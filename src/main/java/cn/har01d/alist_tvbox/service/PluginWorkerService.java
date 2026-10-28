package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.exception.BadGatewayException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * T4 服务端插件运行时:python3 子进程按 JSON 行协议执行 Atvp.py 装载的爬虫插件
 * (脚本 scripts/t4worker.py,蓝图 docs/t4-sidecar-borrow-assessment.md A1)。
 *
 * 隔离与降级:第三方密文代码只进子进程,崩溃/超时只杀 worker 不伤 JVM;
 * 无 python3(老镜像/裸机)时 {@link #isAvailable()} 为 false,插件源不进 T4 配置。
 * 每 siteKey 一个常驻 worker(串行调用,v1 口径),空闲 10 分钟回收,进程死亡下次调用自愈重启;
 * 插件初始化(密文下载+解密+装载)全局串行,防多源同时冷启把机器打满。
 */
@Slf4j
@Service
public class PluginWorkerService {
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration INIT_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Worker> workers = new ConcurrentHashMap<>();
    private final AtomicReference<Boolean> available = new AtomicReference<>();

    private volatile Path workerScript;
    private volatile Path atvpScript;
    private volatile String pythonBin;

    public PluginWorkerService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** python3 与 worker 脚本是否就绪(结果缓存)。 */
    public boolean isAvailable() {
        Boolean state = available.get();
        if (state != null) {
            return state;
        }
        boolean ready = prepareScripts() && detectPython();
        available.set(ready);
        log.info("t4 plugin runtime {}: python={}", ready ? "ready" : "unavailable(plugin sources stay out of T4)",
                ready ? pythonBin : "not found");
        return ready;
    }

    private boolean detectPython() {
        // -c import 探测连依赖一起验:Atvp 需要 requests/lxml(镜像 apk 安装,裸机常缺),
        // 缺依赖时直接判不可用,插件源维持移除口径(而非运行时逐插件报错)
        List<String> candidates = new java.util.ArrayList<>();
        candidates.add(System.getProperty("t4.python"));
        candidates.add(System.getenv("T4_PYTHON"));
        candidates.addAll(List.of("python3", "/usr/bin/python3", "/usr/local/bin/python3"));
        for (String candidate : candidates) {
            if (StringUtils.isBlank(candidate)) {
                continue;
            }
            try {
                ProcessBuilder pb = new ProcessBuilder(candidate, "-c", "import requests, lxml.etree, Crypto.Cipher.AES");
                pb.redirectErrorStream(true);
                Process process = pb.start();
                if (process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) {
                    pythonBin = candidate;
                    return true;
                }
                process.destroyForcibly();
            } catch (Exception e) {
                log.debug("python probe failed: {}", candidate);
            }
        }
        return false;
    }

    private boolean prepareScripts() {
        try {
            Path dir = Files.createTempDirectory("t4-plugins");
            atvpScript = extract("static/Atvp.py", dir.resolve("Atvp.py"));
            workerScript = extract("scripts/t4worker.py", dir.resolve("t4worker.py"));
            return true;
        } catch (Exception e) {
            log.warn("t4 plugin worker scripts extract failed", e);
            return false;
        }
    }

    private Path extract(String classpath, Path target) throws IOException {
        try (var in = new ClassPathResource(classpath).getInputStream()) {
            Files.copy(in, target);
        }
        return target;
    }

    /**
     * 五动作统一入口;params 透传给 worker(filter/t/pg/extend/ids/wd/quick/play/flag)。
     * 返回插件的原始 JSON(home/category/detail/search/play 均为 TVBox Result 形态)。
     */
    public JsonNode call(String siteKey, String extend, String method, Map<String, Object> params) {
        if (!isAvailable()) {
            throw new BadGatewayException("服务端插件运行时不可用(未安装 python3)");
        }
        Worker worker = acquire(siteKey, extend);
        try {
            return worker.request(method, params, CALL_TIMEOUT);
        } catch (WorkerDeadException e) {
            workers.remove(siteKey, worker);
            worker.close();
            throw new BadGatewayException("插件进程异常退出: " + siteKey);
        }
    }

    private Worker acquire(String siteKey, String extend) {
        Worker worker = workers.get(siteKey);
        if (worker != null && worker.alive()) {
            return worker;
        }
        synchronized (workers) {
            worker = workers.get(siteKey);
            if (worker == null || !worker.alive()) {
                if (worker != null) {
                    worker.close();
                }
                Worker created = new Worker(siteKey);
                created.init(extend);
                workers.put(siteKey, created);
                return created;
            }
            return worker;
        }
    }

    @Scheduled(fixedDelay = 300_000)
    public void evictIdle() {
        if (workers.isEmpty()) {
            return;
        }
        workers.entrySet().removeIf(entry -> {
            Worker worker = entry.getValue();
            if (worker.idleFor().compareTo(IDLE_TIMEOUT) > 0) {
                worker.close();
                log.debug("t4 plugin worker evicted: {}", entry.getKey());
                return true;
            }
            return false;
        });
    }

    @PreDestroy
    public void shutdown() {
        workers.values().forEach(Worker::close);
        workers.clear();
    }

    private class Worker {
        private final String siteKey;
        private Process process;
        private OutputStream stdin;
        private BufferedReader stdout;
        private volatile Instant lastUsed = Instant.now();

        Worker(String siteKey) {
            this.siteKey = siteKey;
        }

        void init(String extend) {
            try {
                process = new ProcessBuilder(pythonBin, workerScript.toString(), atvpScript.toString()).start();
                stdin = process.getOutputStream();
                stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new BadGatewayException("插件进程启动失败: " + siteKey);
            }
            try {
                // Atvp init 含密文下载+解密+inner 装载,超时单独放宽
                request("init", Map.of("extend", extend == null ? "" : extend), INIT_TIMEOUT);
                log.info("t4 plugin worker started: {}", siteKey);
            } catch (RuntimeException e) {
                int exitValue = process.isAlive() ? -1 : process.exitValue();
                String tail = readErrorTail();
                log.warn("t4 plugin worker init failed: {} exit={} cmd={} {} {}", siteKey, exitValue,
                        pythonBin, workerScript, tail, e);
                close();
                String message = e.getMessage() == null ? "worker exited" : e.getMessage();
                throw new BadGatewayException("插件初始化失败: " + siteKey + " - " + message + tail);
            }
        }

        boolean alive() {
            return process != null && process.isAlive();
        }

        Duration idleFor() {
            return Duration.between(lastUsed, Instant.now());
        }

        synchronized JsonNode request(String method, Map<String, Object> params, Duration timeout) {
            if (!alive()) {
                throw new WorkerDeadException();
            }
            String requestId = UUID.randomUUID().toString().substring(0, 8);
            ObjectNode request = objectMapper.createObjectNode();
            request.put("id", requestId);
            request.put("method", method);
            request.set("params", objectMapper.valueToTree(params));
            try {
                stdin.write((objectMapper.writeValueAsString(request) + "\n").getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            } catch (IOException e) {
                throw new WorkerDeadException();
            }
            long deadline = System.nanoTime() + timeout.toNanos();
            // 防御:插件后台线程(如 Atvp 预热守护)的裸 print 可能混进 stdout——worker 已把
            // 协议输出与 fd1 隔离,此处再兜一层:非本请求 id 的行跳过继续等,直到超时
            while (true) {
                String line = readLine(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
                if (StringUtils.isBlank(line)) {
                    throw new WorkerDeadException();
                }
                try {
                    JsonNode response = objectMapper.readTree(line);
                    if (!requestId.equals(response.path("id").asText())) {
                        log.debug("t4 worker skip foreign line: {}", StringUtils.abbreviate(line, 120));
                        continue;
                    }
                    lastUsed = Instant.now();
                    if (response.path("ok").asBoolean(false)) {
                        return response.get("result");
                    }
                    throw new BadGatewayException("插件执行失败: " + response.path("error").path("message").asText("unknown"));
                } catch (IOException e) {
                    throw new WorkerDeadException();
                }
            }
        }

        private String readLine(Duration timeout) {
            long deadline = System.nanoTime() + timeout.toNanos();
            try {
                while (!stdout.ready()) {
                    // waitFor 返回 true = 进程已退出(输出永远不会再有);false = 还活着,继续等
                    if (process.waitFor(20, TimeUnit.MILLISECONDS)) {
                        throw new WorkerDeadException();
                    }
                    if (System.nanoTime() > deadline) {
                        log.warn("t4 worker call timeout: {}", siteKey);
                        close();
                        throw new BadGatewayException("插件执行超时: " + siteKey);
                    }
                }
                return stdout.readLine();
            } catch (IOException e) {
                throw new WorkerDeadException();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new WorkerDeadException();
            }
        }

        /** 进程死透后 stderr 尾部(缺依赖/语法错误的第一现场),最多 400 字。 */
        private String readErrorTail() {
            try {
                if (process != null && !process.waitFor(2, TimeUnit.SECONDS)) {
                    return "";
                }
                String text = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)
                        .replaceAll("\\s+", " ").trim();
                return text.isEmpty() ? "" : " | " + text.substring(Math.max(0, text.length() - 400));
            } catch (Exception e) {
                return "";
            }
        }

        void close() {
            Process current = process;
            if (current != null) {
                current.destroyForcibly();
            }
        }
    }

    private static class WorkerDeadException extends RuntimeException {
        WorkerDeadException() {
            super("worker process exited");
        }
    }
}
