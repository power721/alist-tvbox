package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.service.PluginService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.temporal.ChronoUnit;
import java.util.List;

@RestController
@RequestMapping("/api/plugins")
public class PluginController {
    private final PluginService pluginService;

    private record PluginImportRequest(String url) {
    }

    public PluginController(PluginService pluginService) {
        this.pluginService = pluginService;
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

    @PostMapping("/reorder")
    public void reorder(@RequestBody List<Integer> ids) {
        pluginService.reorder(ids);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Integer id) {
        pluginService.delete(id);
    }
}
