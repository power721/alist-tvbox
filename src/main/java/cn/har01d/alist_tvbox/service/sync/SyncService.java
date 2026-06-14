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
    private final cn.har01d.alist_tvbox.service.UserService userService;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

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
                      ObjectMapper objectMapper,
                      cn.har01d.alist_tvbox.service.UserService userService,
                      org.springframework.security.crypto.password.PasswordEncoder passwordEncoder) {
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
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
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

    /**
     * 验证用户名和密码，但不创建会话
     */
    public boolean validateCredentials(String username, String password) {
        try {
            User user = userService.findByUsername(username);
            if (user == null) {
                log.warn("用户不存在: {}", username);
                return false;
            }
            boolean matches = passwordEncoder.matches(password, user.getPassword());
            log.info("验证用户凭据: {} - {}", username, matches ? "成功" : "失败");
            return matches;
        } catch (Exception e) {
            log.error("验证用户凭据失败: {}", username, e);
            return false;
        }
    }

    /**
     * 获取本地应用版本号
     */
    public String getLocalVersion() {
        return settingRepository.findById("app_version")
                .map(Setting::getValue)
                .orElse("unknown");
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
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SyncResult importShares(List<Share> shares, MergeStrategy strategy) {
        SyncResult result = new SyncResult();

        try {
            if (strategy == MergeStrategy.OVERWRITE) {
                shareRepository.deleteAll();
            }

            for (Share remote : shares) {
                try {
                    Optional<Share> existing = shareRepository.findByTypeAndShareId(
                        remote.getType(), remote.getShareId());

                    if (existing.isPresent()) {
                        Share local = existing.get();
                        local.setPath(remote.getPath());
                        local.setFolderId(remote.getFolderId());
                        local.setPassword(remote.getPassword());
                        local.setCookie(remote.getCookie());
                        local.setTemp(remote.isTemp());
                        shareRepository.save(local);
                        result.setUpdated(result.getUpdated() + 1);
                    } else {
                        // Share 没有 @GeneratedValue，需要手动分配 ID
                        Integer maxId = shareRepository.findAll().stream()
                                .map(Share::getId)
                                .max(Integer::compareTo)
                                .orElse(0);
                        remote.setId(maxId + 1);
                        shareRepository.save(remote);
                        result.setImported(result.getImported() + 1);
                    }
                } catch (Exception e) {
                    log.error("导入 Share 失败: {}:{}", remote.getType(), remote.getShareId(), e);
                    result.setFailed(result.getFailed() + 1);
                    result.getErrors().add(remote.getType() + ":" + remote.getShareId() + " 导入失败");
                }
            }

            log.info("导入 Shares 完成: 新增 {}, 更新 {}, 失败 {}",
                    result.getImported(), result.getUpdated(), result.getFailed());
        } catch (Exception e) {
            log.error("导入 Shares 失败", e);
            result.setFailed(shares.size());
            result.getErrors().add("批量导入失败: " + e.getMessage());
        }

        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SyncResult importAccounts(List<Account> accounts, MergeStrategy strategy) {
        SyncResult result = new SyncResult();

        try {
            if (strategy == MergeStrategy.OVERWRITE) {
                accountRepository.deleteAll();
            }

            for (Account remote : accounts) {
                try {
                    Optional<Account> existing = accountRepository.findByNickname(remote.getNickname());

                    if (existing.isPresent()) {
                        Account local = existing.get();
                        local.setRefreshToken(remote.getRefreshToken());
                        local.setRefreshTokenTime(remote.getRefreshTokenTime());
                        local.setAccessToken(remote.getAccessToken());
                        local.setAccessTokenTime(remote.getAccessTokenTime());
                        local.setOpenToken(remote.getOpenToken());
                        local.setOpenTokenTime(remote.getOpenTokenTime());
                        local.setOpenAccessToken(remote.getOpenAccessToken());
                        local.setOpenAccessTokenTime(remote.getOpenAccessTokenTime());
                        local.setAutoCheckin(remote.isAutoCheckin());
                        local.setShowMyAli(remote.isShowMyAli());
                        local.setMaster(remote.isMaster());
                        local.setClean(remote.isClean());
                        local.setUseProxy(remote.isUseProxy());
                        local.setConcurrency(remote.getConcurrency());
                        local.setChunkSize(remote.getChunkSize());
                        accountRepository.save(local);
                        result.setUpdated(result.getUpdated() + 1);
                    } else {
                        remote.setId(null);
                        accountRepository.save(remote);
                        result.setImported(result.getImported() + 1);
                    }
                } catch (Exception e) {
                    log.error("导入 Account 失败: {}", remote.getNickname(), e);
                    result.setFailed(result.getFailed() + 1);
                    result.getErrors().add("Account " + remote.getNickname() + " 导入失败");
                }
            }

            log.info("导入 Accounts 完成: 新增 {}, 更新 {}, 失败 {}",
                    result.getImported(), result.getUpdated(), result.getFailed());
        } catch (Exception e) {
            log.error("导入 Accounts 失败", e);
            result.setFailed(accounts.size());
            result.getErrors().add("批量导入失败: " + e.getMessage());
        }

        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SyncResult importDriverAccounts(List<DriverAccount> accounts, MergeStrategy strategy) {
        SyncResult result = new SyncResult();

        try {
            if (strategy == MergeStrategy.OVERWRITE) {
                driverAccountRepository.deleteAll();
            }

            for (DriverAccount remote : accounts) {
                try {
                    DriverAccount existing = null;

                    // 优先用 username 查找
                    if (StringUtils.isNotBlank(remote.getUsername())) {
                        existing = driverAccountRepository.findByTypeAndUsername(
                            remote.getType(), remote.getUsername()).orElse(null);
                    }

                    // 回退到 name
                    if (existing == null && StringUtils.isNotBlank(remote.getName())) {
                        existing = driverAccountRepository.findByTypeAndName(
                            remote.getType(), remote.getName()).orElse(null);
                    }

                    if (existing != null) {
                        existing.setName(remote.getName());
                        existing.setCookie(remote.getCookie());
                        existing.setToken(remote.getToken());
                        existing.setAddition(remote.getAddition());
                        existing.setUsername(remote.getUsername());
                        existing.setPassword(remote.getPassword());
                        existing.setSafePassword(remote.getSafePassword());
                        existing.setFolder(remote.getFolder());
                        existing.setConcurrency(remote.getConcurrency());
                        existing.setDisabled(remote.isDisabled());
                        existing.setUseProxy(remote.isUseProxy());
                        existing.setMaster(remote.isMaster());
                        driverAccountRepository.save(existing);
                        result.setUpdated(result.getUpdated() + 1);
                    } else {
                        remote.setId(null);
                        driverAccountRepository.save(remote);
                        result.setImported(result.getImported() + 1);
                    }
                } catch (Exception e) {
                    log.error("导入 DriverAccount 失败: {} {}", remote.getType(), remote.getName(), e);
                    result.setFailed(result.getFailed() + 1);
                    result.getErrors().add("DriverAccount " + remote.getType() + " " + remote.getName() + " 导入失败");
                }
            }

            log.info("导入 DriverAccounts 完成: 新增 {}, 更新 {}, 失败 {}",
                    result.getImported(), result.getUpdated(), result.getFailed());
        } catch (Exception e) {
            log.error("导入 DriverAccounts 失败", e);
            result.setFailed(accounts.size());
            result.getErrors().add("批量导入失败: " + e.getMessage());
        }

        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SyncResult importPikPakAccounts(List<PikPakAccount> accounts, MergeStrategy strategy) {
        SyncResult result = new SyncResult();

        try {
            if (strategy == MergeStrategy.OVERWRITE) {
                pikPakAccountRepository.deleteAll();
            }

            for (PikPakAccount remote : accounts) {
                try {
                    Optional<PikPakAccount> existing = Optional.ofNullable(
                        pikPakAccountRepository.findByUsername(remote.getUsername()));

                    if (existing.isPresent()) {
                        PikPakAccount local = existing.get();
                        local.setNickname(remote.getNickname());
                        local.setPlatform(remote.getPlatform());
                        local.setRefreshTokenMethod(remote.getRefreshTokenMethod());
                        local.setPassword(remote.getPassword());
                        local.setMaster(remote.isMaster());
                        pikPakAccountRepository.save(local);
                        result.setUpdated(result.getUpdated() + 1);
                    } else {
                        remote.setId(null);
                        pikPakAccountRepository.save(remote);
                        result.setImported(result.getImported() + 1);
                    }
                } catch (Exception e) {
                    log.error("导入 PikPakAccount 失败: {}", remote.getUsername(), e);
                    result.setFailed(result.getFailed() + 1);
                    result.getErrors().add("PikPakAccount " + remote.getUsername() + " 导入失败");
                }
            }

            log.info("导入 PikPakAccounts 完成: 新增 {}, 更新 {}, 失败 {}",
                    result.getImported(), result.getUpdated(), result.getFailed());
        } catch (Exception e) {
            log.error("导入 PikPakAccounts 失败", e);
            result.setFailed(accounts.size());
            result.getErrors().add("批量导入失败: " + e.getMessage());
        }

        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SyncResult importSubscriptions(List<Subscription> subscriptions, MergeStrategy strategy) {
        SyncResult result = new SyncResult();

        try {
            if (strategy == MergeStrategy.OVERWRITE) {
                subscriptionRepository.deleteAll();
            }

            for (Subscription remote : subscriptions) {
                try {
                    Optional<Subscription> existing = subscriptionRepository.findByUrl(remote.getUrl());

                    if (existing.isPresent()) {
                        Subscription local = existing.get();
                        local.setName(remote.getName());
                        local.setSid(remote.getSid());
                        local.setOverride(remote.getOverride());
                        local.setSort(remote.getSort());
                        subscriptionRepository.save(local);
                        result.setUpdated(result.getUpdated() + 1);
                    } else {
                        remote.setId(null);
                        subscriptionRepository.save(remote);
                        result.setImported(result.getImported() + 1);
                    }
                } catch (Exception e) {
                    log.error("导入 Subscription 失败: {}", remote.getUrl(), e);
                    result.setFailed(result.getFailed() + 1);
                    result.getErrors().add("Subscription " + remote.getUrl() + " 导入失败");
                }
            }

            log.info("导入 Subscriptions 完成: 新增 {}, 更新 {}, 失败 {}",
                    result.getImported(), result.getUpdated(), result.getFailed());
        } catch (Exception e) {
            log.error("导入 Subscriptions 失败", e);
            result.setFailed(subscriptions.size());
            result.getErrors().add("批量导入失败: " + e.getMessage());
        }

        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SyncResult importPlugins(List<Plugin> plugins, MergeStrategy strategy) {
        SyncResult result = new SyncResult();

        try {
            if (strategy == MergeStrategy.OVERWRITE) {
                pluginRepository.deleteAll();
            }

            for (Plugin remote : plugins) {
                try {
                    Plugin existing = null;

                    // 优先用 externalId
                    if (StringUtils.isNotBlank(remote.getExternalId())) {
                        existing = pluginRepository.findByExternalId(remote.getExternalId()).orElse(null);
                    }

                    // 回退到 url
                    if (existing == null && StringUtils.isNotBlank(remote.getUrl())) {
                        existing = pluginRepository.findByUrl(remote.getUrl()).orElse(null);
                    }

                    if (existing != null) {
                        existing.setName(remote.getName());
                        existing.setExternalId(remote.getExternalId());
                        existing.setUrl(remote.getUrl());
                        existing.setEnabled(remote.isEnabled());
                        existing.setSortOrder(remote.getSortOrder());
                        existing.setExtend(remote.getExtend());
                        existing.setSourceName(remote.getSourceName());
                        existing.setLocalPath(remote.getLocalPath());
                        // 不更新 content（有 @JsonIgnore，同步时不传输脚本内容）
                        // existing.setContent(remote.getContent());
                        existing.setVersion(remote.getVersion());
                        pluginRepository.save(existing);
                        result.setUpdated(result.getUpdated() + 1);
                    } else {
                        remote.setId(null);
                        pluginRepository.save(remote);
                        result.setImported(result.getImported() + 1);
                    }
                } catch (Exception e) {
                    log.error("导入 Plugin 失败: {}", remote.getName(), e);
                    result.setFailed(result.getFailed() + 1);
                    result.getErrors().add("Plugin " + remote.getName() + " 导入失败");
                }
            }

            log.info("导入 Plugins 完成: 新增 {}, 更新 , 失败 {}",
                    result.getImported(), result.getUpdated(), result.getFailed());
        } catch (Exception e) {
            log.error("导入 Plugins 失败", e);
            result.setFailed(plugins.size());
            result.getErrors().add("批量导入失败: " + e.getMessage());
        }

        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SyncResult importPluginFilters(List<PluginFilter> filters, MergeStrategy strategy) {
        SyncResult result = new SyncResult();

        try {
            if (strategy == MergeStrategy.OVERWRITE) {
                pluginFilterRepository.deleteAll();
            }

            for (PluginFilter remote : filters) {
                try {
                    Optional<PluginFilter> existing = pluginFilterRepository.findByUrl(remote.getUrl());

                    if (existing.isPresent()) {
                        PluginFilter local = existing.get();
                        local.setName(remote.getName());
                        local.setEnabled(remote.isEnabled());
                        local.setSortOrder(remote.getSortOrder());
                        local.setStages(remote.getStages());
                        local.setExtend(remote.getExtend());
                        local.setErrorStrategy(remote.getErrorStrategy());
                        local.setPluginScope(remote.getPluginScope());
                        local.setPluginIds(remote.getPluginIds());
                        local.setSourceName(remote.getSourceName());
                        // 不更新 content（有 @JsonIgnore，同步时不传输脚本内容）
                        // local.setContent(remote.getContent());
                        local.setVersion(remote.getVersion());
                        pluginFilterRepository.save(local);
                        result.setUpdated(result.getUpdated() + 1);
                    } else {
                        remote.setId(null);
                        pluginFilterRepository.save(remote);
                        result.setImported(result.getImported() + 1);
                    }
                } catch (Exception e) {
                    log.error("导入 PluginFilter 失败: {}", remote.getName(), e);
                    result.setFailed(result.getFailed() + 1);
                    result.getErrors().add("PluginFilter " + remote.getName() + " 导入失败");
                }
            }

            log.info("导入 PluginFilters 完成: 新增 {}, 更新 {}, 失败 {}",
                    result.getImported(), result.getUpdated(), result.getFailed());
        } catch (Exception e) {
            log.error("导入 PluginFilters 失败", e);
            result.setFailed(filters.size());
            result.getErrors().add("批量导入失败: " + e.getMessage());
        }

        return result;
    }

    public SyncResponse push(String remoteUrl, String username, String password,
                             List<String> modules, boolean force) {
        SyncResponse response = new SyncResponse();
        String token = null;

        try {
            // 登录远端（创建临时会话）
            token = remoteClient.login(remoteUrl, username, password);

            // 导出本地数据
            SyncData data = exportData(modules);

            // 推送到远端（远端使用覆盖模式）
            Map<String, SyncResult> results = remoteClient.pushToRemote(
                remoteUrl, token, data, "OVERWRITE", force);

            response.setSuccess(true);
            response.setResults(results);

            log.info("推送到远端成功: {}", remoteUrl);
        } catch (Exception e) {
            log.error("推送到远端失败: {}", remoteUrl, e);
            response.setSuccess(false);
            SyncResult errorResult = new SyncResult();
            errorResult.setFailed(1);
            errorResult.getErrors().add(e.getMessage());
            response.addResult("error", errorResult);
        } finally {
            // 同步完成后清理远端的临时会话
            if (token != null) {
                cleanupRemoteSession(remoteUrl, token);
            }
        }

        return response;
    }

    public SyncResponse pull(String remoteUrl, String username, String password,
                            List<String> modules, MergeStrategy strategy, boolean force) {
        SyncResponse response = new SyncResponse();
        String token = null;

        try {
            // 登录远端（创建临时会话）
            token = remoteClient.login(remoteUrl, username, password);

            // 从远端获取数据
            SyncData data = remoteClient.fetchRemoteData(remoteUrl, token, modules);

            // 导入到本地
            Map<String, SyncResult> results = importData(data, strategy, force);

            response.setSuccess(true);
            response.setResults(results);

            log.info("从远端拉取成功: {}", remoteUrl);
        } catch (VersionMismatchException e) {
            log.warn("版本不匹配: {} vs {}", e.getLocalVersion(), e.getRemoteVersion());
            throw e;  // 重新抛出让 Controller 处理
        } catch (Exception e) {
            log.error("从远端拉取失败: ", remoteUrl, e);
            response.setSuccess(false);
            SyncResult errorResult = new SyncResult();
            errorResult.setFailed(1);
            errorResult.getErrors().add(e.getMessage());
            response.addResult("error", errorResult);
        } finally {
            // 同步完成后清理远端的临时会话
            if (token != null) {
                cleanupRemoteSession(remoteUrl, token);
            }
        }

        return response;
    }

    /**
     * 清理远端的临时会话 token
     * 同步操作完成后主动登出，限制会话数量
     */
    private void cleanupRemoteSession(String remoteUrl, String token) {
        try {
            log.info("清理远端临时会话: {}", remoteUrl);
            remoteClient.logout(remoteUrl, token);
        } catch (Exception e) {
            // 登出失败不影响主流程，只记录警告
            log.warn("清理远端会话失败（不影响同步结果）: {}", remoteUrl, e);
        }
    }
}
