package cn.har01d.alist_tvbox.service.sync;

import cn.har01d.alist_tvbox.dto.sync.*;
import cn.har01d.alist_tvbox.entity.*;
import cn.har01d.alist_tvbox.exception.VersionMismatchException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
public class SyncService {
    private final SettingRepository settingRepository;
    private final SiteRepository siteRepository;
    private final ShareRepository shareRepository;
    private final AccountRepository accountRepository;
    private final DriverAccountRepository driverAccountRepository;
    private final PikPakAccountRepository pikPakAccountRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PluginRepository pluginRepository;
    private final PluginFilterRepository pluginFilterRepository;
    private final RemoteClient remoteClient;
    private final ObjectMapper objectMapper;

    // Setting 白名单
    private static final Set<String> SETTING_WHITELIST = Set.of(
        "bilibili_cookie", "bilibili_qn", "bilibili_dash", "bilibili_heartbeat", "bilibili_searchable",
        "tg_search", "tg_search_api_key", "tg_drivers", "tgDriverOrder", "tg_timeout", "tg_sort_field",
        "pan_sou_url", "pan_sou_source", "pan_sou_channels", "pan_sou_username", "pan_sou_password",
        "pan_sou_link_check_enabled", "pan_sou_link_check_max_count", "panSouPlugins",
        "search_excluded_paths", "search_index_source",
        "merge_site_source", "mix_site_source", "replace_ali_token", "clean_invalid_shares",
        "temp_share_expiration", "validateSharesInterval",
        "video_cover", "use_quark_tv", "plugin_run_mode",
        "open_token_url", "open_api_client_id", "open_api_client_secret",
        "local_proxy_config", "offline_download_config", "global_subscription_override",
        "user_agent", "tmdb_api_key", "debug_log"
    );

    public SyncService(SettingRepository settingRepository,
                      SiteRepository siteRepository,
                      ShareRepository shareRepository,
                      AccountRepository accountRepository,
                      DriverAccountRepository driverAccountRepository,
                      PikPakAccountRepository pikPakAccountRepository,
                      SubscriptionRepository subscriptionRepository,
                      PluginRepository pluginRepository,
                      PluginFilterRepository pluginFilterRepository,
                      RemoteClient remoteClient,
                      ObjectMapper objectMapper) {
        this.settingRepository = settingRepository;
        this.siteRepository = siteRepository;
        this.shareRepository = shareRepository;
        this.accountRepository = accountRepository;
        this.driverAccountRepository = driverAccountRepository;
        this.pikPakAccountRepository = pikPakAccountRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.pluginRepository = pluginRepository;
        this.pluginFilterRepository = pluginFilterRepository;
        this.remoteClient = remoteClient;
        this.objectMapper = objectMapper;
    }

    public SyncData exportData(List<String> modules) {
        SyncData data = new SyncData();

        // 设置版本号
        String appVersion = settingRepository.findById("app_version")
                .map(Setting::getValue)
                .orElse("unknown");
        data.setAppVersion(appVersion);

        // 按模块导出数据
        for (String module : modules) {
            switch (module) {
                case "sites":
                    data.setSites(siteRepository.findAll());
                    break;
                case "shares":
                    data.setShares(shareRepository.findAll());
                    break;
                case "accounts":
                    data.setAccounts(accountRepository.findAll());
                    break;
                case "driverAccounts":
                    data.setDriverAccounts(driverAccountRepository.findAll());
                    break;
                case "pikpakAccounts":
                    data.setPikpakAccounts(pikPakAccountRepository.findAll());
                    break;
                case "subscriptions":
                    data.setSubscriptions(subscriptionRepository.findAll());
                    data.setPlugins(pluginRepository.findAll());
                    data.setPluginFilters(pluginFilterRepository.findAll());
                    break;
                case "settings":
                    data.setSettings(exportSettings());
                    break;
                default:
                    log.warn("未知模块: {}", module);
            }
        }

        log.info("导出数据完成，模块: {}", modules);
        return data;
    }

    private Map<String, String> exportSettings() {
        Map<String, String> settings = new HashMap<>();
        for (String key : SETTING_WHITELIST) {
            settingRepository.findById(key).ifPresent(setting -> {
                settings.put(key, setting.getValue());
            });
        }
        return settings;
    }

    public RemoteClient getRemoteClient() {
        return remoteClient;
    }

    @SuppressWarnings("unchecked")
    public Map<String, SyncResult> importData(SyncData data, MergeStrategy strategy, boolean force) {
        // 版本校验
        String localVersion = settingRepository.findById("app_version")
                .map(Setting::getValue)
                .orElse("unknown");
        if (!localVersion.equals(data.getAppVersion()) && !force) {
            throw new VersionMismatchException(localVersion, data.getAppVersion());
        }

        Map<String, SyncResult> results = new HashMap<>();

        // 按顺序导入各模块（独立事务）
        if (data.getModules().containsKey("settings")) {
            results.put("settings", importSettings(
                (Map<String, String>) data.getModules().get("settings"), strategy));
        }
        if (data.getModules().containsKey("sites")) {
            results.put("sites", importSites(
                (List<Site>) data.getModules().get("sites"), strategy));
        }
        if (data.getModules().containsKey("accounts")) {
            results.put("accounts", importAccounts(
                (List<Account>) data.getModules().get("accounts"), strategy));
        }
        if (data.getModules().containsKey("driverAccounts")) {
            results.put("driverAccounts", importDriverAccounts(
                (List<DriverAccount>) data.getModules().get("driverAccounts"), strategy));
        }
        if (data.getModules().containsKey("pikpakAccounts")) {
            results.put("pikpakAccounts", importPikPakAccounts(
                (List<PikPakAccount>) data.getModules().get("pikpakAccounts"), strategy));
        }
        if (data.getModules().containsKey("shares")) {
            results.put("shares", importShares(
                (List<Share>) data.getModules().get("shares"), strategy));
        }
        if (data.getModules().containsKey("plugins")) {
            results.put("plugins", importPlugins(
                (List<Plugin>) data.getModules().get("plugins"), strategy));
        }
        if (data.getModules().containsKey("pluginFilters")) {
            results.put("pluginFilters", importPluginFilters(
                (List<PluginFilter>) data.getModules().get("pluginFilters"), strategy));
        }
        if (data.getModules().containsKey("subscriptions")) {
            results.put("subscriptions", importSubscriptions(
                (List<Subscription>) data.getModules().get("subscriptions"), strategy));
        }

        return results;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SyncResult importSettings(Map<String, String> settings, MergeStrategy strategy) {
        SyncResult result = new SyncResult();

        try {
            if (strategy == MergeStrategy.OVERWRITE) {
                // 覆盖模式：删除白名单内的 Setting
                for (String key : SETTING_WHITELIST) {
                    settingRepository.deleteById(key);
                }
            }

            // 插入或更新
            for (Map.Entry<String, String> entry : settings.entrySet()) {
                if (!SETTING_WHITELIST.contains(entry.getKey())) {
                    continue;  // 跳过不在白名单的
                }

                Optional<Setting> existing = settingRepository.findById(entry.getKey());
                if (existing.isPresent()) {
                    existing.get().setValue(entry.getValue());
                    settingRepository.save(existing.get());
                    result.setUpdated(result.getUpdated() + 1);
                } else {
                    settingRepository.save(new Setting(entry.getKey(), entry.getValue()));
                    result.setImported(result.getImported() + 1);
                }
            }

            log.info("导入 Settings 完成: 新增 {}, 更新 {}", result.getImported(), result.getUpdated());
        } catch (Exception e) {
            log.error("导入 Settings 失败", e);
            result.setFailed(1);
            result.getErrors().add("导入失败: " + e.getMessage());
        }

        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SyncResult importSites(List<Site> sites, MergeStrategy strategy) {
        SyncResult result = new SyncResult();

        try {
            if (strategy == MergeStrategy.OVERWRITE) {
                siteRepository.deleteAll();
            }

            for (Site remote : sites) {
                try {
                    Optional<Site> existing = siteRepository.findByUrl(remote.getUrl());
                    if (existing.isPresent()) {
                        // 更新：保留本地 ID
                        Site local = existing.get();
                        local.setName(remote.getName());
                        local.setPassword(remote.getPassword());
                        local.setToken(remote.getToken());
                        local.setIndexFile(remote.getIndexFile());
                        local.setFolder(remote.getFolder());
                        local.setSearchable(remote.isSearchable());
                        local.setDisabled(remote.isDisabled());
                        local.setXiaoya(remote.isXiaoya());
                        local.setOrder(remote.getOrder());
                        local.setVersion(remote.getVersion());
                        siteRepository.save(local);
                        result.setUpdated(result.getUpdated() + 1);
                    } else {
                        // 插入：ID 自动生成
                        remote.setId(null);
                        siteRepository.save(remote);
                        result.setImported(result.getImported() + 1);
                    }
                } catch (Exception e) {
                    log.error("导入 Site 失败: {}", remote.getUrl(), e);
                    result.setFailed(result.getFailed() + 1);
                    result.getErrors().add("Site " + remote.getUrl() + " 导入失败: " + e.getMessage());
                }
            }

            log.info("导入 Sites 完成: 新增 {}, 更新 {}, 失败 {}",
                    result.getImported(), result.getUpdated(), result.getFailed());
        } catch (Exception e) {
            log.error("导入 Sites 失败", e);
            result.setFailed(sites.size());
            result.getErrors().add("批量导入失败: " + e.getMessage());
        }

        return result;
    }

    // 占位方法，将在后续任务实现
    private SyncResult importAccounts(List<Account> accounts, MergeStrategy strategy) {
        return new SyncResult();
    }

    private SyncResult importDriverAccounts(List<DriverAccount> accounts, MergeStrategy strategy) {
        return new SyncResult();
    }

    private SyncResult importPikPakAccounts(List<PikPakAccount> accounts, MergeStrategy strategy) {
        return new SyncResult();
    }

    private SyncResult importShares(List<Share> shares, MergeStrategy strategy) {
        return new SyncResult();
    }

    private SyncResult importPlugins(List<Plugin> plugins, MergeStrategy strategy) {
        return new SyncResult();
    }

    private SyncResult importPluginFilters(List<PluginFilter> filters, MergeStrategy strategy) {
        return new SyncResult();
    }

    private SyncResult importSubscriptions(List<Subscription> subscriptions, MergeStrategy strategy) {
        return new SyncResult();
    }
}
