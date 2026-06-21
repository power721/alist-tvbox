package cn.har01d.alist_tvbox.service.backup;

import cn.har01d.alist_tvbox.entity.*;
import cn.har01d.alist_tvbox.service.backup.BackupModuleHandler.IdStrategy;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.EntityManager;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static registration of every backup module: repository binding, export/restore order, id
 * strategy and merge business key. The list order is the dependency-safe restore order; overwrite
 * deletion uses the reverse.
 */
@Component
public class BackupModuleRegistry {

    private final List<BackupModuleHandler<?>> handlers = new ArrayList<>();
    /**
     * The large douban modules (movie/meta/alias). They live in {@link #handlers} too, so restore and
     * id_generator rebuild still see them, but {@link #exportHandlers(boolean)} omits them unless the
     * caller opts in (migration only) — keeps daily JSON backups small. Restore is manifest-driven, so
     * a base-only backup never touches existing douban rows.
     */
    private final List<BackupModuleHandler<?>> doubanHandlers = new ArrayList<>();
    private final EntityManager entityManager;

    public BackupModuleRegistry(EntityManager entityManager,
                                SettingRepository settingRepository,
                                UserRepository userRepository,
                                TmdbRepository tmdbRepository,
                                TmdbMetaRepository tmdbMetaRepository,
                                SiteRepository siteRepository,
                                ShareRepository shareRepository,
                                AccountRepository accountRepository,
                                DriverAccountRepository driverAccountRepository,
                                PanAccountRepository panAccountRepository,
                                PikPakAccountRepository pikPakAccountRepository,
                                SubscriptionRepository subscriptionRepository,
                                PluginRepository pluginRepository,
                                PluginFilterRepository pluginFilterRepository,
                                JellyfinRepository jellyfinRepository,
                                EmbyRepository embyRepository,
                                FeiniuRepository feiniuRepository,
                                NavigationRepository navigationRepository,
                                TelegramChannelRepository telegramChannelRepository,
                                IndexTemplateRepository indexTemplateRepository,
                                ConfigFileRepository configFileRepository,
                                DeviceRepository deviceRepository,
                                TenantRepository tenantRepository,
                                AListAliasRepository aListAliasRepository,
                                OfflineDownloadTaskRepository offlineDownloadTaskRepository,
                                PlayUrlRepository playUrlRepository,
                                HistoryRepository historyRepository,
                                TaskRepository taskRepository,
                                MovieRepository movieRepository,
                                MetaRepository metaRepository,
                                AliasRepository aliasRepository) {
        this.entityManager = entityManager;
        ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        add("settings", "setting", Setting.class, settingRepository, mapper, IdStrategy.ASSIGNED, "name");
        add("users", "x_user", User.class, userRepository, mapper, IdStrategy.IDENTITY, "username");
        add("tmdb", "tmdb", Tmdb.class, tmdbRepository, mapper, IdStrategy.TABLE, "id");
        add("tmdbMeta", "tmdb_meta", TmdbMeta.class, tmdbMetaRepository, mapper, IdStrategy.TABLE, "id");
        add("sites", "site", Site.class, siteRepository, mapper, IdStrategy.TABLE, "id");
        add("shares", "share", Share.class, shareRepository, mapper, IdStrategy.ASSIGNED, "path");
        add("accounts", "account", Account.class, accountRepository, mapper, IdStrategy.IDENTITY, "id");
        add("driverAccounts", "driver_account", DriverAccount.class, driverAccountRepository, mapper, IdStrategy.TABLE, "id");
        add("panAccounts", "pan_account", PanAccount.class, panAccountRepository, mapper, IdStrategy.TABLE, "id");
        add("pikpakAccounts", "pik_pak_account", PikPakAccount.class, pikPakAccountRepository, mapper, IdStrategy.TABLE, "id");
        add("subscriptions", "subscription", Subscription.class, subscriptionRepository, mapper, IdStrategy.TABLE, "id");
        add("plugins", "plugin", Plugin.class, pluginRepository, mapper, IdStrategy.TABLE, "id", Set.of("content"));
        add("pluginFilters", "plugin_filter", PluginFilter.class, pluginFilterRepository, mapper, IdStrategy.TABLE, "id", Set.of("content"));
        add("jellyfins", "jellyfin", Jellyfin.class, jellyfinRepository, mapper, IdStrategy.TABLE, "id");
        add("embys", "emby", Emby.class, embyRepository, mapper, IdStrategy.TABLE, "id");
        add("feinius", "feiniu", Feiniu.class, feiniuRepository, mapper, IdStrategy.TABLE, "id");
        add("navigations", "navigation", Navigation.class, navigationRepository, mapper, IdStrategy.TABLE, "id");
        add("telegramChannels", "telegram_channel", TelegramChannel.class, telegramChannelRepository, mapper, IdStrategy.ASSIGNED, "id");
        add("indexTemplates", "index_template", IndexTemplate.class, indexTemplateRepository, mapper, IdStrategy.TABLE, "id");
        add("configFiles", "config_file", ConfigFile.class, configFileRepository, mapper, IdStrategy.TABLE, "path");
        add("devices", "device", Device.class, deviceRepository, mapper, IdStrategy.TABLE, "id");
        add("tenants", "tenant", Tenant.class, tenantRepository, mapper, IdStrategy.TABLE, "id");
        add("alistAliases", "alist_alias", AListAlias.class, aListAliasRepository, mapper, IdStrategy.ASSIGNED, "path");
        add("offlineDownloadTasks", "offline_download_task", OfflineDownloadTask.class, offlineDownloadTaskRepository, mapper, IdStrategy.TABLE, "id");
        add("playUrls", "play_url", PlayUrl.class, playUrlRepository, mapper, IdStrategy.TABLE, "id");
        add("histories", "history", History.class, historyRepository, mapper, IdStrategy.TABLE, "id");
        add("tasks", "task", Task.class, taskRepository, mapper, IdStrategy.TABLE, "id");

        // Douban movie data is large and only carried by migration exports (exportHandlers(true));
        // daily JSON backups skip it. Restore order matters: movie before alias/meta (FK movie_id),
        // and tmdb (registered above) before meta (FK tmdb_id).
        addDouban("movies", "movie", Movie.class, movieRepository, mapper, IdStrategy.ASSIGNED, "id");
        addDouban("aliases", "alias", Alias.class, aliasRepository, mapper, IdStrategy.ASSIGNED, "name");
        addDouban("metas", "meta", Meta.class, metaRepository, mapper, IdStrategy.TABLE, "id");
    }

    private <T> void addDouban(String moduleName, String tableName, Class<T> entityClass,
                               JpaRepository<T, ?> repository, ObjectMapper mapper,
                               IdStrategy idStrategy, String keyField) {
        BackupModuleHandler<T> handler = new BackupModuleHandler<>(moduleName, tableName, entityClass, repository, mapper, entityManager, idStrategy, keyField);
        handlers.add(handler);
        doubanHandlers.add(handler);
    }

    private <T> void add(String moduleName, String tableName, Class<T> entityClass,
                         JpaRepository<T, ?> repository, ObjectMapper mapper,
                         IdStrategy idStrategy, String keyField) {
        add(moduleName, tableName, entityClass, repository, mapper, idStrategy, keyField, Set.of());
    }

    private <T> void add(String moduleName, String tableName, Class<T> entityClass,
                         JpaRepository<T, ?> repository, ObjectMapper mapper,
                         IdStrategy idStrategy, String keyField, Set<String> excludedIgnoredFields) {
        handlers.add(new BackupModuleHandler<>(moduleName, tableName, entityClass, repository, mapper, entityManager, idStrategy, keyField, excludedIgnoredFields));
    }

    public List<BackupModuleHandler<?>> orderedHandlers() {
        return handlers;
    }

    /**
     * Handlers to export. Douban modules (movie/meta/alias) are included only when
     * {@code includeDouban} is true — used by the migration path so the target DB receives the douban
     * data, while daily backups stay small.
     */
    public List<BackupModuleHandler<?>> exportHandlers(boolean includeDouban) {
        if (includeDouban || doubanHandlers.isEmpty()) {
            return handlers;
        }
        List<BackupModuleHandler<?>> base = new ArrayList<>(handlers.size() - doubanHandlers.size());
        for (BackupModuleHandler<?> handler : handlers) {
            if (!doubanHandlers.contains(handler)) {
                base.add(handler);
            }
        }
        return base;
    }

    public Map<String, BackupModuleHandler<?>> handlerMap() {
        Map<String, BackupModuleHandler<?>> map = new LinkedHashMap<>();
        for (BackupModuleHandler<?> handler : handlers) {
            map.put(handler.moduleName(), handler);
        }
        return map;
    }
}
