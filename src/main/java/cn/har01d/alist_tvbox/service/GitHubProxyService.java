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
import java.util.stream.Collectors;

@Slf4j
@Service
public class GitHubProxyService {
    private static final String GITHUB_PROXY_FILE = "/data/github_proxy.txt";
    private static final String TEST_URL = "https://github.com/har01d5/tvbox/raw/refs/heads/master/spiders_v2.json";
    private static final int BENCHMARK_THREADS = 5;
    private static final int CONNECT_TIMEOUT_SECONDS = 8;
    private static final int READ_TIMEOUT_SECONDS = 30;

    private final OkHttpClient httpClient;

    public GitHubProxyService() {
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
        nodes.add(new GitHubProxyNode("无代理（直连）", "", "", "", null, null, null, null));
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
     * 并发测速多个代理节点（5线程）
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
        if (proxyUrl == null || proxyUrl.trim().isEmpty()) {
            node.setLabel("无代理（直连）");
            node.setHost("");
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

            if (response.isSuccessful()) {
                node.setSuccess(true);
                node.setLatency(latency);
                node.setSpeed(0.0); // 这里简化处理，不计算速度
                log.info("测速成功: {} - {}ms", node.getHost(), latency);
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

            if (response.isSuccessful()) {
                node.setSuccess(true);
                node.setLatency(latency);
                node.setSpeed(0.0);
                log.info("直连测速成功: {}ms", latency);
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
     * 保存 GitHub 代理到文件 /data/github_proxy.txt
     * 确保以 / 结尾
     */
    public void saveToFile(String proxyUrl) throws IOException {
        Path filePath = Paths.get(GITHUB_PROXY_FILE);

        // 确保以 / 结尾（空字符串除外）
        String normalizedUrl = "";
        if (proxyUrl != null && !proxyUrl.trim().isEmpty()) {
            normalizedUrl = proxyUrl.trim().endsWith("/") ? proxyUrl.trim() : proxyUrl.trim() + "/";
        }

        // 写入文件
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, normalizedUrl, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        log.info("已保存 GitHub 代理到文件: {} -> {}", GITHUB_PROXY_FILE, normalizedUrl);
    }

    /**
     * 从文件读取 GitHub 代理
     */
    public String readFromFile() {
        Path filePath = Paths.get(GITHUB_PROXY_FILE);
        if (!Files.exists(filePath)) {
            return "";
        }

        try {
            return Files.readString(filePath).trim();
        } catch (IOException e) {
            log.error("读取 GitHub 代理文件失败: {}", GITHUB_PROXY_FILE, e);
            return "";
        }
    }
}
