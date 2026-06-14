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
}
