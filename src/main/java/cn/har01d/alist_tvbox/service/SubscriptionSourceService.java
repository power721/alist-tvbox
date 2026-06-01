package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.EmbyRepository;
import cn.har01d.alist_tvbox.entity.FeiniuRepository;
import cn.har01d.alist_tvbox.entity.JellyfinRepository;
import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.PluginRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SubscriptionSourceService {
    private static final String BUILTIN_SETTINGS_KEY = "builtin_subscription_sources";
    private static final String SORT_ORDER_MIGRATED_KEY = "sub_source_order_migrated";

    private final AppProperties appProperties;
    private final PluginRepository pluginRepository;
    private final SettingRepository settingRepository;
    private final SiteRepository siteRepository;
    private final EmbyRepository embyRepository;
    private final FeiniuRepository feiniuRepository;
    private final JellyfinRepository jellyfinRepository;
    private final ObjectMapper objectMapper;

    public SubscriptionSourceService(AppProperties appProperties,
                                     PluginRepository pluginRepository,
                                     SettingRepository settingRepository,
                                     SiteRepository siteRepository,
                                     EmbyRepository embyRepository,
                                     FeiniuRepository feiniuRepository,
                                     JellyfinRepository jellyfinRepository,
                                     ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        this.pluginRepository = pluginRepository;
        this.settingRepository = settingRepository;
        this.siteRepository = siteRepository;
        this.embyRepository = embyRepository;
        this.feiniuRepository = feiniuRepository;
        this.jellyfinRepository = jellyfinRepository;
        this.objectMapper = objectMapper;
    }

    public record ManagedSource(
            String id,
            boolean builtin,
            Integer pluginId,
            String key,
            String name,
            String sourceName,
            String url,
            boolean enabled,
            int sortOrder,
            Integer version,
            String extend,
            String lastCheckedAt,
            String lastError,
            boolean deletable,
            boolean refreshable,
            boolean extendable
    ) {
    }

    public record ManagedSourceUpdate(String name, boolean enabled, String extend, Integer sortOrder) {
    }

    public record SubscriptionSourceRef(String id, boolean builtin, String siteKey, String name, Plugin plugin, int sortOrder) {
    }

    private record BuiltinDefinition(String siteKey, String defaultName, int defaultSortOrder) {
    }

    private record ManagedSourceHolder(ManagedSource source, Plugin plugin) {
    }

    private static class BuiltinSourceState {
        private String name;
        private Boolean enabled;
        private Integer sortOrder;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public Integer getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(Integer sortOrder) {
            this.sortOrder = sortOrder;
        }
    }

    @PostConstruct
    void migratePluginSortOrders() {
        if (settingRepository.existsByName(SORT_ORDER_MIGRATED_KEY)) {
            return;
        }
        // Migrate builtin source sort orders to 1000-based scheme
        Map<String, BuiltinSourceState> settings = readBuiltinSettings();
        List<BuiltinDefinition> definitions = builtinDefinitions();
        int builtinOrder = 1000;
        for (BuiltinDefinition definition : definitions) {
            BuiltinSourceState state = settings.computeIfAbsent(definition.siteKey(), ignored -> new BuiltinSourceState());
            state.setSortOrder(builtinOrder);
            builtinOrder += 10;
        }
        saveBuiltinSettings(settings);

        // Migrate plugin sort orders
        List<Plugin> plugins = pluginRepository.findAllByOrderBySortOrderAscIdAsc();
        if (!plugins.isEmpty()) {
            int order = builtinOrder;
            for (Plugin plugin : plugins) {
                plugin.setSortOrder(order);
                order += 10;
            }
            pluginRepository.saveAll(plugins);
        }
        settingRepository.save(new Setting(SORT_ORDER_MIGRATED_KEY, "true"));
    }

    public List<ManagedSource> findAll() {
        return buildManagedSources().stream()
                .map(ManagedSourceHolder::source)
                .toList();
    }

    public List<SubscriptionSourceRef> findEnabledSources() {
        return buildManagedSources().stream()
                .filter(item -> item.source().enabled())
                .map(item -> new SubscriptionSourceRef(
                        item.source().id(),
                        item.source().builtin(),
                        item.source().key(),
                        item.source().name(),
                        item.plugin(),
                        item.source().sortOrder()
                ))
                .toList();
    }

    public ManagedSource update(String id, ManagedSourceUpdate update) {
        if (id.startsWith("builtin-")) {
            String siteKey = id.substring("builtin-".length());
            BuiltinDefinition definition = definitionMap().get(siteKey);
            if (definition == null) {
                throw new NotFoundException("内置源不存在");
            }
            Map<String, BuiltinSourceState> settings = readBuiltinSettings();
            BuiltinSourceState state = settings.computeIfAbsent(siteKey, ignored -> new BuiltinSourceState());
            state.setName(StringUtils.trimToNull(update.name()));
            state.setEnabled(update.enabled());
            if (update.sortOrder() != null && update.sortOrder() > 0) {
                state.setSortOrder(update.sortOrder());
            } else if (state.getSortOrder() == null) {
                state.setSortOrder(definition.defaultSortOrder());
            }
            saveBuiltinSettings(settings);
            return buildBuiltinSource(definition, state).source();
        }

        Integer pluginId = parsePluginId(id);
        Plugin plugin = pluginRepository.findById(pluginId).orElseThrow(NotFoundException::new);
        plugin.setName(StringUtils.defaultIfBlank(StringUtils.trimToNull(update.name()), plugin.getSourceName()));
        plugin.setEnabled(update.enabled());
        plugin.setExtend(StringUtils.trimToEmpty(update.extend()));
        if (update.sortOrder() != null && update.sortOrder() > 0) {
            plugin.setSortOrder(update.sortOrder());
        }
        pluginRepository.save(plugin);
        return buildPluginSource(plugin).source();
    }

    public void reorder(List<String> ids) {
        Map<String, BuiltinSourceState> settings = readBuiltinSettings();
        List<Plugin> plugins = new ArrayList<>();
        int order = 1000;
        for (String id : ids) {
            if (id.startsWith("builtin-")) {
                String siteKey = id.substring("builtin-".length());
                BuiltinSourceState state = settings.computeIfAbsent(siteKey, ignored -> new BuiltinSourceState());
                state.setSortOrder(order);
                if (state.getEnabled() == null) {
                    state.setEnabled(true);
                }
            } else {
                Plugin plugin = pluginRepository.findById(parsePluginId(id)).orElseThrow(NotFoundException::new);
                plugin.setSortOrder(order);
                plugins.add(plugin);
            }
            order += 10;
        }
        if (!plugins.isEmpty()) {
            pluginRepository.saveAll(plugins);
        }
        saveBuiltinSettings(settings);
    }

    public void normalizeSortOrders() {
        reorder(findAll().stream().map(ManagedSource::id).toList());
    }

    public int nextSortOrder() {
        int count = (int) findAll().stream().count();
        return 1000 + 10 * count;
    }

    private List<ManagedSourceHolder> buildManagedSources() {
        Map<String, BuiltinSourceState> settings = readBuiltinSettings();
        List<ManagedSourceHolder> sources = new ArrayList<>();
        for (BuiltinDefinition definition : builtinDefinitions()) {
            sources.add(buildBuiltinSource(definition, settings.get(definition.siteKey())));
        }
        for (Plugin plugin : pluginRepository.findAllByOrderBySortOrderAscIdAsc()) {
            sources.add(buildPluginSource(plugin));
        }
        sources.sort(Comparator.comparingInt((ManagedSourceHolder item) -> item.source().sortOrder())
                .thenComparing(item -> item.source().id()));
        return sources;
    }

    private ManagedSourceHolder buildBuiltinSource(BuiltinDefinition definition, BuiltinSourceState state) {
        ManagedSource source = new ManagedSource(
                "builtin-" + definition.siteKey(),
                true,
                null,
                definition.siteKey(),
                StringUtils.defaultIfBlank(state == null ? null : state.getName(), definition.defaultName()),
                definition.defaultName(),
                "",
                state == null || state.getEnabled() == null || state.getEnabled(),
                state == null || state.getSortOrder() == null ? definition.defaultSortOrder() : state.getSortOrder(),
                null,
                "",
                "",
                "",
                false,
                false,
                false
        );
        return new ManagedSourceHolder(source, null);
    }

    private ManagedSourceHolder buildPluginSource(Plugin plugin) {
        ManagedSource source = new ManagedSource(
                "plugin-" + plugin.getId(),
                false,
                plugin.getId(),
                plugin.getName(),
                plugin.getName(),
                plugin.getSourceName(),
                plugin.getUrl(),
                plugin.isEnabled(),
                plugin.getSortOrder(),
                plugin.getVersion(),
                StringUtils.defaultString(plugin.getExtend()),
                plugin.getLastCheckedAt() == null ? "" : plugin.getLastCheckedAt().toString(),
                StringUtils.defaultString(plugin.getLastError()),
                true,
                true,
                true
        );
        return new ManagedSourceHolder(source, plugin);
    }

    private Map<String, BuiltinDefinition> definitionMap() {
        Map<String, BuiltinDefinition> map = new HashMap<>();
        for (BuiltinDefinition definition : builtinDefinitions()) {
            map.put(definition.siteKey(), definition);
        }
        return map;
    }

    private List<BuiltinDefinition> builtinDefinitions() {
        List<BuiltinDefinition> definitions = new ArrayList<>();
        int order = 1000;
        Site xiaoya = siteRepository.findById(1).orElse(null);
        if (xiaoya != null) {
            definitions.add(new BuiltinDefinition("csp_XiaoYa", xiaoya.getName(), order));
            order += 10;
        }
        definitions.add(new BuiltinDefinition("csp_AList", "AList", order));
        order += 10;
        definitions.add(new BuiltinDefinition("csp_BiliBili", "BiliBili", order));
        order += 10;
        if (embyRepository.count() > 0) {
            definitions.add(new BuiltinDefinition("csp_Emby", "Emby", order));
            order += 10;
        }
        if (jellyfinRepository.count() > 0) {
            definitions.add(new BuiltinDefinition("csp_Jellyfin", "Jellyfin", order));
            order += 10;
        }
        if (feiniuRepository.count() > 0) {
            definitions.add(new BuiltinDefinition("csp_FeiNiu", "飞牛影视", order));
            order += 10;
        }
        definitions.add(new BuiltinDefinition("csp_Live", "网络直播", order));
        order += 10;
        definitions.add(new BuiltinDefinition("csp_TgDouBan", "电报豆瓣", order));
        order += 10;
        if (appProperties.isTgLogin() || StringUtils.isNotBlank(appProperties.getTgSearch())) {
            definitions.add(new BuiltinDefinition("csp_TgSearch", "电报搜索", order));
            order += 10;
        }
        definitions.add(new BuiltinDefinition("csp_TgWeb", "电报网页", order));
        order += 10;
        if (StringUtils.isNotBlank(appProperties.getPanSouUrl())) {
            definitions.add(new BuiltinDefinition("csp_FishPanSou", "鱼佬盘搜", order));
        }
        definitions.add(new BuiltinDefinition("csp_Push", "推送", order));
        return definitions;
    }

    private Integer parsePluginId(String id) {
        return Integer.parseInt(id.substring("plugin-".length()));
    }

    private Map<String, BuiltinSourceState> readBuiltinSettings() {
        return settingRepository.findById(BUILTIN_SETTINGS_KEY)
                .map(Setting::getValue)
                .filter(StringUtils::isNotBlank)
                .map(this::parseBuiltinSettings)
                .orElseGet(HashMap::new);
    }

    private Map<String, BuiltinSourceState> parseBuiltinSettings(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private void saveBuiltinSettings(Map<String, BuiltinSourceState> settings) {
        try {
            settingRepository.save(new Setting(BUILTIN_SETTINGS_KEY, objectMapper.writeValueAsString(settings)));
        } catch (Exception e) {
            throw new IllegalStateException("保存内置源设置失败", e);
        }
    }
}
