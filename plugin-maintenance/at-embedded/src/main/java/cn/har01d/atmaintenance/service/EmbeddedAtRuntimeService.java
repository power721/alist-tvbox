package cn.har01d.atmaintenance.service;

import cn.har01d.alist_tvbox.entity.PluginRepository;
import cn.har01d.alist_tvbox.service.PluginService;
import cn.har01d.alist_tvbox.service.PluginWorkerService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.atmaintenance.dto.AtRuntimeIdentity;
import cn.har01d.atmaintenance.dto.AtProtectedFiltersIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class EmbeddedAtRuntimeService {
    private final PluginRepository plugins;
    private final PluginWorkerService workers;
    private final EmbeddedFilterIdentityService filters;

    public AtRuntimeIdentity observe(int pluginId) {
        var plugin = plugins.findById(pluginId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "AT_PLUGIN_NOT_FOUND"));
        String siteKey = SubscriptionService.pluginSiteKey(plugin);
        if (PluginService.isWebPagePlugin(plugin)) return absent(pluginId, siteKey, "BLOCKED");
        JsonNode status = workers.runtimeStatus(siteKey, null);
        String state = status.path("configurationState").asText();
        if (state.equals("maintenance_blocked")) {
            String generation = status.path("identity").path("generationId").asText();
            return identity(pluginId, siteKey, null, "unobserved", "worker-loader-v1:unobserved",
                    "BLOCKED", false, generation.matches("[a-f0-9]{32}") ? generation : null, null);
        }
        if (state.equals("not_running")) return absent(pluginId, siteKey, "NOT_LOADED");
        JsonNode identity = status.path("identity");
        if (!Set.of("loaded", "matching", "pending_restart").contains(state)
                || !identity.path("authority").asText().equals("worker_loader_observed")
                || !hash(identity, "sourceTextSha256") || !hash(identity, "workerSha256")
                || !hash(identity, "atvpSha256")
                || !identity.path("generationId").asText().matches("[a-f0-9]{32}")) {
            return absent(pluginId, siteKey, "UNKNOWN");
        }
        return identity(pluginId, siteKey, identity.path("sourceTextSha256").asText(),
                "utf8_compilation_text", "worker-loader-v1:" + identity.path("workerSha256").asText()
                        + ":" + identity.path("atvpSha256").asText(),
                "LOADED", true, identity.path("generationId").asText(), filters.observe(pluginId, siteKey, identity));
    }

    private static boolean hash(JsonNode value, String field) {
        return value.path(field).isTextual() && value.path(field).asText().matches("[a-f0-9]{64}");
    }

    private AtRuntimeIdentity absent(int id, String key, String state) {
        return identity(id, key, null, "unobserved", "worker-loader-v1:unobserved", state, false, null, null);
    }

    private AtRuntimeIdentity identity(int id, String key, String sha, String kind, String revision,
                                       String state, boolean loaded, String generation,
                                       AtProtectedFiltersIdentity filterIdentity) {
        return new AtRuntimeIdentity(id, key, sha, revision, state, loaded, generation, Instant.now(),
                "at_embedded_worker_snapshot",
                "实际 loader 编译文本与有序过滤器装载快照；不比较当前保存配置，"
                        + "不核验远端新鲜度或媒体健康，不表示候选已发布或实际切换。", kind, null, filterIdentity);
    }
}
