package cn.har01d.alist_tvbox.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.PluginRepository;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import cn.har01d.alist_tvbox.util.Utils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class PluginService {
    private static final String PLUGIN_INDEX_FILE = "spiders_v2.json";
    private static final Pattern PLUGIN_ID = Pattern.compile("(?m)^\\s*//@id:([^\\s]+)\\s*$");
    private static final Pattern PLUGIN_VERSION = Pattern.compile("(?m)^\\s*//@version:(\\d+)\\s*$");
    private static final Pattern PLUGIN_NAME = Pattern.compile("(?m)^\\s*//@name:(.+)\\s*$");
    private static final String GITHUB_PROXY = "github_proxy";

    private final PluginRepository pluginRepository;
    private final SettingRepository settingRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final SubscriptionSourceService subscriptionSourceService;
    private final GitHubProxyService gitHubProxyService;

    @Autowired
    public PluginService(PluginRepository pluginRepository,
                         SettingRepository settingRepository,
                         RestTemplateBuilder builder,
                         ObjectMapper objectMapper,
                         TransactionTemplate transactionTemplate,
                         SubscriptionSourceService subscriptionSourceService,
                         GitHubProxyService gitHubProxyService) {
        this.pluginRepository = pluginRepository;
        this.settingRepository = settingRepository;
        this.restTemplate = builder.build();
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.subscriptionSourceService = subscriptionSourceService;
        this.gitHubProxyService = gitHubProxyService;
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

    private record ImportEntry(String url, String externalId, Integer version, boolean valid) {
    }

    private record DownloadedPlugin(String body, String sourceName, String externalId, Integer version) {
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
        applyDownloadedPlugin(plugin, downloadPluginData(plugin.getUrl()), null, false);
        validateExternalIdUniqueness(plugin.getExternalId(), null);
        plugin.setEnabled(true);
        plugin.setSortOrder(subscriptionSourceService.nextSortOrder());
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
            plugin.setUrl(input.getUrl());
            applyDownloadedPlugin(plugin, downloadPluginData(input.getUrl()), null, shouldUpdateName(plugin));
            validateExternalIdUniqueness(plugin.getExternalId(), id);
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
        return refresh(plugin, null);
    }

    public ImportResult importFromSource(String url) {
        String sourceUrl = resolveImportSource(url);
        String payload = downloadText(sourceUrl, PLUGIN_INDEX_FILE + " 不可访问");
        List<ImportEntry> pluginUrls = readPluginUrls(payload, sourceUrl);
        List<String> created = new ArrayList<>();
        List<String> refreshed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (ImportEntry entry : pluginUrls) {
            String normalizedPluginUrl = StringUtils.trimToNull(entry.url());
            if (normalizedPluginUrl == null) {
                continue;
            }
            String dedupeKey = StringUtils.trimToNull(entry.externalId());
            if (dedupeKey == null) {
                dedupeKey = normalizedPluginUrl;
            }
            if (!seen.add(dedupeKey)) {
                skipped.add(normalizedPluginUrl);
                continue;
            }

            try {
                Plugin existing = findExistingPlugin(normalizedPluginUrl, entry.externalId());
                if (existing != null && entry.version() != null && entry.version().equals(existing.getVersion())) {
                    backfillImportMetadata(existing, normalizedPluginUrl, entry.externalId());
                    skipped.add(normalizedPluginUrl);
                    continue;
                }

                DownloadedPlugin downloadedPlugin;
                try {
                    downloadedPlugin = downloadPluginData(normalizedPluginUrl);
                } catch (RuntimeException e) {
                    if (existing != null) {
                        transactionTemplate.execute(status -> markRefreshFailure(existing.getId(), e.getMessage()));
                    }
                    throw e;
                }
                if (existing != null) {
                    Plugin plugin = transactionTemplate.execute(status -> {
                        Plugin current = pluginRepository.findById(existing.getId()).orElseThrow(NotFoundException::new);
                        current.setUrl(normalizedPluginUrl);
                        return refresh(current, downloadedPlugin, entry.externalId());
                    });
                    if (plugin != null && StringUtils.isBlank(plugin.getLastError())) {
                        refreshed.add(plugin.getName());
                    } else {
                        failed.add(normalizedPluginUrl + ": " + (plugin == null ? "刷新失败" : plugin.getLastError()));
                    }
                } else {
                    Plugin saved = transactionTemplate.execute(status -> createImportedPlugin(normalizedPluginUrl, downloadedPlugin, entry.externalId(), entry.valid()));
                    if (saved != null) {
                        created.add(saved.getName());
                    }
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

    @Transactional
    public void delete(Integer id) {
        Plugin plugin = pluginRepository.findById(id).orElseThrow(NotFoundException::new);
        pluginRepository.delete(plugin);
        subscriptionSourceService.normalizeSortOrders();
    }

    @Transactional
    public int deleteBatch(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<Plugin> plugins = pluginRepository.findAllById(ids);
        pluginRepository.deleteAll(plugins);
        subscriptionSourceService.normalizeSortOrders();
        return plugins.size();
    }

    private Plugin createImportedPlugin(String url, DownloadedPlugin downloadedPlugin, String entryExternalId, boolean enabled) {
        Plugin plugin = new Plugin();
        plugin.setUrl(url);
        validateUrlUniqueness(url, null);
        applyDownloadedPlugin(plugin, downloadedPlugin, entryExternalId, false);
        validateExternalIdUniqueness(plugin.getExternalId(), null);
        plugin.setEnabled(enabled);
        plugin.setSortOrder(subscriptionSourceService.nextSortOrder());
        plugin.setLastCheckedAt(OffsetDateTime.now());
        plugin.setLastError("");
        return pluginRepository.save(plugin);
    }

    private Plugin refresh(Plugin plugin, DownloadedPlugin downloadedPlugin) {
        return refresh(plugin, downloadedPlugin, null);
    }

    private Plugin refresh(Plugin plugin, DownloadedPlugin downloadedPlugin, String entryExternalId) {
        try {
            applyDownloadedPlugin(plugin, downloadedPlugin == null ? downloadPluginData(plugin.getUrl()) : downloadedPlugin, entryExternalId, shouldUpdateName(plugin));
            validateExternalIdUniqueness(plugin.getExternalId(), plugin.getId());
            plugin.setLastError("");
        } catch (RuntimeException e) {
            plugin.setLastError(e.getMessage());
        }
        plugin.setLastCheckedAt(OffsetDateTime.now());
        return pluginRepository.save(plugin);
    }

    private Plugin markRefreshFailure(Integer id, String message) {
        Plugin plugin = pluginRepository.findById(id).orElseThrow(NotFoundException::new);
        plugin.setLastError(message);
        plugin.setLastCheckedAt(OffsetDateTime.now());
        return pluginRepository.save(plugin);
    }

    private void backfillImportMetadata(Plugin existing, String url, String externalId) {
        String id = StringUtils.trimToNull(externalId);
        boolean urlChanged = !StringUtils.equals(existing.getUrl(), url);
        boolean externalIdChanged = id != null && !StringUtils.equals(existing.getExternalId(), id);
        if (!urlChanged && !externalIdChanged) {
            return;
        }
        transactionTemplate.execute(status -> {
            Plugin plugin = pluginRepository.findById(existing.getId()).orElseThrow(NotFoundException::new);
            if (urlChanged) {
                plugin.setUrl(url);
            }
            if (externalIdChanged) {
                plugin.setExternalId(id);
                validateExternalIdUniqueness(plugin.getExternalId(), plugin.getId());
            }
            pluginRepository.save(plugin);
            return null;
        });
    }

    private boolean shouldUpdateName(Plugin plugin) {
        return StringUtils.isBlank(plugin.getName()) || StringUtils.equals(plugin.getName(), plugin.getSourceName());
    }

    private void validateUrlUniqueness(String url, Integer currentId) {
        pluginRepository.findByUrl(url).ifPresent(other -> {
            if (currentId == null || !other.getId().equals(currentId)) {
                throw new BadRequestException("插件地址重复");
            }
        });
    }

    private void validateExternalIdUniqueness(String externalId, Integer currentId) {
        String id = StringUtils.trimToNull(externalId);
        if (id == null) {
            return;
        }
        pluginRepository.findByExternalId(id).ifPresent(other -> {
            if (currentId == null || !other.getId().equals(currentId)) {
                throw new BadRequestException("插件ID重复");
            }
        });
    }

    private Plugin findExistingPlugin(String url, String externalId) {
        String id = StringUtils.trimToNull(externalId);
        if (id != null) {
            Plugin existing = pluginRepository.findByExternalId(id).orElse(null);
            if (existing != null) {
                return existing;
            }
        }
        return pluginRepository.findByUrl(url).orElse(null);
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
        throw new BadRequestException(PLUGIN_INDEX_FILE + " 不可访问");
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
            if (StringUtils.endsWith(uri.getPath(), "/" + PLUGIN_INDEX_FILE)) {
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
        if (segments.length >= 5 && "raw".equals(segments[2]) && "refs".equals(segments[3]) && StringUtils.endsWith(uri.getPath(), "/" + PLUGIN_INDEX_FILE)) {
            return List.of(uri.toString());
        }
        if (segments.length >= 4 && ("blob".equals(segments[2]) || "tree".equals(segments[2]))) {
            return List.of("https://github.com/" + owner + "/" + repo + "/raw/refs/heads/" + segments[3] + "/" + PLUGIN_INDEX_FILE);
        }
        return List.of(
                "https://github.com/" + owner + "/" + repo + "/raw/refs/heads/master/" + PLUGIN_INDEX_FILE,
                "https://github.com/" + owner + "/" + repo + "/raw/refs/heads/main/" + PLUGIN_INDEX_FILE
        );
    }

    private String downloadPlugin(String url) {
        return downloadText(url, "插件地址不可访问");
    }

    private DownloadedPlugin downloadPluginData(String url) {
        String body = downloadPlugin(url);
        String name = extractPluginName(body);
        if (StringUtils.isBlank(name)) {
            name = deriveSourceName(url);
        }
        return new DownloadedPlugin(body, name, extractPluginId(body), extractPluginVersion(body));
    }

    private String downloadText(String url, String message) {
        // Validate URL to prevent SSRF attacks
        if (!isValidUrl(url)) {
            throw new BadRequestException("Invalid or unsafe URL: " + url);
        }

        // 如果不是 GitHub URL，直接下载
        if (!StringUtils.startsWith(url, "https://github.com/")) {
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

        // GitHub URL - 使用多代理 fallback
        List<String> proxyList = gitHubProxyService.readProxyListFromFile();
        Exception lastException = null;

        if (proxyList.isEmpty()) {
            // 没有配置代理，使用旧逻辑（读取单个代理配置）
            String proxy = settingRepository.findById(GITHUB_PROXY)
                    .map(e -> StringUtils.trimToEmpty(e.getValue()))
                    .orElse("");
            String finalUrl = proxy.isBlank() ? url : StringUtils.appendIfMissing(proxy, "/") + url;
            try {
                String body = restTemplate.getForObject(URI.create(finalUrl), String.class);
                if (StringUtils.isBlank(body)) {
                    throw new BadRequestException(message);
                }
                return body;
            } catch (Exception e) {
                throw new BadRequestException(message, e);
            }
        }

        // 使用配置的多代理列表，逐个尝试（最多 5 个）
        for (int i = 0; i < Math.min(proxyList.size(), 5); i++) {
            String proxy = proxyList.get(i);
            String finalUrl;

            if (proxy == null || proxy.trim().isEmpty()) {
                // 空字符串表示直连
                finalUrl = url;
            } else {
                finalUrl = StringUtils.appendIfMissing(proxy, "/") + url;
            }

            try {
                String body = restTemplate.getForObject(URI.create(finalUrl), String.class);
                if (StringUtils.isNotBlank(body)) {
                    return body;
                }
            } catch (Exception e) {
                lastException = e;
                // 继续尝试下一个代理
            }
        }

        // 所有代理都失败
        throw new BadRequestException(message + " (所有代理均失败)", lastException);
    }

    /**
     * Validate URL to prevent SSRF attacks
     * - Only allow HTTP/HTTPS protocols
     * - Block private IP ranges and localhost
     * - Block special hostnames
     */
    /**
     * Validate URL to prevent access to obviously dangerous endpoints.
     * Simplified for private network deployments - only blocks localhost and metadata endpoints.
     */
    private boolean isValidUrl(String url) {
        return Utils.isSafeExternalUrl(url);
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

    private List<ImportEntry> readPluginUrls(String payload, String sourceUrl) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (!root.isArray()) {
                throw new BadRequestException(PLUGIN_INDEX_FILE + " 格式不正确");
            }
            List<ImportEntry> urls = new ArrayList<>();
            for (JsonNode node : root) {
                if (node.isTextual()) {
                    urls.add(new ImportEntry(resolvePluginUrl(sourceUrl, node.asText()), null, null, true));
                    continue;
                }
                if (node.isObject()) {
                    String file = StringUtils.trimToNull(node.path("file").asText(null));
                    if (file == null) {
                        throw new BadRequestException(PLUGIN_INDEX_FILE + " 格式不正确");
                    }
                    String externalId = StringUtils.trimToNull(node.path("id").asText(null));
                    Integer version = node.path("version").canConvertToInt() ? node.path("version").intValue() : null;
                    boolean valid = !node.has("valid") || node.path("valid").asBoolean(true);
                    urls.add(new ImportEntry(resolvePluginUrl(sourceUrl, file), externalId, version, valid));
                    continue;
                }
                throw new BadRequestException(PLUGIN_INDEX_FILE + " 格式不正确");
            }
            return urls;
        } catch (Exception e) {
            if (e instanceof BadRequestException badRequestException) {
                throw badRequestException;
            }
            throw new BadRequestException(PLUGIN_INDEX_FILE + " 格式不正确", e);
        }
    }

    private String resolvePluginUrl(String sourceUrl, String path) {
        String candidate = StringUtils.trimToEmpty(path);
        if (candidate.isBlank()) {
            throw new BadRequestException(PLUGIN_INDEX_FILE + " 格式不正确");
        }
        return URI.create(sourceUrl).resolve(candidate).toString();
    }

    private Integer extractPluginVersion(String body) {
        if (StringUtils.isBlank(body)) {
            return null;
        }
        Matcher matcher = PLUGIN_VERSION.matcher(body);
        if (!matcher.find()) {
            return null;
        }
        return Integer.parseInt(matcher.group(1));
    }

    private String extractPluginId(String body) {
        if (StringUtils.isBlank(body)) {
            return null;
        }
        Matcher matcher = PLUGIN_ID.matcher(body);
        if (!matcher.find()) {
            return null;
        }
        return StringUtils.trimToNull(matcher.group(1));
    }

    private String extractPluginName(String body) {
        if (StringUtils.isBlank(body)) {
            return null;
        }
        Matcher matcher = PLUGIN_NAME.matcher(body);
        if (!matcher.find()) {
            return null;
        }
        return StringUtils.trimToNull(matcher.group(1));
    }

    private String buildRemoteUrl(String url) {
        if (!StringUtils.startsWith(url, "https://github.com/")) {
            return url;
        }
        String proxy = settingRepository.findById(GITHUB_PROXY)
                .map(e -> StringUtils.trimToEmpty(e.getValue()))
                .orElse("");
        if (proxy.isBlank()) {
            return url;
        }
        return StringUtils.appendIfMissing(proxy, "/") + url;
    }

    static boolean isPythonPluginUrl(String url) {
        try {
            String path = URI.create(StringUtils.trimToEmpty(url)).getPath();
            return StringUtils.endsWithIgnoreCase(path, ".py");
        } catch (Exception e) {
            return false;
        }
    }

    private void applyDownloadedPlugin(Plugin plugin, DownloadedPlugin downloadedPlugin, String entryExternalId, boolean updateName) {
        plugin.setSourceName(downloadedPlugin.sourceName());
        if (updateName) {
            plugin.setName(downloadedPlugin.sourceName());
        } else {
            plugin.setName(StringUtils.defaultIfBlank(plugin.getName(), downloadedPlugin.sourceName()));
        }
        plugin.setExternalId(StringUtils.defaultIfBlank(downloadedPlugin.externalId(), entryExternalId));
        plugin.setContent(downloadedPlugin.body());
        plugin.setVersion(downloadedPlugin.version());
    }
}
