package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.PluginFilter;
import cn.har01d.alist_tvbox.model.PluginFilterConfigSchema;
import cn.har01d.alist_tvbox.service.PluginFilterService;
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
@RequestMapping("/api/plugin-filters")
public class PluginFilterController {
    private final PluginFilterService pluginFilterService;

    private record PluginFilterBatchDeleteRequest(List<Integer> ids) {
    }

    public PluginFilterController(PluginFilterService pluginFilterService) {
        this.pluginFilterService = pluginFilterService;
    }

    @GetMapping
    public List<PluginFilter> findAll() {
        return pluginFilterService.findAll().stream()
                .peek(e -> {
                    if (e.getLastCheckedAt() != null) {
                        e.setLastCheckedAt(e.getLastCheckedAt().truncatedTo(ChronoUnit.SECONDS));
                    }
                })
                .toList();
    }

    @PostMapping
    public PluginFilter create(@RequestBody PluginFilter filter) {
        return pluginFilterService.create(filter);
    }

    @PutMapping("/{id}")
    public PluginFilter update(@PathVariable Integer id, @RequestBody PluginFilter filter) {
        return pluginFilterService.update(id, filter);
    }

    @PostMapping("/{id}/refresh")
    public PluginFilter refresh(@PathVariable Integer id) {
        return pluginFilterService.refresh(id);
    }

    @GetMapping("/{id}/config-schema")
    public PluginFilterConfigSchema configSchema(@PathVariable Integer id) {
        return pluginFilterService.readConfigSchema(id);
    }

    @PostMapping("/reorder")
    public void reorder(@RequestBody List<Integer> ids) {
        pluginFilterService.reorder(ids);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Integer id) {
        pluginFilterService.delete(id);
    }

    @PostMapping("/delete-batch")
    public int deleteBatch(@RequestBody PluginFilterBatchDeleteRequest request) {
        return pluginFilterService.deleteBatch(request.ids());
    }
}
