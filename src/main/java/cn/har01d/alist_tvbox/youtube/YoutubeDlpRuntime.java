package cn.har01d.alist_tvbox.youtube;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.exception.BadGatewayException;
import cn.har01d.alist_tvbox.service.GitHubProxyService;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * yt-dlp 运行时:python3 探测、yt-dlp 源码包下载/解压/版本管理与子进程执行。
 *
 * <p>交付形态用 GitHub Releases 的 yt-dlp.tar.gz 源码包(纯 Python,Alpine musl 可直接跑),
 * 解压后经 PYTHONPATH 由 {@code python3 -m yt_dlp} 调起,不依赖 pip 与系统包。
 * 官方 standalone 二进制是 glibc 专享,musl 容器跑不了,勿改回二进制形态。</p>
 *
 * <p>选 yt-dlp 而非 Java 库(sealedtx/java-youtube-downloader)是维护性决策:
 * 后者单维护者曾停更 17 个月导致 #760 整站移除;yt-dlp 由社区维护,YouTube 改版通常数日内修复,
 * 且运行时自更新不需要重发镜像。</p>
 */
@Slf4j
@Service
public class YoutubeDlpRuntime {
    private static final Duration EXEC_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration SHORT_TIMEOUT = Duration.ofSeconds(20);
    private static final String LATEST_RELEASE_API = "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest";
    private static final String DEFAULT_DOWNLOAD_URL = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.tar.gz";
    private static final String GITHUB_TOKEN_HEADER = "Authorization";
    private static final Pattern TAG_PATTERN = Pattern.compile("releases/tag/((?:\\d{4}\\.\\d+\\.\\d+))");

    private final AppProperties appProperties;
    private final GitHubProxyService gitHubProxyService;
    private final ObjectMapper objectMapper;

    private final AtomicBoolean downloading = new AtomicBoolean();
    private final ExecutorService warmupExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "yt-dlp-warmup");
        t.setDaemon(true);
        return t;
    });

    private volatile String pythonBin;
    private volatile String pythonVersion;
    private volatile boolean pythonProbed;
    private volatile String version;
    private volatile String proxy;
    private volatile String latestVersion;
    private volatile long latestVersionAt;

    public YoutubeDlpRuntime(AppProperties appProperties, GitHubProxyService gitHubProxyService, ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        this.gitHubProxyService = gitHubProxyService;
        this.objectMapper = objectMapper;
        try {
            Path path = Utils.getDataPath("proxy.txt");
            if (Files.exists(path)) {
                String line = Files.readString(path).trim();
                URI uri = URI.create(line);
                if (uri.getHost() != null && uri.getPort() > 0) {
                    log.debug("use http proxy for yt-dlp: {}", uri.getHost());
                    proxy = line;
                }
            }
        } catch (Exception e) {
            log.warn("read proxy.txt failed", e);
        }
    }

    /** python3 与 yt-dlp 包是否就绪;未下载时触发一次后台安装。 */
    public boolean isAvailable() {
        if (!detectPython() || !Files.isDirectory(packageRoot())) {
            ensureInstalledAsync();
            return false;
        }
        return true;
    }

    private boolean detectPython() {
        if (pythonProbed) {
            return pythonBin != null;
        }
        synchronized (this) {
            if (pythonProbed) {
                return pythonBin != null;
            }
            // yt-dlp 要求 Python 3.11+;老 Alpine 基座/Ubuntu 22.04 的系统 python3 是 3.10,
            // 探测按版本门控并依次尝试带版本号的解释器(覆盖 PATH 落在旧系统 python 的环境)
            Set<String> candidates = new LinkedHashSet<>();
            addCandidate(candidates, System.getProperty("youtube.python"));
            addCandidate(candidates, System.getenv("YOUTUBE_PYTHON"));
            candidates.addAll(List.of("python3", "python3.13", "python3.12", "python3.11",
                    "/usr/bin/python3", "/usr/local/bin/python3", "python", "/usr/bin/python"));
            String foundVersion = null;
            String tooOld = null;
            for (String candidate : candidates) {
                try {
                    Process p = new ProcessBuilder(candidate, "-c",
                            "import sys; v = '%d.%d' % sys.version_info[:2]; print(v); sys.exit(0 if sys.version_info >= (3, 11) else 1)")
                            .redirectErrorStream(true).start();
                    if (!p.waitFor(5, TimeUnit.SECONDS)) {
                        p.destroyForcibly();
                        continue;
                    }
                    String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                    if (p.exitValue() == 0 && !output.isEmpty()) {
                        pythonBin = candidate;
                        foundVersion = output.lines().findFirst().orElse("");
                        break;
                    }
                    if (p.exitValue() == 1 && output.matches("(?s).*\\b3\\.\\d+.*")) {
                        tooOld = candidate + " " + output.lines().findFirst().map(s -> "(" + s + ")").orElse("");
                    }
                } catch (Exception e) {
                    log.debug("python probe failed: {}", candidate);
                }
            }
            pythonProbed = true;
            pythonVersion = foundVersion;
            if (pythonBin != null) {
                log.info("yt-dlp runtime python={} {}", pythonBin, foundVersion);
            } else if (tooOld != null) {
                pythonVersion = tooOld;
                log.warn("yt-dlp 运行时不可用:现有 python 过旧 {} 需 3.11+;安装 3.11+ 或以 -Dyoutube.python=/path 指定", tooOld);
            } else {
                log.warn("yt-dlp 运行时不可用:未找到 python3");
            }
        }
        return pythonBin != null;
    }

    private static void addCandidate(Set<String> candidates, String candidate) {
        if (candidate != null && !candidate.isBlank()) {
            candidates.add(candidate.trim());
        }
    }

    /** yt_dlp 包根目录(PYTHONPATH 指向这里)。tar.gz 是仓库快照,包在 <data>/yt-dlp/yt-dlp/ 下。 */
    private Path packageRoot() {
        return Utils.getDataPath("yt-dlp", "yt-dlp");
    }

    public String getVersion() {
        if (version == null) {
            try {
                version = exec(List.of("--version"), SHORT_TIMEOUT).trim();
                log.info("yt-dlp version: {}", version);
            } catch (Exception e) {
                log.warn("get yt-dlp version failed", e);
            }
        }
        return version;
    }

    /** 缺包时同步安装(首个请求或定时任务触发);其余请求直接失败等下一轮。 */
    private void ensureInstalled() {
        if (Files.isDirectory(packageRoot()) || !downloading.compareAndSet(false, true)) {
            return;
        }
        try {
            log.info("yt-dlp missing, downloading from {}", StringUtils.abbreviate(resolveDownloadUrls(null).get(0), 90));
            downloadAndExtract();
            log.info("yt-dlp installed: {}", getVersion());
        } catch (Exception e) {
            log.warn("yt-dlp install failed", e);
            throw new BadGatewayException("yt-dlp 下载失败: " + e.getMessage());
        } finally {
            downloading.set(false);
        }
    }

    private void ensureInstalledAsync() {
        if (detectPython()) {
            warmupExecutor.submit(() -> {
                try {
                    ensureInstalled();
                } catch (Exception e) {
                    log.debug("async yt-dlp install failed: {}", e.getMessage());
                }
            });
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmup() {
        ensureInstalledAsync();
    }

    /** 每日比对 GitHub 最新 release,落后则更新;GitHub 不可达时静默跳过。 */
    @Scheduled(cron = "0 47 6 * * ?")
    public void autoUpdate() {
        if (!appProperties.isYoutubeDlpAutoUpdate() || !detectPython() || !Files.isDirectory(packageRoot())) {
            return;
        }
        try {
            String latestVersion = fetchLatestVersion();
            String current = getVersion();
            if (latestVersion != null && current != null && !latestVersion.equals(current)) {
                log.info("yt-dlp update: {} -> {}", current, latestVersion);
                downloadAndExtract();
                version = null;
                log.info("yt-dlp updated: {}", getVersion());
            }
        } catch (Exception e) {
            log.debug("yt-dlp auto update skipped: {}", e.getMessage());
        }
    }

    /**
     * 运行时状态(网页展示):当前版本 + 最新版本。
     * latest 缓存 5 分钟避免 GitHub API 限流,refresh=true 强制刷新。
     */
    public Map<String, Object> status(boolean refresh) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (!detectPython()) {
            map.put("available", false);
            map.put("python", "");
            map.put("version", "");
            map.put("latest", "");
            map.put("message", pythonVersion == null
                    ? "未找到 python3,yt-dlp 需 Python 3.11+"
                    : "python 过旧 " + pythonVersion + ",yt-dlp 需 Python 3.11+");
            return map;
        }
        if (refresh || latestVersion == null
                || System.currentTimeMillis() - latestVersionAt > Duration.ofMinutes(5).toMillis()) {
            try {
                String value = fetchLatestVersion();
                if (value != null) {
                    latestVersion = value;
                    latestVersionAt = System.currentTimeMillis();
                }
            } catch (Exception e) {
                log.debug("fetch latest version failed: {}", e.getMessage());
            }
        }
        String current = getVersion();
        map.put("available", current != null && Files.isDirectory(packageRoot()));
        map.put("python", pythonBin);
        map.put("version", current == null ? "" : current);
        map.put("latest", latestVersion == null ? "" : latestVersion);
        return map;
    }

    /** 手动更新:强制重下(版本相同也重装),与自动更新共用下载闸门。 */
    public Map<String, Object> update() {
        if (!detectPython()) {
            throw new BadGatewayException("yt-dlp 运行时不可用:需 Python 3.11+(可用 -Dyoutube.python=/path 指定)");
        }
        if (downloading.get()) {
            return Map.of("updated", false, "version", StringUtils.defaultString(getVersion()), "message", "更新进行中");
        }
        downloading.set(true);
        try {
            log.info("yt-dlp manual update");
            downloadAndExtract();
            String current = getVersion();
            latestVersionAt = 0;
            return Map.of("updated", true, "version", StringUtils.defaultString(current));
        } catch (Exception e) {
            log.warn("yt-dlp manual update failed", e);
            throw new BadGatewayException("yt-dlp 更新失败: " + e.getMessage());
        } finally {
            downloading.set(false);
        }
    }

    private String fetchLatestVersion() throws IOException {
        HttpURLConnection conn = openConnection(LATEST_RELEASE_API, "application/vnd.github+json");
        int code = conn.getResponseCode();
        if (code == 200) {
            try (InputStream in = conn.getInputStream()) {
                JsonNode node = objectMapper.readTree(in);
                String tag = node.path("tag_name").asText(null);
                if (tag != null) {
                    return tag;
                }
            }
        } else {
            log.debug("github latest release api: {}", code);
        }
        // GitHub 直连不通:走已配置的 GitHub 代理(github_proxy.txt)解析 latest 页面
        // (302 落地 URL 含 tag,或页面内 og/canonical 链接含 releases/tag/{tag})
        for (String url : applyProxies("https://github.com/yt-dlp/yt-dlp/releases/latest", gitHubProxyService.readProxyListFromFile())) {
            String tag = fetchLatestVersionFromProxy(url);
            if (tag != null) {
                return tag;
            }
        }
        return null;
    }

    private String fetchLatestVersionFromProxy(String url) {
        try {
            HttpURLConnection conn = openConnection(url, "text/html");
            if (conn.getResponseCode() != 200) {
                log.debug("github proxy latest page: {} {}", conn.getResponseCode(), StringUtils.abbreviate(url, 60));
                return null;
            }
            String tag = parseTag(conn.getURL().toString());
            if (tag != null) {
                return tag;
            }
            try (InputStream in = conn.getInputStream()) {
                String body = new String(in.readNBytes(512 * 1024), StandardCharsets.UTF_8);
                java.util.regex.Matcher matcher = TAG_PATTERN.matcher(body);
                return matcher.find() ? matcher.group(1) : null;
            }
        } catch (Exception e) {
            log.debug("github proxy latest page failed: {} {}", StringUtils.abbreviate(url, 60), e.getMessage());
            return null;
        }
    }

    static String parseTag(String url) {
        java.util.regex.Matcher matcher = TAG_PATTERN.matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 下载候选地址解析:
     * 1. 用户显式配置过 youtubeDlpUrl(非默认值)→ 直接用之(GitHub 代理不介入)
     * 2. 指定 tag → 静态资产 releases/download/{tag}/yt-dlp.tar.gz(代理兼容性最好)
     * 3. 未指定 tag → latest/download 动态资产
     * 每种按 github_proxy.txt 代理列表逐个拼前缀(空行=直连),末尾补直连兜底(FileDownloader 同惯例)
     */
    List<String> resolveDownloadUrls(String tag) {
        String configured = appProperties.getYoutubeDlpUrl();
        if (!DEFAULT_DOWNLOAD_URL.equals(configured)) {
            return List.of(configured);
        }
        String base = tag != null && !tag.isBlank()
                ? "https://github.com/yt-dlp/yt-dlp/releases/download/" + tag + "/yt-dlp.tar.gz"
                : DEFAULT_DOWNLOAD_URL;
        return applyProxies(base, gitHubProxyService.readProxyListFromFile());
    }

    static List<String> applyProxies(String url, List<String> proxies) {
        List<String> urls = new ArrayList<>();
        for (String proxy : proxies) {
            if (proxy == null || proxy.trim().isEmpty()) {
                continue; // 空行=直连,由末尾兜底统一补
            }
            String prefix = proxy.trim();
            urls.add((prefix.endsWith("/") ? prefix : prefix + "/") + url);
        }
        urls.add(url);
        return urls;
    }

    private void downloadAndExtract() throws IOException {
        Path home = Utils.getDataPath("yt-dlp");
        Files.createDirectories(home);
        Path tarball = Files.createTempFile(home, "ytdlp-", ".tar.gz");
        Path staging = Files.createTempDirectory(home, "extract-");
        try {
            downloadTo(tarball, null);
            extractTarGz(tarball, staging);
            // tar.gz 内是仓库快照,顶层目录名固定为 yt-dlp;解压结果整体换入
            Path extracted = staging.resolve("yt-dlp");
            if (!Files.isDirectory(extracted)) {
                throw new IOException("unexpected tarball layout: " + staging);
            }
            Path target = home.resolve("yt-dlp");
            Path old = home.resolve("yt-dlp.old");
            deleteRecursively(old);
            if (Files.isDirectory(target)) {
                Files.move(target, old);
            }
            try {
                Files.move(extracted, target);
            } catch (IOException e) {
                if (Files.isDirectory(old)) {
                    Files.move(old, target);
                }
                throw e;
            }
            deleteRecursively(old);
            version = null;
        } finally {
            Files.deleteIfExists(tarball);
            deleteRecursively(staging);
        }
    }

    /** 下载 tar.gz 到目标文件:依次尝试候选地址;latest/download 全军覆没时按已知 tag 再试静态资产。 */
    private void downloadTo(Path tarball, String tag) throws IOException {
        List<String> urls = resolveDownloadUrls(tag);
        IOException last = null;
        for (String url : urls) {
            try {
                downloadTo0(tarball, url);
                return;
            } catch (IOException e) {
                log.debug("download failed: {} {}", StringUtils.abbreviate(url, 70), e.getMessage());
                last = e;
            }
        }
        if (tag == null && StringUtils.isNotBlank(latestVersion)) {
            for (String url : resolveDownloadUrls(latestVersion)) {
                try {
                    downloadTo0(tarball, url);
                    return;
                } catch (IOException e) {
                    log.debug("download failed: {} {}", StringUtils.abbreviate(url, 70), e.getMessage());
                    last = e;
                }
            }
        }
        throw last != null ? last : new IOException("no download url");
    }

    private void downloadTo0(Path tarball, String url) throws IOException {
        HttpURLConnection conn = openConnection(url, null);
        if (conn.getResponseCode() != 200) {
            throw new IOException("download HTTP " + conn.getResponseCode() + " from " + StringUtils.abbreviate(url, 80));
        }
        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, tarball, StandardCopyOption.REPLACE_EXISTING);
        }
        // 部分代理把反爬验证页当 200 返回(几 KB 的 HTML):按 gzip 魔数校验拦下
        try (InputStream in = Files.newInputStream(tarball)) {
            if ((in.read() & 0xFF) != 0x1F || (in.read() & 0xFF) != 0x8B) {
                throw new IOException("download is not a gzip payload (proxy challenge page?): " + StringUtils.abbreviate(url, 80));
            }
        }
    }

    private static void extractTarGz(Path tarball, Path target) throws IOException {
        try (TarArchiveInputStream in = new TarArchiveInputStream(new GZIPInputStream(Files.newInputStream(tarball)))) {
            TarArchiveEntry entry;
            int count = 0;
            while ((entry = in.getNextTarEntry()) != null) {
                if (++count > 20000) {
                    throw new IOException("tarball entry count exceeded");
                }
                Path file = target.resolve(entry.getName()).normalize();
                if (!file.startsWith(target)) {
                    continue; // zip-slip 防御
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(file);
                } else {
                    Files.createDirectories(file.getParent());
                    Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    private HttpURLConnection openConnection(String url, String accept) throws IOException {
        // GitHub 与 YouTube 的访问统一走 proxy.txt(若配置),YouTube 域名见 exec 的 --proxy
        HttpURLConnection conn;
        if (proxy != null && url.contains("github.com")) {
            URI uri = URI.create(proxy);
            java.net.Proxy httpProxy = new java.net.Proxy(java.net.Proxy.Type.HTTP,
                    new java.net.InetSocketAddress(uri.getHost(), uri.getPort()));
            conn = (HttpURLConnection) new URL(url).openConnection(httpProxy);
        } else {
            conn = (HttpURLConnection) new URL(url).openConnection();
        }
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
        conn.setRequestProperty("User-Agent", "alist-tvbox");
        if (accept != null) {
            conn.setRequestProperty("Accept", accept);
        }
        String token = System.getenv("GITHUB_TOKEN");
        if (token != null && url.contains("github.com")) {
            conn.setRequestProperty(GITHUB_TOKEN_HEADER, "Bearer " + token);
        }
        return conn;
    }

    /** 执行 yt-dlp 子进程并返回 stdout(一般为 JSON)。 */
    public String exec(List<String> ytArgs, Duration timeout) {
        if (!detectPython()) {
            throw new BadGatewayException("yt-dlp 运行时不可用:需 Python 3.11+(可用 -Dyoutube.python=/path 指定)");
        }
        ensureInstalled();
        List<String> command = new ArrayList<>();
        command.add(pythonBin);
        command.add("-m");
        command.add("yt_dlp");
        command.add("--ignore-config");
        command.add("--no-warnings");
        command.add("--no-progress");
        command.add("--retries");
        command.add("2");
        command.add("--socket-timeout");
        command.add("15");
        if (proxy != null) {
            command.add("--proxy");
            command.add(proxy);
        }
        command.addAll(ytArgs);
        log.debug("yt-dlp exec: {}", String.join(" ", ytArgs));
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            // PYTHONPATH 必须显式注入:宿主 python 若装过 pip 版 yt-dlp 会遮蔽下载的副本,
            // 容器里则根本没有 yt_dlp 模块,-m 直接失败
            builder.environment().put("PYTHONPATH", packageRoot().toString());
            Process process = builder.start();
            // stdout/stderr 必须由独立线程排空,否则缓冲填满后进程卡死,waitFor 超时永不触发
            StringBuilder stderr = new StringBuilder();
            byte[][] stdoutHolder = new byte[1][];
            Thread outThread = new Thread(() -> {
                try (var in = process.getInputStream()) {
                    stdoutHolder[0] = in.readAllBytes();
                } catch (IOException ignored) {
                }
            });
            Thread errThread = new Thread(() -> {
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (stderr.length() < 8000) {
                            stderr.append(line).append('\n');
                        }
                    }
                } catch (IOException ignored) {
                }
            });
            outThread.setDaemon(true);
            errThread.setDaemon(true);
            outThread.start();
            errThread.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new BadGatewayException("yt-dlp 执行超时");
            }
            outThread.join(3000);
            errThread.join(1000);
            int exit = process.exitValue();
            String stdout = stdoutHolder[0] == null ? "" : new String(stdoutHolder[0], StandardCharsets.UTF_8);
            if (exit != 0) {
                String tail = stderr.toString();
                String message = tail.lines().filter(StringUtils::isNotBlank).reduce((a, b) -> b).orElse("exit " + exit);
                throw new BadGatewayException("yt-dlp 执行失败: " + StringUtils.abbreviate(message, 300));
            }
            return stdout;
        } catch (BadGatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new BadGatewayException("yt-dlp 执行异常: " + e.getMessage());
        }
    }

    public JsonNode execJson(List<String> ytArgs, Duration timeout) {
        try {
            return objectMapper.readTree(exec(ytArgs, timeout));
        } catch (BadGatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new BadGatewayException("yt-dlp 输出解析失败: " + e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        warmupExecutor.shutdownNow();
    }
}
