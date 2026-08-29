package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.model.PluginFilterConfigSchema;
import cn.har01d.alist_tvbox.service.PluginFileSyncService;
import cn.har01d.alist_tvbox.service.PluginService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.temporal.ChronoUnit;
import java.util.List;

@RestController
@RequestMapping("/api/plugins")
@PreAuthorize("hasAnyAuthority('ADMIN', 'CLIENT')")
public class PluginController {
    private final PluginService pluginService;
    private final PluginFileSyncService pluginFileSyncService;

    private record PluginImportRequest(String url) {
    }

    private record PluginBatchDeleteRequest(List<Integer> ids) {
    }

    public PluginController(PluginService pluginService, PluginFileSyncService pluginFileSyncService) {
        this.pluginService = pluginService;
        this.pluginFileSyncService = pluginFileSyncService;
    }

    @GetMapping
    public List<Plugin> findAll() {
        return pluginService.findAll().stream()
                .peek(e -> e.setLastCheckedAt(e.getLastCheckedAt().truncatedTo(ChronoUnit.SECONDS)))
                .toList();
    }

    @PostMapping
    public Plugin create(@RequestBody Plugin plugin) {
        return pluginService.create(plugin);
    }

    @PostMapping("/import")
    public PluginService.ImportResult importPlugins(@RequestBody PluginImportRequest request) {
        return pluginService.importFromSource(request.url());
    }

    @PutMapping("/{id}")
    public Plugin update(@PathVariable Integer id, @RequestBody Plugin plugin) {
        return pluginService.update(id, plugin);
    }

    @PostMapping("/{id}/refresh")
    public Plugin refresh(@PathVariable Integer id) {
        return pluginService.refresh(id);
    }

    @GetMapping("/{id}/config-schema")
    public PluginFilterConfigSchema configSchema(@PathVariable Integer id) {
        return pluginService.readConfigSchema(id);
    }

    @PostMapping("/scan")
    public void scan() {
        pluginFileSyncService.reconcile();
    }

    @PostMapping("/reorder")
    public void reorder(@RequestBody List<Integer> ids) {
        pluginService.reorder(ids);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Integer id) {
        pluginService.delete(id);
    }

    @PostMapping("/delete-batch")
    public int deleteBatch(@RequestBody PluginBatchDeleteRequest request) {
        return pluginService.deleteBatch(request.ids());
    }
}
