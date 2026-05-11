package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.PluginRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class PluginService {
    private final PluginRepository pluginRepository;
    private final RestTemplate restTemplate;

    public PluginService(PluginRepository pluginRepository, RestTemplateBuilder builder) {
        this.pluginRepository = pluginRepository;
        this.restTemplate = builder.build();
    }

    public List<Plugin> findAll() {
        return pluginRepository.findAllByOrderBySortOrderAscIdAsc();
    }

    public List<Plugin> findEnabled() {
        return pluginRepository.findByEnabledTrueOrderBySortOrderAscIdAsc();
    }

    @Transactional
    public Plugin create(Plugin plugin) {
        validateUrlUniqueness(plugin.getUrl(), null);
        checkUrlReachable(plugin.getUrl());
        String sourceName = deriveSourceName(plugin.getUrl());
        plugin.setSourceName(sourceName);
        plugin.setName(StringUtils.defaultIfBlank(plugin.getName(), sourceName));
        plugin.setEnabled(true);
        plugin.setSortOrder(pluginRepository.findAllByOrderBySortOrderAscIdAsc().size() + 1);
        plugin.setLastCheckedAt(OffsetDateTime.now());
        plugin.setLastError("");
        return pluginRepository.save(plugin);
    }

    @Transactional
    public Plugin update(Integer id, Plugin input) {
        Plugin plugin = pluginRepository.findById(id).orElseThrow(NotFoundException::new);
        boolean urlChanged = !StringUtils.equals(plugin.getUrl(), input.getUrl());
        if (urlChanged) {
            validateUrlUniqueness(input.getUrl(), id);
            checkUrlReachable(input.getUrl());
            String previousSourceName = plugin.getSourceName();
            String newSourceName = deriveSourceName(input.getUrl());
            if (StringUtils.isBlank(plugin.getName()) || StringUtils.equals(plugin.getName(), previousSourceName)) {
                plugin.setName(newSourceName);
            }
            plugin.setUrl(input.getUrl());
            plugin.setSourceName(newSourceName);
            plugin.setLastCheckedAt(OffsetDateTime.now());
            plugin.setLastError("");
        }
        plugin.setName(StringUtils.defaultIfBlank(input.getName(), plugin.getSourceName()));
        plugin.setEnabled(input.isEnabled());
        plugin.setExtend(input.getExtend());
        return pluginRepository.save(plugin);
    }

    @Transactional
    public Plugin refresh(Integer id) {
        Plugin plugin = pluginRepository.findById(id).orElseThrow(NotFoundException::new);
        String previousSourceName = plugin.getSourceName();
        try {
            checkUrlReachable(plugin.getUrl());
            String refreshedSourceName = deriveSourceName(plugin.getUrl());
            if (StringUtils.isBlank(plugin.getName()) || StringUtils.equals(plugin.getName(), previousSourceName)) {
                plugin.setName(refreshedSourceName);
            }
            plugin.setSourceName(refreshedSourceName);
            plugin.setLastError("");
        } catch (RuntimeException e) {
            plugin.setLastError(e.getMessage());
        }
        plugin.setLastCheckedAt(OffsetDateTime.now());
        return pluginRepository.save(plugin);
    }

    @Transactional
    public void reorder(List<Integer> ids) {
        List<Plugin> plugins = new ArrayList<>();
        int order = 1;
        for (Integer id : ids) {
            Plugin plugin = pluginRepository.findById(id).orElseThrow(NotFoundException::new);
            plugin.setSortOrder(order++);
            plugins.add(plugin);
        }
        pluginRepository.saveAll(plugins);
    }

    public void delete(Integer id) {
        pluginRepository.deleteById(id);
    }

    private void validateUrlUniqueness(String url, Integer currentId) {
        pluginRepository.findByUrl(url).ifPresent(other -> {
            if (currentId == null || !other.getId().equals(currentId)) {
                throw new BadRequestException("插件地址重复");
            }
        });
    }

    private void checkUrlReachable(String url) {
        try {
            restTemplate.getForObject(URI.create(url), String.class);
        } catch (Exception e) {
            throw new BadRequestException("插件地址不可访问", e);
        }
    }

    String deriveSourceName(String url) {
        String raw = url.substring(url.lastIndexOf('/') + 1);
        String decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
        int dot = decoded.lastIndexOf('.');
        return dot > 0 ? decoded.substring(0, dot) : decoded;
    }
}
