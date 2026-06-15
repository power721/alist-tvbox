package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.GitHubProxyNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GitHubProxyService {
    private static final String GITHUB_PROXY_FILE = "/data/github_proxy.txt";
    private static final String TEST_URL = "https://raw.githubusercontent.com/xiaoyaliu00/data/main/version.txt";
    private static final int BENCHMARK_THREADS = 5;
    private static final int CONNECT_TIMEOUT_SECONDS = 3;
    private static final int READ_TIMEOUT_SECONDS = 3;
    private static final int MAX_PROXY_COUNT = 5; // 最多配置 5 个代理
    private static final Pattern VERSION_PATTERN = Pattern.compile("^\\d+\\.\\d+\\.\\d+$"); // 匹配版本号格式，如 0.54.49
    private static final String DIRECT_PROXY = ""; // 直连（无代理）

    private final OkHttpClient httpClient;
    private final Path githubProxyFile;
    private final Map<String, GitHubProxyNode> benchmarkCache = new ConcurrentHashMap<>();
    private volatile boolean isBenchmarking = false;

    public GitHubProxyService() {
        this(Paths.get(GITHUB_PROXY_FILE));
    }

    GitHubProxyService(Path githubProxyFile) {
        this.githubProxyFile = githubProxyFile;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }

    /**
     * 获取预设的 GitHub 代理节点列表（来自 gh_proxy_bench.sh 脚本）
     */
    public List<GitHubProxyNode> getDefaultNodes() {
        List<GitHubProxyNode> nodes = new ArrayList<>();

        // 从脚本嵌入的节点数据构建列表
        nodes.add(new GitHubProxyNode("无代理（直连）", DIRECT_PROXY, "", "", null, null, null, null));
        nodes.add(new GitHubProxyNode("默认节点", "https://gh.llkk.cc/", "gh.llkk.cc", "donate", null, null, null, null));
        nodes.add(new GitHubProxyNode("公益贡献", "https://github.starrlzy.cn/", "github.starrlzy.cn", "donate", null, null, null, null));
        nodes.add(new GitHubProxyNode("高速节点", "https://slink.ltd/", "slink.ltd", "search", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://gh.tryxd.cn/", "gh.tryxd.cn", "search", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://gitproxy.click/", "gitproxy.click", "search", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://github.dpik.top/", "github.dpik.top", "donate", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://ghm.078465.xyz/", "ghm.078465.xyz", "donate", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://github.tbedu.top/", "github.tbedu.top", "donate", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://gh.chjina.com/", "gh.chjina.com", "search", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://github.chenc.dev/", "github.chenc.dev", "search", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://gh.nxnow.top/", "gh.nxnow.top", "search", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://gh.felicity.ac.cn/", "gh.felicity.ac.cn", "donate", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://git.yylx.win/", "git.yylx.win", "donate", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://ghfile.geekertao.top/", "ghfile.geekertao.top", "donate", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://gh.sixyin.com/", "gh.sixyin.com", "search", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://gh.dpik.top/", "gh.dpik.top", "donate", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://gitproxy.mrhjx.cn/", "gitproxy.mrhjx.cn", "search", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://ghproxy.1888866.xyz/", "ghproxy.1888866.xyz", "search", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://jiashu.1win.eu.org/", "jiashu.1win.eu.org", "donate", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://gh-proxy.com/", "gh-proxy.com", "donate", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://github-proxy.memory-echoes.cn/", "github-proxy.memory-echoes.cn", "donate", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://gh.jasonzeng.dev/", "gh.jasonzeng.dev", "search", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://j.1lin.dpdns.org/", "j.1lin.dpdns.org", "donate", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://gh.inkchills.cn/", "gh.inkchills.cn", "donate", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://ghproxy.imciel.com/", "ghproxy.imciel.com", "search", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://tvv.tw/", "tvv.tw", "donate", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://gitproxy.127731.xyz/", "gitproxy.127731.xyz", "donate", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://cdn.akaere.online/", "cdn.akaere.online", "donate", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://ghproxy.cxkpro.top/", "ghproxy.cxkpro.top", "search", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://fastgit.cc/", "fastgit.cc", "search", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://gh.idayer.com/", "gh.idayer.com", "search", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://ghfast.top/", "ghfast.top", "search", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://ghp.arslantu.xyz/", "ghp.arslantu.xyz", "search", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://ghproxy.monkeyray.net/", "ghproxy.monkeyray.net", "search", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://gh.noki.icu/", "gh.noki.icu", "search", null, null, null, null));
        nodes.add(new GitHubProxyNode("备用节点", "https://cdn.gh-proxy.com/", "cdn.gh-proxy.com", "donate", null, null, null, null));

        return nodes;
    }

    /**
     * 异步并发测速多个代理节点，结果实时更新到缓存
     */
    public void benchmarkNodesAsync(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return;
        }

        if (isBenchmarking) {
            log.warn("测速任务已在进行中，忽略新请求");
            return;
        }

        isBenchmarking = true;
        benchmarkCache.clear();

        // 初始化缓存（标记为进行中）
        for (String url : urls) {
            benchmarkCache.computeIfAbsent(url, key -> {
                GitHubProxyNode node = new GitHubProxyNode();
                node.setUrl(key);
                node.setLabel("测速中...");
                node.setSuccess(null);
                return node;
            });
        }

        ExecutorService executor = Executors.newFixedThreadPool(BENCHMARK_THREADS);

        for (String url : urls) {
            executor.submit(() -> {
                try {
                    GitHubProxyNode result = benchmarkSingleNode(url);
                    benchmarkCache.put(url, result);
                    log.info("测速完成并更新缓存: {}", url);
                } catch (Exception e) {
                    log.error("测速异常: {}", url, e);
                    GitHubProxyNode errorNode = new GitHubProxyNode();
                    errorNode.setUrl(url);
                    errorNode.setSuccess(false);
                    errorNode.setError("异常: " + e.getMessage());
                    benchmarkCache.put(url, errorNode);
                }
            });
        }

        // 异步等待所有任务完成
        executor.shutdown();
        CompletableFuture.runAsync(() -> {
            try {
                executor.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                log.error("等待测速任务超时", e);
            } finally {
                isBenchmarking = false;
                log.info("所有测速任务完成");
            }
        });
    }

    /**
     * 获取测速结果缓存
     */
    public Map<String, GitHubProxyNode> getBenchmarkResults() {
        return new HashMap<>(benchmarkCache);
    }

    /**
     * 检查是否正在测速
     */
    public boolean isBenchmarking() {
        return isBenchmarking;
    }

    /**
     * 并发测速多个代理节点（5线程）- 同步版本
     */
    public List<GitHubProxyNode> benchmarkNodes(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return Collections.emptyList();
        }

        ExecutorService executor = Executors.newFixedThreadPool(BENCHMARK_THREADS);
        List<CompletableFuture<GitHubProxyNode>> futures = new ArrayList<>();

        for (String url : urls) {
            CompletableFuture<GitHubProxyNode> future = CompletableFuture.supplyAsync(() -> {
                return benchmarkSingleNode(url);
            }, executor);
            futures.add(future);
        }

        // 等待所有测速完成
        List<GitHubProxyNode> results = futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());

        executor.shutdown();
        return results;
    }

    /**
     * 测速单个节点
     */
    private GitHubProxyNode benchmarkSingleNode(String proxyUrl) {
        GitHubProxyNode node = new GitHubProxyNode();
        node.setUrl(proxyUrl);

        // 处理空字符串（直连）
        if (DIRECT_PROXY.equals(proxyUrl)) {
            node.setLabel("无代理（直连）");
            node.setHost(DIRECT_PROXY);
            return benchmarkDirect(node);
        }

        // 确保以 / 结尾
        String normalizedUrl = proxyUrl.endsWith("/") ? proxyUrl : proxyUrl + "/";
        node.setUrl(normalizedUrl);

        // 提取主机名
        try {
            String host = new java.net.URL(normalizedUrl).getHost();
            node.setHost(host);
            node.setLabel(host);
        } catch (Exception e) {
            node.setHost("");
            node.setLabel("无效 URL");
        }

        long startTime = System.currentTimeMillis();
        String testUrl = normalizedUrl.isEmpty() ? TEST_URL : normalizedUrl + TEST_URL;

        Request request = new Request.Builder()
                .url(testUrl)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            long endTime = System.currentTimeMillis();
            int latency = (int) (endTime - startTime);

            if (response.isSuccessful() && response.body() != null) {
                String content = response.body().string().trim();

                // 验证内容是否为版本号格式（如 0.54.49）
                if (VERSION_PATTERN.matcher(content).matches()) {
                    node.setSuccess(true);
                    node.setLatency(latency);
                    node.setSpeed(0.0);
                    log.info("测速成功: {} - {}ms (版本: {})", node.getHost(), latency, content);
                } else {
                    node.setSuccess(false);
                    node.setError("内容验证失败（非版本号）: " + content.substring(0, Math.min(20, content.length())));
                    log.warn("测速失败: {} - 内容不符合版本号格式", node.getHost());
                }
            } else {
                node.setSuccess(false);
                node.setError("HTTP " + response.code());
                log.warn("测速失败: {} - HTTP {}", node.getHost(), response.code());
            }
        } catch (IOException e) {
            node.setSuccess(false);
            node.setError(e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("测速失败: {} - {}", node.getHost(), e.getMessage());
        }

        return node;
    }

    /**
     * 测速直连（无代理）
     */
    private GitHubProxyNode benchmarkDirect(GitHubProxyNode node) {
        long startTime = System.currentTimeMillis();

        Request request = new Request.Builder()
                .url(TEST_URL)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            long endTime = System.currentTimeMillis();
            int latency = (int) (endTime - startTime);

            if (response.isSuccessful() && response.body() != null) {
                String content = response.body().string().trim();

                // 验证内容是否为版本号格式（如 0.54.49）
                if (VERSION_PATTERN.matcher(content).matches()) {
                    node.setSuccess(true);
                    node.setLatency(latency);
                    node.setSpeed(0.0);
                    log.info("直连测速成功: {}ms (版本: {})", latency, content);
                } else {
                    node.setSuccess(false);
                    node.setError("内容验证失败（非版本号）: " + content.substring(0, Math.min(20, content.length())));
                    log.warn("直连测速失败: 内容不符合版本号格式");
                }
            } else {
                node.setSuccess(false);
                node.setError("HTTP " + response.code());
                log.warn("直连测速失败: HTTP {}", response.code());
            }
        } catch (IOException e) {
            node.setSuccess(false);
            node.setError(e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("直连测速失败: {}", e.getMessage());
        }

        return node;
    }

    /**
     * 保存 GitHub 代理列表到文件 /data/github_proxy.txt（多行格式）
     * 每个代理一行，确保以 / 结尾，最多保存 5 个
     */
    public void saveProxyListToFile(List<String> proxyUrls) throws IOException {
        Path filePath = githubProxyFile;

        // 限制最多 5 个代理
        List<String> limitedProxies = proxyUrls.stream()
                .limit(MAX_PROXY_COUNT)
                .map(this::normalizeProxyUrl)
                .collect(Collectors.toList());

        // 写入文件（多行）
        Files.createDirectories(filePath.getParent());
        Files.write(filePath, limitedProxies, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        log.info("已保存 {} 个 GitHub 代理到文件: {}", limitedProxies.size(), filePath);
    }

    /**
     * 从文件读取 GitHub 代理列表（多行格式）
     */
    public List<String> readProxyListFromFile() {
        Path filePath = githubProxyFile;
        if (!Files.exists(filePath)) {
            return Collections.emptyList();
        }

        try {
            return Files.readAllLines(filePath).stream()
                    .map(String::trim)
                    .limit(MAX_PROXY_COUNT)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("读取 GitHub 代理文件失败: {}", filePath, e);
            return Collections.emptyList();
        }
    }

    /**
     * 规范化代理 URL：确保以 / 结尾（空字符串除外）
     */
    private String normalizeProxyUrl(String proxyUrl) {
        if (DIRECT_PROXY.equals(proxyUrl)) {
            return DIRECT_PROXY;
        }
        String trimmed = proxyUrl.trim();
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }

    /**
     * 使用代理列表下载文件，支持自动 fallback
     * 按顺序尝试每个代理，最多尝试 5 个，失败则尝试下一个
     */
    public Response downloadWithFallback(String githubUrl, List<String> proxyList) throws IOException {
        if (proxyList == null || proxyList.isEmpty()) {
            // 没有配置代理，直接下载
            return downloadDirect(githubUrl);
        }

        // 限制最多尝试 5 个代理
        List<String> limitedProxies = proxyList.stream()
                .limit(MAX_PROXY_COUNT)
                .collect(Collectors.toList());

        IOException lastException = null;

        for (int i = 0; i < limitedProxies.size(); i++) {
            String proxy = limitedProxies.get(i);
            try {
                return tryDownloadWithProxy(proxy, githubUrl, i + 1, limitedProxies.size());
            } catch (IOException e) {
                lastException = e;
                log.warn("代理 {}/{} ({}) 下载失败: {}", i + 1, limitedProxies.size(), proxy, e.getMessage());
                // 继续尝试下一个代理
            }
        }

        // 所有代理都失败
        throw new IOException("所有 GitHub 代理均失败 (" + limitedProxies.size() + " 个已尝试)", lastException);
    }

    /**
     * 尝试使用单个代理下载
     */
    private Response tryDownloadWithProxy(String proxy, String githubUrl, int index, int total) throws IOException {
        if (DIRECT_PROXY.equals(proxy)) {
            // 空字符串表示直连
            log.info("尝试直连下载 (代理 {}/{}): {}", index, total, githubUrl);
            return downloadDirect(githubUrl);
        } else {
            // 使用代理下载
            String proxyUrl = normalizeProxyUrl(proxy) + githubUrl;
            log.info("尝试使用代理 {}/{} 下载: {} -> {}", index, total, proxy, githubUrl);
            return downloadDirect(proxyUrl);
        }
    }

    /**
     * 直接下载（不使用代理或使用完整 URL）
     */
    private Response downloadDirect(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        Response response = httpClient.newCall(request).execute();
        if (!response.isSuccessful()) {
            response.close();
            throw new IOException("下载失败: HTTP " + response.code());
        }
        return response;
    }

    /**
     * 兼容旧版：保存单个代理到文件（现在保存为单行列表）
     */
    public void saveToFile(String proxyUrl) throws IOException {
        saveProxyListToFile(Collections.singletonList(proxyUrl));
    }

    /**
     * 兼容旧版：从文件读取单个代理（现在读取第一行）
     */
    public String readFromFile() {
        List<String> proxies = readProxyListFromFile();
        return proxies.isEmpty() ? "" : proxies.get(0);
    }
}
