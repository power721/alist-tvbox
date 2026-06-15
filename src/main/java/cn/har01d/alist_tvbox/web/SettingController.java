package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.GitHubProxyBenchmarkRequest;
import cn.har01d.alist_tvbox.dto.GitHubProxyNode;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.service.GitHubProxyService;
import cn.har01d.alist_tvbox.service.SettingService;
import cn.har01d.alist_tvbox.util.Utils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingController {
    private final SettingService service;
    private final GitHubProxyService gitHubProxyService;

    public SettingController(SettingService service, GitHubProxyService gitHubProxyService) {
        this.service = service;
        this.gitHubProxyService = gitHubProxyService;
    }

    @GetMapping
    public Map<String, String> findAll() {
        return service.findAll();
    }

    @GetMapping("/{name}")
    public Setting get(@PathVariable String name) {
        return service.get(name);
    }

    @PostMapping("/apikey")
    public String generateApiKey() {
        return service.generateApiKey();
    }

    @PostMapping
    public Setting update(@RequestBody Setting setting, HttpServletRequest request) {
        if ("user_agent".equals(setting.getName())) {
            if (StringUtils.isBlank(setting.getValue())) {
                setting.setValue(Utils.getUserAgent());
            } else if ("current".equals(setting.getValue())) {
                setting.setValue(request.getHeader(HttpHeaders.USER_AGENT));
            }
        }
        return service.update(setting);
    }

    @GetMapping("/export")
    public FileSystemResource exportDatabase(HttpServletResponse response) throws IOException {
        response.addHeader("Content-Disposition", "attachment; filename=\"database-" + LocalDate.now() + ".zip\"");
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        return service.exportDatabase();
    }

    /**
     * 获取预设的 GitHub 代理节点列表
     */
    @GetMapping("/github-proxy/nodes")
    public List<GitHubProxyNode> getGitHubProxyNodes() {
        return gitHubProxyService.getDefaultNodes();
    }

    /**
     * 并发测速多个 GitHub 代理节点（5线程）- 同步版本
     */
    @PostMapping("/github-proxy/benchmark")
    public List<GitHubProxyNode> benchmarkGitHubProxyNodes(@RequestBody GitHubProxyBenchmarkRequest request) {
        return gitHubProxyService.benchmarkNodes(request.getUrls());
    }

    /**
     * 启动异步测速任务（实时更新）
     */
    @PostMapping("/github-proxy/benchmark/start")
    public Map<String, Object> startBenchmark(@RequestBody GitHubProxyBenchmarkRequest request) {
        gitHubProxyService.benchmarkNodesAsync(request.getUrls());
        return Map.of("success", true, "message", "测速任务已启动");
    }

    /**
     * 获取测速结果（实时更新）
     */
    @GetMapping("/github-proxy/benchmark/results")
    public Map<String, Object> getBenchmarkResults() {
        return Map.of(
            "results", gitHubProxyService.getBenchmarkResults(),
            "isRunning", gitHubProxyService.isBenchmarking()
        );
    }

    /**
     * 获取已配置的 GitHub 代理列表
     */
    @GetMapping("/github-proxy/list")
    public List<String> getGitHubProxyList() {
        return gitHubProxyService.readProxyListFromFile();
    }

    /**
     * 保存 GitHub 代理列表（最多 5 个）
     */
    @PostMapping("/github-proxy/list")
    public Map<String, Object> saveGitHubProxyList(@RequestBody List<String> proxyList) throws IOException {
        gitHubProxyService.saveProxyListToFile(proxyList);
        return Map.of("success", true, "count", Math.min(proxyList.size(), 5));
    }

    /**
     * 获取用户自定义的 GitHub 代理节点列表
     */
    @GetMapping("/github-proxy/custom-nodes")
    public List<String> getCustomNodes() {
        Setting setting = service.get("github_custom_nodes");
        if (setting == null || setting.getValue() == null || setting.getValue().isEmpty()) {
            return List.of();
        }
        return List.of(setting.getValue().split("\n"));
    }

    /**
     * 保存用户自定义的 GitHub 代理节点列表
     */
    @PostMapping("/github-proxy/custom-nodes")
    public Map<String, Object> saveCustomNodes(@RequestBody List<String> nodes) {
        String value = String.join("\n", nodes);
        Setting setting = service.get("github_custom_nodes");
        if (setting == null) {
            setting = new Setting();
            setting.setName("github_custom_nodes");
        }
        setting.setValue(value);
        service.update(setting);
        return Map.of("success", true, "count", nodes.size());
    }

}
