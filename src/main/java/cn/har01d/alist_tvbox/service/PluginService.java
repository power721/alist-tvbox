package cn.har01d.alist_tvbox.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.PluginRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class PluginService {
    private final PluginRepository pluginRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public PluginService(PluginRepository pluginRepository, RestTemplateBuilder builder, ObjectMapper objectMapper) {
        this.pluginRepository = pluginRepository;
        this.restTemplate = builder.build();
        this.objectMapper = objectMapper;
    }

    public record ImportResult(
            String sourceUrl,
            int createdCount,
            int refreshedCount,
            int skippedCount,
            int failedCount,
            List<String> created,
            List<String> refreshed,
            List<String> skipped,
            List<String> failed
    ) {
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
        String body = downloadPlugin(plugin.getUrl());
        String sourceName = deriveSourceName(plugin.getUrl());
        plugin.setSourceName(sourceName);
        plugin.setName(StringUtils.defaultIfBlank(plugin.getName(), sourceName));
        plugin.setContent(body);
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
            String body = downloadPlugin(input.getUrl());
            String previousSourceName = plugin.getSourceName();
            String newSourceName = deriveSourceName(input.getUrl());
            if (StringUtils.isBlank(plugin.getName()) || StringUtils.equals(plugin.getName(), previousSourceName)) {
                plugin.setName(newSourceName);
            }
            plugin.setUrl(input.getUrl());
            plugin.setSourceName(newSourceName);
            plugin.setContent(body);
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
        return refresh(plugin);
    }

    @Transactional
    public ImportResult importFromSource(String url) {
        String sourceUrl = resolveImportSource(url);
        String payload = downloadText(sourceUrl, "spiders.json 不可访问");
        List<String> pluginUrls = readPluginUrls(payload);
        List<String> created = new ArrayList<>();
        List<String> refreshed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (String pluginUrl : pluginUrls) {
            String normalizedPluginUrl = StringUtils.trimToNull(pluginUrl);
            if (normalizedPluginUrl == null) {
                continue;
            }
            if (!seen.add(normalizedPluginUrl)) {
                skipped.add(normalizedPluginUrl);
                continue;
            }
            Plugin existing = pluginRepository.findByUrl(normalizedPluginUrl).orElse(null);
            try {
                if (existing != null) {
                    Plugin plugin = refresh(existing);
                    if (StringUtils.isBlank(plugin.getLastError())) {
                        refreshed.add(plugin.getName());
                    } else {
                        failed.add(normalizedPluginUrl + ": " + plugin.getLastError());
                    }
                } else {
                    Plugin plugin = new Plugin();
                    plugin.setUrl(normalizedPluginUrl);
                    Plugin saved = create(plugin);
                    created.add(saved.getName());
                }
            } catch (RuntimeException e) {
                failed.add(normalizedPluginUrl + ": " + e.getMessage());
            }
        }
        return new ImportResult(
                sourceUrl,
                created.size(),
                refreshed.size(),
                skipped.size(),
                failed.size(),
                created,
                refreshed,
                skipped,
                failed
        );
    }

    private Plugin refresh(Plugin plugin) {
        String previousSourceName = plugin.getSourceName();
        try {
            String body = downloadPlugin(plugin.getUrl());
            String refreshedSourceName = deriveSourceName(plugin.getUrl());
            if (StringUtils.isBlank(plugin.getName()) || StringUtils.equals(plugin.getName(), previousSourceName)) {
                plugin.setName(refreshedSourceName);
            }
            plugin.setSourceName(refreshedSourceName);
            plugin.setContent(body);
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
        Plugin plugin = pluginRepository.findById(id).orElseThrow(NotFoundException::new);
        pluginRepository.delete(plugin);
    }

    private void validateUrlUniqueness(String url, Integer currentId) {
        pluginRepository.findByUrl(url).ifPresent(other -> {
            if (currentId == null || !other.getId().equals(currentId)) {
                throw new BadRequestException("插件地址重复");
            }
        });
    }

    private String resolveImportSource(String url) {
        List<String> candidates = resolveImportCandidates(url);
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }
        for (String candidate : candidates) {
            try {
                String body = restTemplate.getForObject(URI.create(candidate), String.class);
                if (StringUtils.isNotBlank(body)) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // try next candidate
            }
        }
        throw new BadRequestException("spiders.json 不可访问");
    }

    private List<String> resolveImportCandidates(String url) {
        String source = StringUtils.trimToEmpty(url);
        if (source.isBlank()) {
            throw new BadRequestException("仓库地址不能为空");
        }
        URI uri;
        try {
            uri = URI.create(source);
        } catch (Exception e) {
            throw new BadRequestException("仓库地址不正确", e);
        }
        if (!"github.com".equalsIgnoreCase(uri.getHost())) {
            if (StringUtils.endsWith(uri.getPath(), "/spiders.json")) {
                return List.of(uri.toString());
            }
            throw new BadRequestException("不支持的仓库地址");
        }
        String[] segments = StringUtils.strip(uri.getPath(), "/").split("/");
        if (segments.length < 2) {
            throw new BadRequestException("GitHub 仓库地址不正确");
        }
        String owner = segments[0];
        String repo = segments[1];
        if (segments.length >= 5 && "raw".equals(segments[2]) && "refs".equals(segments[3]) && StringUtils.endsWith(uri.getPath(), "/spiders.json")) {
            return List.of(uri.toString());
        }
        if (segments.length >= 4 && ("blob".equals(segments[2]) || "tree".equals(segments[2]))) {
            return List.of("https://github.com/" + owner + "/" + repo + "/raw/refs/heads/" + segments[3] + "/spiders.json");
        }
        return List.of(
                "https://github.com/" + owner + "/" + repo + "/raw/refs/heads/master/spiders.json",
                "https://github.com/" + owner + "/" + repo + "/raw/refs/heads/main/spiders.json"
        );
    }

    private String downloadPlugin(String url) {
        return downloadText(url, "插件地址不可访问");
    }

    private String downloadText(String url, String message) {
        try {
            String body = restTemplate.getForObject(URI.create(url), String.class);
            if (StringUtils.isBlank(body)) {
                throw new BadRequestException(message);
            }
            return body;
        } catch (Exception e) {
            throw new BadRequestException(message, e);
        }
    }

    public String readContent(Integer id) {
        Plugin plugin = pluginRepository.findById(id).orElseThrow(NotFoundException::new);
        if (StringUtils.isBlank(plugin.getContent())) {
            throw new BadRequestException("插件内容为空");
        }
        return plugin.getContent();
    }

    String deriveSourceName(String url) {
        String raw = url.substring(url.lastIndexOf('/') + 1);
        String decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
        int dot = decoded.lastIndexOf('.');
        return dot > 0 ? decoded.substring(0, dot) : decoded;
    }

    private List<String> readPluginUrls(String payload) {
        try {
            return objectMapper.readValue(payload, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new BadRequestException("spiders.json 格式不正确", e);
        }
    }
}
