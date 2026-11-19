package cn.har01d.atmaintenance.service;

import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.PluginRepository;
import cn.har01d.alist_tvbox.service.PluginService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.util.Utils;
import cn.har01d.atmaintenance.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class EmbeddedAtHostAccess implements AtHostAccess {
    private final PluginRepository repository;
    private final ObjectProvider<EmbeddedAtRuntimeService> runtime;
    @Value("${atv.data.dir:./data}") private String dataDirectory;

    @Override
    public AtConnection connection() {
        String scope = Hashes.sha256(Path.of(dataDirectory).toAbsolutePath().normalize().toString());
        return new AtConnection(true, "current-at", Hashes.sha256("embedded-at\n" + scope),
                scope, "host", "at_embedded_repository");
    }

    @Override
    public List<AtPluginSummary> plugins() {
        return repository.findAll().stream().sorted(Comparator.comparing(Plugin::getId)).map(plugin -> {
            String url = plugin.getUrl() == null ? "" : plugin.getUrl();
            String path = url.split("[?#]", 2)[0].toLowerCase(Locale.ROOT);
            String kind = PluginService.isWebPagePlugin(plugin) ? "webpage"
                    : path.endsWith(".py") ? "python" : path.endsWith(".txt") ? "secspider" : "unknown";
            return new AtPluginSummary(plugin.getId(), plugin.getName(), plugin.getExternalId(),
                    plugin.isEnabled(), plugin.isT4Visible(), plugin.getVersion(), kind,
                    SubscriptionService.pluginSiteKey(plugin), Hashes.sha256(url),
                    plugin.getLastError() != null && !plugin.getLastError().isBlank());
        }).toList();
    }

    @Override
    public byte[] readPythonSource(int pluginId) {
        var plugin = repository.findById(pluginId).orElseThrow(() -> denied("AT_PLUGIN_NOT_FOUND"));
        String url = plugin.getUrl() == null ? "" : plugin.getUrl();
        String path = url.split("[?#]", 2)[0].toLowerCase(Locale.ROOT);
        if (!plugin.isEnabled() || !plugin.isT4Visible() || PluginService.isWebPagePlugin(plugin)
                || !(path.endsWith(".py") || path.endsWith(".txt")))
            throw denied("AT_SOURCE_NOT_READABLE");
        try {
            byte[] bytes;
            if (url.startsWith("/static/")) {
                Path root = Utils.getWebPath("static").toRealPath();
                Path file = root.resolve(url.substring("/static/".length())).normalize().toRealPath();
                if (!file.startsWith(root) || !Files.isRegularFile(file) || Files.size(file) > 1_048_576)
                    throw denied("AT_SOURCE_NOT_READABLE");
                try (var input = Files.newInputStream(file)) { bytes = input.readNBytes(1_048_577); }
            } else {
                // Saved host content is a snapshot, not a request to the remote URL.
                if (plugin.getContent() == null) throw denied("AT_SOURCE_NOT_READABLE");
                bytes = plugin.getContent().getBytes(StandardCharsets.UTF_8);
            }
            if (bytes.length == 0 || bytes.length > 1_048_576) throw denied("AT_SOURCE_NOT_READABLE");
            return bytes;
        } catch (IOException | java.nio.file.InvalidPathException error) {
            throw denied("AT_SOURCE_NOT_READABLE");
        }
    }

    @Override
    public AtRuntimeIdentity runtimeIdentity(int pluginId) { return runtime.getObject().observe(pluginId); }

    private ResponseStatusException denied(String code) { return new ResponseStatusException(HttpStatus.CONFLICT, code); }
}
