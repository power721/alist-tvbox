# 配置远程同步功能实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现配置数据在不同 AList-TvBox 实例间的双向同步（推送/拉取），支持模块选择和智能合并

**Architecture:** 轻量级 JSON 同步方案。SyncController 提供 REST API，SyncService 处理导出/导入/合并逻辑，RemoteClient 负责远端 HTTP 通信和认证。前端使用 Element Plus Dialog 三步流程（连接→配置→结果）。

**Tech Stack:** Spring Boot 3.5, OkHttp 4.12, Vue 3 + Element Plus, JPA

---

## 文件结构规划

**后端新增**：
- `src/main/java/cn/har01d/alist_tvbox/dto/sync/` - 同步相关 DTO
  - `MergeStrategy.java` - 枚举（OVERWRITE, MERGE）
  - `SyncData.java` - 同步数据容器
  - `SyncRequest.java` - 推送/拉取请求
  - `SyncResponse.java` - 同步响应
  - `SyncResult.java` - 单个模块结果
  - `ConnectionInfo.java` - 连接测试请求
  - `ConnectionResult.java` - 连接测试响应
- `src/main/java/cn/har01d/alist_tvbox/service/sync/` - 同步服务
  - `SyncService.java` - 核心同步逻辑
  - `RemoteClient.java` - HTTP 客户端
- `src/main/java/cn/har01d/alist_tvbox/web/SyncController.java` - REST API
- `src/main/java/cn/har01d/alist_tvbox/exception/VersionMismatchException.java` - 版本不匹配异常

**后端修改**：
- Repository 接口：添加业务键查询方法

**前端新增**：
- `web-ui/src/views/RemoteSyncDialog.vue` - 同步对话框组件

**前端修改**：
- `web-ui/src/views/Settings.vue` - 添加"远程同步"按钮

**测试**：
- `src/test/java/cn/har01d/alist_tvbox/service/sync/SyncServiceTest.java`
- `src/test/java/cn/har01d/alist_tvbox/web/SyncControllerTest.java`

---

## Task 1: 创建 DTO 和枚举类

**Files:**
- Create: `src/main/java/cn/har01d/alist_tvbox/dto/sync/MergeStrategy.java`
- Create: `src/main/java/cn/har01d/alist_tvbox/dto/sync/SyncResult.java`
- Create: `src/main/java/cn/har01d/alist_tvbox/dto/sync/ConnectionInfo.java`
- Create: `src/main/java/cn/har01d/alist_tvbox/dto/sync/ConnectionResult.java`

- [ ] **Step 1: 创建 MergeStrategy 枚举**

```java
package cn.har01d.alist_tvbox.dto.sync;

public enum MergeStrategy {
    OVERWRITE,  // 覆盖本地数据
    MERGE       // 智能合并
}
```

- [ ] **Step 2: 创建 SyncResult 类**

```java
package cn.har01d.alist_tvbox.dto.sync;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class SyncResult {
    private int imported = 0;    // 新增数量
    private int updated = 0;     // 更新数量
    private int failed = 0;      // 失败数量
    private List<String> errors = new ArrayList<>();  // 错误信息列表
}
```

- [ ] **Step 3: 创建 ConnectionInfo 类**

```java
package cn.har01d.alist_tvbox.dto.sync;

import lombok.Data;

@Data
public class ConnectionInfo {
    private String url;       // 远端地址
    private String username;  // 用户名
    private String password;  // 密码
}
```

- [ ] **Step 4: 创建 ConnectionResult 类**

```java
package cn.har01d.alist_tvbox.dto.sync;

import lombok.Data;

@Data
public class ConnectionResult {
    private boolean success;     // 连接是否成功
    private String appVersion;   // 远端版本号
    private String token;        // 临时 token
    private String message;      // 提示信息
}
```

- [ ] **Step 5: 编译验证**

Run: `mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 6: 提交**

```bash
git add src/main/java/cn/har01d/alist_tvbox/dto/sync/
git commit -m "feat(sync): 添加同步功能 DTO 和枚举类"
```

---

## Task 2: 创建 SyncData 和 SyncRequest 类

**Files:**
- Create: `src/main/java/cn/har01d/alist_tvbox/dto/sync/SyncData.java`
- Create: `src/main/java/cn/har01d/alist_tvbox/dto/sync/SyncRequest.java`
- Create: `src/main/java/cn/har01d/alist_tvbox/dto/sync/SyncResponse.java`

- [ ] **Step 1: 创建 SyncData 类**

```java
package cn.har01d.alist_tvbox.dto.sync;

import cn.har01d.alist_tvbox.entity.*;
import lombok.Data;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class SyncData {
    private String appVersion;  // 应用版本号
    private Map<String, Object> modules = new HashMap<>();
    
    // 辅助方法：设置各个模块数据
    public void setSites(List<Site> sites) {
        modules.put("sites", sites);
    }
    
    public void setShares(List<Share> shares) {
        modules.put("shares", shares);
    }
    
    public void setAccounts(List<Account> accounts) {
        modules.put("accounts", accounts);
    }
    
    public void setDriverAccounts(List<DriverAccount> driverAccounts) {
        modules.put("driverAccounts", driverAccounts);
    }
    
    public void setPikpakAccounts(List<PikPakAccount> pikpakAccounts) {
        modules.put("pikpakAccounts", pikpakAccounts);
    }
    
    public void setSubscriptions(List<Subscription> subscriptions) {
        modules.put("subscriptions", subscriptions);
    }
    
    public void setPlugins(List<Plugin> plugins) {
        modules.put("plugins", plugins);
    }
    
    public void setPluginFilters(List<PluginFilter> pluginFilters) {
        modules.put("pluginFilters", pluginFilters);
    }
    
    public void setSettings(Map<String, String> settings) {
        modules.put("settings", settings);
    }
}
```

- [ ] **Step 2: 创建 SyncRequest 类**

```java
package cn.har01d.alist_tvbox.dto.sync;

import lombok.Data;
import java.util.List;

@Data
public class SyncRequest {
    private String remoteUrl;           // 远端地址
    private String username;            // 用户名
    private String password;            // 密码
    private List<String> modules;       // 要同步的模块
    private MergeStrategy strategy;     // 合并策略（仅拉取时用）
    private boolean force;              // 是否强制同步（版本不匹配时）
    private SyncData data;              // 同步数据（仅导入时用）
}
```

- [ ] **Step 3: 创建 SyncResponse 类**

```java
package cn.har01d.alist_tvbox.dto.sync;

import lombok.Data;
import java.util.HashMap;
import java.util.Map;

@Data
public class SyncResponse {
    private boolean success;  // 总体是否成功
    private Map<String, SyncResult> results = new HashMap<>();  // 各模块结果
    
    public void addResult(String module, SyncResult result) {
        results.put(module, result);
    }
}
```

- [ ] **Step 4: 编译验证**

Run: `mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/cn/har01d/alist_tvbox/dto/sync/
git commit -m "feat(sync): 添加 SyncData、SyncRequest 和 SyncResponse"
```

---

## Task 3: 创建 VersionMismatchException

**Files:**
- Create: `src/main/java/cn/har01d/alist_tvbox/exception/VersionMismatchException.java`

- [ ] **Step 1: 创建 VersionMismatchException 类**

```java
package cn.har01d.alist_tvbox.exception;

public class VersionMismatchException extends RuntimeException {
    private final String localVersion;
    private final String remoteVersion;
    
    public VersionMismatchException(String localVersion, String remoteVersion) {
        super(String.format("版本不一致：本地 %s，远端 %s", localVersion, remoteVersion));
        this.localVersion = localVersion;
        this.remoteVersion = remoteVersion;
    }
    
    public String getLocalVersion() {
        return localVersion;
    }
    
    public String getRemoteVersion() {
        return remoteVersion;
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add src/main/java/cn/har01d/alist_tvbox/exception/VersionMismatchException.java
git commit -m "feat(sync): 添加版本不匹配异常"
```

---

## Task 4: 扩展 Repository 添加业务键查询方法

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/entity/ShareRepository.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/entity/SiteRepository.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/entity/AccountRepository.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/entity/DriverAccountRepository.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/entity/PikPakAccountRepository.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/entity/SubscriptionRepository.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/entity/PluginRepository.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/entity/PluginFilterRepository.java`

- [ ] **Step 1: 添加 ShareRepository 业务键查询方法**

在 `ShareRepository` 接口中添加：
```java
Optional<Share> findByTypeAndShareId(Integer type, String shareId);
```

- [ ] **Step 2: 添加 SiteRepository 业务键查询方法**

在 `SiteRepository` 接口中添加：
```java
Optional<Site> findByUrl(String url);
```

- [ ] **Step 3: 添加 AccountRepository 业务键查询方法**

在 `AccountRepository` 接口中添加：
```java
Optional<Account> findByNickname(String nickname);
```

- [ ] **Step 4: 添加 DriverAccountRepository 业务键查询方法**

在 `DriverAccountRepository` 接口中添加：
```java
Optional<DriverAccount> findByTypeAndUsername(DriverType type, String username);
Optional<DriverAccount> findByTypeAndName(DriverType type, String name);
```

- [ ] **Step 5: 添加 PikPakAccountRepository 业务键查询方法**

在 `PikPakAccountRepository` 接口中添加：
```java
Optional<PikPakAccount> findByUsername(String username);
```

- [ ] **Step 6: 添加 SubscriptionRepository 业务键查询方法**

在 `SubscriptionRepository` 接口中添加：
```java
Optional<Subscription> findByUrl(String url);
```

- [ ] **Step 7: 添加 PluginRepository 业务键查询方法**

在 `PluginRepository` 接口中添加：
```java
Optional<Plugin> findByExternalId(String externalId);
Optional<Plugin> findByUrl(String url);
```

- [ ] **Step 8: 添加 PluginFilterRepository 业务键查询方法**

在 `PluginFilterRepository` 接口中添加：
```java
Optional<PluginFilter> findByUrl(String url);
```

- [ ] **Step 9: 编译验证**

Run: `mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 10: 提交**

```bash
git add src/main/java/cn/har01d/alist_tvbox/entity/
git commit -m "feat(sync): 扩展 Repository 添加业务键查询方法"
```

---

## Task 5: 实现 RemoteClient HTTP 客户端

**Files:**
- Create: `src/main/java/cn/har01d/alist_tvbox/service/sync/RemoteClient.java`

- [ ] **Step 1: 创建 RemoteClient 类框架**

```java
package cn.har01d.alist_tvbox.service.sync;

import cn.har01d.alist_tvbox.dto.sync.SyncData;
import cn.har01d.alist_tvbox.dto.sync.SyncRequest;
import cn.har01d.alist_tvbox.dto.sync.SyncResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class RemoteClient {
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    public RemoteClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }
    
    // 方法将在后续步骤实现
}
```

- [ ] **Step 2: 实现 login 方法（Basic Auth）**

```java
public String login(String remoteUrl, String username, String password) throws IOException {
    String credentials = username + ":" + password;
    String basicAuth = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());
    
    Request request = new Request.Builder()
            .url(remoteUrl + "/api/settings")  // 测试端点
            .header("Authorization", basicAuth)
            .get()
            .build();
    
    try (Response response = httpClient.newCall(request).execute()) {
        if (!response.isSuccessful()) {
            if (response.code() == 401) {
                throw new IOException("认证失败：用户名或密码错误");
            }
            throw new IOException("连接失败：HTTP " + response.code());
        }
        // Basic Auth 成功，返回 credentials 作为 token
        return basicAuth;
    } catch (IOException e) {
        log.error("登录远端失败: {}", remoteUrl, e);
        throw new IOException("无法连接到远端服务器，请检查地址和网络");
    }
}
```

- [ ] **Step 3: 实现 fetchRemoteData 方法**

```java
public SyncData fetchRemoteData(String remoteUrl, String token, List<String> modules) throws IOException {
    String modulesParam = String.join(",", modules);
    String url = remoteUrl + "/api/sync/export?modules=" + modulesParam;
    
    Request request = new Request.Builder()
            .url(url)
            .header("Authorization", token)
            .get()
            .build();
    
    try (Response response = httpClient.newCall(request).execute()) {
        if (!response.isSuccessful()) {
            throw new IOException("获取远端数据失败：HTTP " + response.code());
        }
        
        String body = response.body().string();
        return objectMapper.readValue(body, SyncData.class);
    } catch (IOException e) {
        log.error("从远端获取数据失败: {}", remoteUrl, e);
        throw new IOException("远端服务器不支持同步功能或版本不兼容");
    }
}
```

- [ ] **Step 4: 实现 pushToRemote 方法**

```java
public Map<String, SyncResult> pushToRemote(String remoteUrl, String token, SyncData data,
                                             String strategy, boolean force) throws IOException {
    SyncRequest request = new SyncRequest();
    request.setData(data);
    request.setStrategy(strategy != null ? 
        cn.har01d.alist_tvbox.dto.sync.MergeStrategy.valueOf(strategy.toUpperCase()) : null);
    request.setForce(force);
    
    String json = objectMapper.writeValueAsString(request);
    RequestBody body = RequestBody.create(json, MediaType.get("application/json"));
    
    Request httpRequest = new Request.Builder()
            .url(remoteUrl + "/api/sync/import")
            .header("Authorization", token)
            .post(body)
            .build();
    
    try (Response response = httpClient.newCall(httpRequest).execute()) {
        if (!response.isSuccessful()) {
            throw new IOException("推送数据到远端失败：HTTP " + response.code());
        }
        
        String responseBody = response.body().string();
        Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);
        return (Map<String, SyncResult>) result.get("results");
    } catch (IOException e) {
        log.error("推送数据到远端失败: {}", remoteUrl, e);
        throw;
    }
}
```

- [ ] **Step 5: 编译验证**

Run: `mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 6: 提交**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/sync/RemoteClient.java
git commit -m "feat(sync): 实现 RemoteClient HTTP 客户端"
```

---

## Task 6: 实现 SyncService - 导出功能

**Files:**
- Create: `src/main/java/cn/har01d/alist_tvbox/service/sync/SyncService.java`

- [ ] **Step 1: 创建 SyncService 类框架和依赖注入**

```java
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
    
    // 方法将在后续步骤实现
}
```

- [ ] **Step 2: 实现 exportData 方法**

```java
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
```

- [ ] **Step 3: 实现 exportSettings 辅助方法**

```java
private Map<String, String> exportSettings() {
    Map<String, String> settings = new HashMap<>();
    for (String key : SETTING_WHITELIST) {
        settingRepository.findById(key).ifPresent(setting -> {
            settings.put(key, setting.getValue());
        });
    }
    return settings;
}
```

- [ ] **Step 4: 编译验证**

Run: `mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/sync/SyncService.java
git commit -m "feat(sync): 实现 SyncService 导出功能"
```

---

## Task 7: 实现 SyncService - 导入功能（合并模式）

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/sync/SyncService.java`

- [ ] **Step 1: 实现 importData 主方法**

在 `SyncService` 类中添加：
```java
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
```

- [ ] **Step 2: 实现 importSettings 方法**

```java
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
```

- [ ] **Step 3: 实现 importSites 方法（基于 url 合并）**

```java
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
```

- [ ] **Step 4: 编译验证**

Run: `mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/sync/SyncService.java
git commit -m "feat(sync): 实现 SyncService 导入功能（Settings 和 Sites）"
```

---

## Task 8: 实现 SyncService - 其他模块导入

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/sync/SyncService.java`

- [ ] **Step 1: 实现 importShares 方法（基于 type:shareId）**

在 `SyncService` 类中添加：
```java
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
                    remote.setId(null);
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
```

- [ ] **Step 2: 实现 importAccounts 方法（基于 nickname）**

```java
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
```

- [ ] **Step 3: 实现 importDriverAccounts 方法（type+username 或 type+name）**

```java
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
```

- [ ] **Step 4: 编译验证**

Run: `mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/sync/SyncService.java
git commit -m "feat(sync): 实现 Shares、Accounts、DriverAccounts 导入"
```

---

## Task 9: 实现 SyncService - 剩余模块导入

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/sync/SyncService.java`

- [ ] **Step 1: 实现 importPikPakAccounts 方法**

在 `SyncService` 类中添加：
```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public SyncResult importPikPakAccounts(List<PikPakAccount> accounts, MergeStrategy strategy) {
    SyncResult result = new SyncResult();
    
    try {
        if (strategy == MergeStrategy.OVERWRITE) {
            pikPakAccountRepository.deleteAll();
        }
        
        for (PikPakAccount remote : accounts) {
            try {
                Optional<PikPakAccount> existing = pikPakAccountRepository.findByUsername(remote.getUsername());
                
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
```

- [ ] **Step 2: 实现 importSubscriptions 方法**

```java
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
```

- [ ] **Step 3: 实现 importPlugins 方法（externalId 或 url）**

```java
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
                    existing.setContent(remote.getContent());
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
        
        log.info("导入 Plugins 完成: 新增 {}, 更新 {}, 失败 {}", 
                result.getImported(), result.getUpdated(), result.getFailed());
    } catch (Exception e) {
        log.error("导入 Plugins 失败", e);
        result.setFailed(plugins.size());
        result.getErrors().add("批量导入失败: " + e.getMessage());
    }
    
    return result;
}
```

- [ ] **Step 4: 实现 importPluginFilters 方法**

```java
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
                    local.setContent(remote.getContent());
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
```

- [ ] **Step 5: 编译验证**

Run: `mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 6: 提交**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/sync/SyncService.java
git commit -m "feat(sync): 实现剩余模块导入（PikPak、Subscription、Plugin、PluginFilter）"
```

---

## Task 10: 实现 SyncService - 推送和拉取方法

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/sync/SyncService.java`

- [ ] **Step 1: 实现 push 方法**

在 `SyncService` 类中添加：
```java
public SyncResponse push(String remoteUrl, String username, String password, List<String> modules) {
    SyncResponse response = new SyncResponse();
    
    try {
        // 登录远端
        String token = remoteClient.login(remoteUrl, username, password);
        
        // 导出本地数据
        SyncData data = exportData(modules);
        
        // 推送到远端（远端使用覆盖模式）
        Map<String, SyncResult> results = remoteClient.pushToRemote(
            remoteUrl, token, data, "OVERWRITE", false);
        
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
    }
    
    return response;
}
```

- [ ] **Step 2: 实现 pull 方法**

```java
public SyncResponse pull(String remoteUrl, String username, String password, 
                        List<String> modules, MergeStrategy strategy, boolean force) {
    SyncResponse response = new SyncResponse();
    
    try {
        // 登录远端
        String token = remoteClient.login(remoteUrl, username, password);
        
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
        log.error("从远端拉取失败: {}", remoteUrl, e);
        response.setSuccess(false);
        SyncResult errorResult = new SyncResult();
        errorResult.setFailed(1);
        errorResult.getErrors().add(e.getMessage());
        response.addResult("error", errorResult);
    }
    
    return response;
}
```

- [ ] **Step 3: 编译验证**

Run: `mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/sync/SyncService.java
git commit -m "feat(sync): 实现推送和拉取方法"
```

---

## Task 11: 实现 SyncController REST API

**Files:**
- Create: `src/main/java/cn/har01d/alist_tvbox/web/SyncController.java`

- [ ] **Step 1: 创建 SyncController 类框架**

```java
package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.sync.*;
import cn.har01d.alist_tvbox.exception.VersionMismatchException;
import cn.har01d.alist_tvbox.service.sync.SyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/sync")
public class SyncController {
    private final SyncService syncService;
    
    public SyncController(SyncService syncService) {
        this.syncService = syncService;
    }
    
    // 方法将在后续步骤实现
}
```

- [ ] **Step 2: 实现 connect 端点**

```java
@PostMapping("/connect")
public ConnectionResult connect(@RequestBody ConnectionInfo info) {
    ConnectionResult result = new ConnectionResult();
    
    try {
        // 登录测试
        String token = syncService.getRemoteClient().login(info.getUrl(), 
            info.getUsername(), info.getPassword());
        
        // 获取远端版本
        SyncData data = syncService.getRemoteClient().fetchRemoteData(
            info.getUrl(), token, List.of());
        
        result.setSuccess(true);
        result.setToken(token);
        result.setAppVersion(data.getAppVersion());
        result.setMessage("连接成功");
        
        log.info("连接远端成功: {}", info.getUrl());
    } catch (Exception e) {
        log.error("连接远端失败: {}", info.getUrl(), e);
        result.setSuccess(false);
        result.setMessage(e.getMessage());
    }
    
    return result;
}
```

- [ ] **Step 3: 添加 getRemoteClient 方法到 SyncService**

在 `SyncService` 类中添加：
```java
public RemoteClient getRemoteClient() {
    return remoteClient;
}
```

- [ ] **Step 4: 实现 export 端点**

在 `SyncController` 类中添加：
```java
@GetMapping("/export")
public SyncData export(@RequestParam("modules") List<String> modules) {
    log.info("导出数据，模块: {}", modules);
    return syncService.exportData(modules);
}
```

- [ ] **Step 5: 实现 importData 端点**

```java
@PostMapping("/import")
public SyncResponse importData(@RequestBody SyncRequest request) {
    log.info("导入数据，策略: {}, force: {}", request.getStrategy(), request.isForce());
    
    SyncResponse response = new SyncResponse();
    try {
        Map<String, SyncResult> results = syncService.importData(
            request.getData(), 
            request.getStrategy(), 
            request.isForce());
        
        response.setSuccess(true);
        response.setResults(results);
    } catch (VersionMismatchException e) {
        response.setSuccess(false);
        SyncResult errorResult = new SyncResult();
        errorResult.setFailed(1);
        errorResult.getErrors().add(e.getMessage());
        response.addResult("version_error", errorResult);
    } catch (Exception e) {
        log.error("导入数据失败", e);
        response.setSuccess(false);
        SyncResult errorResult = new SyncResult();
        errorResult.setFailed(1);
        errorResult.getErrors().add("导入失败: " + e.getMessage());
        response.addResult("error", errorResult);
    }
    
    return response;
}
```

- [ ] **Step 6: 实现 push 端点**

```java
@PostMapping("/push")
public SyncResponse push(@RequestBody SyncRequest request) {
    log.info("推送到远端: {}, 模块: {}", request.getRemoteUrl(), request.getModules());
    return syncService.push(
        request.getRemoteUrl(),
        request.getUsername(),
        request.getPassword(),
        request.getModules()
    );
}
```

- [ ] **Step 7: 实现 pull 端点**

```java
@PostMapping("/pull")
public SyncResponse pull(@RequestBody SyncRequest request) {
    log.info("从远端拉取: {}, 模块: {}, 策略: {}", 
        request.getRemoteUrl(), request.getModules(), request.getStrategy());
    
    try {
        return syncService.pull(
            request.getRemoteUrl(),
            request.getUsername(),
            request.getPassword(),
            request.getModules(),
            request.getStrategy(),
            request.isForce()
        );
    } catch (VersionMismatchException e) {
        // 版本不匹配，返回特殊响应让前端处理
        SyncResponse response = new SyncResponse();
        response.setSuccess(false);
        SyncResult errorResult = new SyncResult();
        errorResult.setFailed(1);
        errorResult.getErrors().add("VERSION_MISMATCH:" + e.getMessage());
        response.addResult("version_error", errorResult);
        return response;
    }
}
```

- [ ] **Step 8: 编译验证**

Run: `mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 9: 提交**

```bash
git add src/main/java/cn/har01d/alist_tvbox/web/SyncController.java
git add src/main/java/cn/har01d/alist_tvbox/service/sync/SyncService.java
git commit -m "feat(sync): 实现 SyncController REST API"
```

---

## Task 12: 手动测试后端 API

**Files:**
- Test: Backend APIs

- [ ] **Step 1: 启动应用**

Run: `mvn spring-boot:run`
Expected: 应用启动成功，监听 4567 端口

- [ ] **Step 2: 测试导出 API**

```bash
curl -H "Authorization: Basic YWRtaW46cGFzc3dvcmQ=" \
  "http://localhost:4567/api/sync/export?modules=sites,settings"
```

Expected: 返回 JSON，包含 `appVersion` 和 `modules.sites`、`modules.settings`

- [ ] **Step 3: 测试连接 API（失败场景）**

```bash
curl -X POST http://localhost:4567/api/sync/connect \
  -H "Content-Type: application/json" \
  -d '{
    "url": "http://invalid-url:9999",
    "username": "admin",
    "password": "wrong"
  }'
```

Expected: `{"success": false, "message": "无法连接到远端服务器..."}`

- [ ] **Step 4: 验证版本校验逻辑**

手动调用 `/api/sync/import`，传入不同 `appVersion`，不带 `force=true`

Expected: 抛出 VersionMismatchException，响应中包含 `VERSION_MISMATCH`

- [ ] **Step 5: 停止应用**

Ctrl+C 停止 Spring Boot

- [ ] **Step 6: 提交测试记录**

```bash
git commit --allow-empty -m "test(sync): 手动测试后端 API 通过"
```

---

## Task 13: 前端 - 创建 RemoteSyncDialog 组件

**Files:**
- Create: `web-ui/src/views/RemoteSyncDialog.vue`

- [ ] **Step 1: 创建组件框架和 data**

```vue
<template>
  <el-dialog
    v-model="visible"
    title="远程同步"
    width="600px"
    :close-on-click-modal="false"
  >
    <el-steps :active="currentStep" finish-status="success" align-center>
      <el-step title="连接远端" />
      <el-step title="配置同步" />
      <el-step title="同步完成" />
    </el-steps>

    <!-- 步骤内容将在后续添加 -->

  </el-dialog>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import axios from 'axios'

const visible = ref(false)
const currentStep = ref(0)

// 连接信息
const connectionForm = reactive({
  url: '',
  username: '',
  password: ''
})

// 同步配置
const syncConfig = reactive({
  direction: 'pull',  // 'push' | 'pull'
  strategy: 'merge',  // 'overwrite' | 'merge'
  modules: ['sites', 'shares', 'accounts', 'driverAccounts', 'pikpakAccounts', 'subscriptions', 'settings']
})

// 远端信息
const remoteInfo = reactive({
  appVersion: '',
  token: ''
})

// 同步结果
const syncResults = ref({})

// 方法将在后续步骤实现

const open = () => {
  visible.value = true
  currentStep.value = 0
}

defineExpose({ open })
</script>

<style scoped>
.el-steps {
  margin-bottom: 30px;
}
</style>
```

- [ ] **Step 2: 实现步骤 1 UI（连接远端）**

在 `<template>` 的 `<!-- 步骤内容将在后续添加 -->` 处添加：
```vue
    <div v-if="currentStep === 0" style="padding: 20px 0;">
      <el-form :model="connectionForm" label-width="100px">
        <el-form-item label="远端地址">
          <el-input v-model="connectionForm.url" placeholder="http://192.168.1.100:4567" />
        </el-form-item>
        <el-form-item label="用户名">
          <el-input v-model="connectionForm.username" placeholder="admin" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="connectionForm.password" type="password" placeholder="密码" />
        </el-form-item>
      </el-form>
    </div>

    <template #footer v-if="currentStep === 0">
      <span class="dialog-footer">
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" @click="handleConnect" :loading="connecting">
          连接并继续 →
        </el-button>
      </span>
    </template>
```

在 `<script setup>` 中添加：
```javascript
const connecting = ref(false)

const handleConnect = async () => {
  if (!connectionForm.url || !connectionForm.username || !connectionForm.password) {
    ElMessage.warning('请填写完整的连接信息')
    return
  }
  
  connecting.value = true
  try {
    const response = await axios.post('/api/sync/connect', connectionForm)
    
    if (response.data.success) {
      remoteInfo.appVersion = response.data.appVersion
      remoteInfo.token = response.data.token
      currentStep.value = 1
      ElMessage.success('连接成功')
    } else {
      ElMessage.error(response.data.message || '连接失败')
    }
  } catch (error) {
    ElMessage.error('连接失败: ' + (error.response?.data?.message || error.message))
  } finally {
    connecting.value = false
  }
}
```

- [ ] **Step 3: 编译验证前端**

Run: `cd web-ui && npm run build`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add web-ui/src/views/RemoteSyncDialog.vue
git commit -m "feat(sync): 创建 RemoteSyncDialog 组件并实现步骤1"
```

---

## Task 14: 前端 - 实现步骤 2（配置同步）

**Files:**
- Modify: `web-ui/src/views/RemoteSyncDialog.vue`

- [ ] **Step 1: 添加步骤 2 UI**

在步骤 1 的 `</div>` 后添加：
```vue
    <div v-if="currentStep === 1" style="padding: 20px 0;">
      <el-alert 
        :title="`远端版本：${remoteInfo.appVersion}`" 
        type="success" 
        :closable="false" 
        style="margin-bottom: 20px;"
      />
      
      <el-form :model="syncConfig" label-width="120px">
        <el-form-item label="同步方向">
          <el-radio-group v-model="syncConfig.direction">
            <el-radio label="push">推送到远端</el-radio>
            <el-radio label="pull">从远端拉取</el-radio>
          </el-radio-group>
        </el-form-item>
        
        <el-form-item label="合并策略" v-if="syncConfig.direction === 'pull'">
          <el-radio-group v-model="syncConfig.strategy">
            <el-radio label="overwrite">覆盖本地</el-radio>
            <el-radio label="merge">智能合并</el-radio>
          </el-radio-group>
        </el-form-item>
        
        <el-form-item label="选择模块">
          <el-checkbox-group v-model="syncConfig.modules">
            <el-checkbox label="sites">外部站点 (Sites)</el-checkbox><br/>
            <el-checkbox label="shares">网盘分享 (Shares)</el-checkbox><br/>
            <el-checkbox label="accounts">阿里云账号 (Accounts)</el-checkbox><br/>
            <el-checkbox label="driverAccounts">网盘账号 (DriverAccounts)</el-checkbox><br/>
            <el-checkbox label="pikpakAccounts">PikPak账号 (PikPakAccounts)</el-checkbox><br/>
            <el-checkbox label="subscriptions">订阅配置 (Subscriptions)</el-checkbox><br/>
            <el-checkbox label="settings">系统设置 (Settings)</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </el-form>
    </div>

    <template #footer v-if="currentStep === 1">
      <span class="dialog-footer">
        <el-button @click="currentStep = 0">← 上一步</el-button>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" @click="handleSync" :loading="syncing">
          执行同步
        </el-button>
      </span>
    </template>
```

- [ ] **Step 2: 实现 handleSync 方法**

在 `<script setup>` 中添加：
```javascript
const syncing = ref(false)

const handleSync = async () => {
  if (syncConfig.modules.length === 0) {
    ElMessage.warning('请至少选择一个模块')
    return
  }
  
  syncing.value = true
  try {
    const endpoint = syncConfig.direction === 'push' ? '/api/sync/push' : '/api/sync/pull'
    const payload = {
      remoteUrl: connectionForm.url,
      username: connectionForm.username,
      password: connectionForm.password,
      modules: syncConfig.modules,
      strategy: syncConfig.direction === 'pull' ? syncConfig.strategy.toUpperCase() : undefined,
      force: false
    }
    
    const response = await axios.post(endpoint, payload)
    
    if (response.data.success) {
      syncResults.value = response.data.results
      currentStep.value = 2
      ElMessage.success('同步完成')
    } else {
      // 检查是否版本不匹配
      const versionError = response.data.results?.version_error
      if (versionError && versionError.errors[0]?.startsWith('VERSION_MISMATCH')) {
        await handleVersionMismatch()
      } else {
        ElMessage.error('同步失败')
        syncResults.value = response.data.results
        currentStep.value = 2
      }
    }
  } catch (error) {
    ElMessage.error('同步失败: ' + (error.response?.data?.message || error.message))
  } finally {
    syncing.value = false
  }
}

const handleVersionMismatch = async () => {
  const confirmed = await ElMessageBox.confirm(
    '版本不一致，可能导致数据不兼容。是否强制同步？',
    '版本不匹配',
    { type: 'warning' }
  ).catch(() => false)
  
  if (confirmed) {
    syncing.value = true
    try {
      const endpoint = syncConfig.direction === 'push' ? '/api/sync/push' : '/api/sync/pull'
      const payload = {
        remoteUrl: connectionForm.url,
        username: connectionForm.username,
        password: connectionForm.password,
        modules: syncConfig.modules,
        strategy: syncConfig.direction === 'pull' ? syncConfig.strategy.toUpperCase() : undefined,
        force: true
      }
      
      const response = await axios.post(endpoint, payload)
      
      if (response.data.success) {
        syncResults.value = response.data.results
        currentStep.value = 2
        ElMessage.success('同步完成')
      } else {
        ElMessage.error('同步失败')
        syncResults.value = response.data.results
        currentStep.value = 2
      }
    } catch (error) {
      ElMessage.error('强制同步失败: ' + (error.response?.data?.message || error.message))
    } finally {
      syncing.value = false
    }
  }
}
```

添加 import：
```javascript
import { ElMessage, ElMessageBox } from 'element-plus'
```

- [ ] **Step 3: 编译验证**

Run: `cd web-ui && npm run build`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add web-ui/src/views/RemoteSyncDialog.vue
git commit -m "feat(sync): 实现步骤2（配置同步）"
```

---

## Task 15: 前端 - 实现步骤 3（同步结果）

**Files:**
- Modify: `web-ui/src/views/RemoteSyncDialog.vue`

- [ ] **Step 1: 添加步骤 3 UI**

在步骤 2 的 `</div>` 后添加：
```vue
    <div v-if="currentStep === 2" style="padding: 20px 0;">
      <div v-for="(result, module) in syncResults" :key="module" style="margin-bottom: 15px;">
        <el-alert
          :type="result.failed > 0 ? 'error' : 'success'"
          :closable="false"
        >
          <template #title>
            <span v-if="result.failed === 0">✓ {{ getModuleName(module) }}</span>
            <span v-else>✗ {{ getModuleName(module) }}</span>
          </template>
          <div>
            <span v-if="result.imported > 0">新增 {{ result.imported }}</span>
            <span v-if="result.updated > 0">，更新 {{ result.updated }}</span>
            <span v-if="result.failed > 0">，失败 {{ result.failed }}</span>
          </div>
          <div v-if="result.errors && result.errors.length > 0" style="margin-top: 8px; color: #F56C6C;">
            <div v-for="(error, idx) in result.errors" :key="idx" style="font-size: 12px;">
              - {{ error }}
            </div>
          </div>
        </el-alert>
      </div>
    </div>

    <template #footer v-if="currentStep === 2">
      <span class="dialog-footer">
        <el-button type="primary" @click="visible = false">关闭</el-button>
      </span>
    </template>
```

- [ ] **Step 2: 实现 getModuleName 方法**

在 `<script setup>` 中添加：
```javascript
const getModuleName = (module) => {
  const nameMap = {
    'sites': '外部站点',
    'shares': '网盘分享',
    'accounts': '阿里云账号',
    'driverAccounts': '网盘账号',
    'pikpakAccounts': 'PikPak账号',
    'subscriptions': '订阅配置',
    'plugins': '插件',
    'pluginFilters': '过滤器',
    'settings': '系统设置'
  }
  return nameMap[module] || module
}
```

- [ ] **Step 3: 编译验证**

Run: `cd web-ui && npm run build`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add web-ui/src/views/RemoteSyncDialog.vue
git commit -m "feat(sync): 实现步骤3（同步结果）"
```

---

## Task 16: 集成到设置页面

**Files:**
- Modify: `web-ui/src/views/Settings.vue`

- [ ] **Step 1: 导入 RemoteSyncDialog 组件**

在 `Settings.vue` 的 `<script setup>` 开头添加：
```javascript
import RemoteSyncDialog from './RemoteSyncDialog.vue'
import { ref } from 'vue'

const remoteSyncDialogRef = ref(null)

const openRemoteSync = () => {
  remoteSyncDialogRef.value.open()
}
```

- [ ] **Step 2: 在模板中添加按钮和组件**

在设置页面的合适位置（建议在页面顶部或底部）添加：
```vue
<el-card style="margin-bottom: 20px;">
  <template #header>
    <span>远程同步</span>
  </template>
  <el-button type="primary" @click="openRemoteSync">
    远程同步配置
  </el-button>
  <span style="margin-left: 10px; color: #909399;">
    在不同实例之间同步配置数据
  </span>
</el-card>

<RemoteSyncDialog ref="remoteSyncDialogRef" />
```

- [ ] **Step 3: 编译验证**

Run: `cd web-ui && npm run build`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add web-ui/src/views/Settings.vue
git commit -m "feat(sync): 集成远程同步到设置页面"
```

---

## Task 17: 端到端手动测试

**Files:**
- Test: Full E2E flow

- [ ] **Step 1: 启动两个实例**

实例 A（本地）：
```bash
mvn spring-boot:run
```
监听 4567 端口

实例 B（远端，Docker 或另一台机器）：
```bash
docker run -p 4568:4567 haroldli/xiaoya-tvbox:latest
```
监听 4568 端口

- [ ] **Step 2: 在实例 A 添加测试数据**

1. 访问 `http://localhost:4567` 登录
2. 添加 2 个外部站点
3. 添加 1 个网盘分享
4. 修改一个系统设置（如 `user_agent`）

- [ ] **Step 3: 测试推送到远端**

1. 访问设置页面，点击"远程同步配置"
2. 填写远端地址：`http://localhost:4568`，用户名和密码
3. 点击"连接并继续"
4. 选择"推送到远端"
5. 勾选"外部站点"、"网盘分享"、"系统设置"
6. 点击"执行同步"
7. 验证结果显示成功

- [ ] **Step 4: 验证远端数据**

1. 访问 `http://localhost:4568`
2. 检查外部站点列表，确认有 2 个站点
3. 检查网盘分享列表，确认有 1 个分享
4. 检查系统设置，确认 `user_agent` 已更新

- [ ] **Step 5: 测试拉取（合并模式）**

1. 在实例 B 添加 1 个新站点
2. 在实例 A 点击"远程同步"
3. 选择"从远端拉取"、"智能合并"
4. 勾选"外部站点"
5. 点击"执行同步"
6. 验证实例 A 现在有 3 个站点（2 个本地 + 1 个远端）

- [ ] **Step 6: 测试版本不匹配警告**

1. 修改实例 B 的 `app_version` Setting 为不同值
2. 在实例 A 尝试拉取（不勾选 force）
3. 验证弹出版本不匹配确认框
4. 选择"强制同步"
5. 验证同步成功

- [ ] **Step 7: 测试覆盖模式**

1. 在实例 A 保留 3 个站点
2. 在实例 B 删除所有站点，只保留 1 个
3. 在实例 A 选择"从远端拉取"、"覆盖本地"
4. 勾选"外部站点"
5. 验证实例 A 现在只有 1 个站点

- [ ] **Step 8: 记录测试结果**

```bash
git commit --allow-empty -m "test(sync): 端到端手动测试通过"
```

---

## Task 18: 编写单元测试

**Files:**
- Create: `src/test/java/cn/har01d/alist_tvbox/service/sync/SyncServiceTest.java`

- [ ] **Step 1: 创建测试类框架**

```java
package cn.har01d.alist_tvbox.service.sync;

import cn.har01d.alist_tvbox.dto.sync.*;
import cn.har01d.alist_tvbox.entity.*;
import cn.har01d.alist_tvbox.exception.VersionMismatchException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SyncServiceTest {
    @Mock private SettingRepository settingRepository;
    @Mock private SiteRepository siteRepository;
    @Mock private ShareRepository shareRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private DriverAccountRepository driverAccountRepository;
    @Mock private PikPakAccountRepository pikPakAccountRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private PluginRepository pluginRepository;
    @Mock private PluginFilterRepository pluginFilterRepository;
    @Mock private RemoteClient remoteClient;
    @Mock private ObjectMapper objectMapper;
    
    @InjectMocks
    private SyncService syncService;
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }
    
    // 测试方法将在后续步骤实现
}
```

- [ ] **Step 2: 编写 exportData 测试**

```java
@Test
void testExportData_sites() {
    // 准备数据
    Setting appVersion = new Setting("app_version", "1.0.0");
    when(settingRepository.findById("app_version")).thenReturn(Optional.of(appVersion));
    
    Site site1 = new Site();
    site1.setId(1);
    site1.setUrl("http://test.com");
    when(siteRepository.findAll()).thenReturn(List.of(site1));
    
    // 执行
    SyncData result = syncService.exportData(List.of("sites"));
    
    // 验证
    assertEquals("1.0.0", result.getAppVersion());
    assertTrue(result.getModules().containsKey("sites"));
    List<Site> sites = (List<Site>) result.getModules().get("sites");
    assertEquals(1, sites.size());
    assertEquals("http://test.com", sites.get(0).getUrl());
}
```

- [ ] **Step 3: 编写版本校验测试**

```java
@Test
void testImportData_versionMismatch_throwsException() {
    // 准备数据
    Setting localVersion = new Setting("app_version", "1.0.0");
    when(settingRepository.findById("app_version")).thenReturn(Optional.of(localVersion));
    
    SyncData remoteData = new SyncData();
    remoteData.setAppVersion("2.0.0");
    
    // 执行和验证
    assertThrows(VersionMismatchException.class, () -> {
        syncService.importData(remoteData, MergeStrategy.MERGE, false);
    });
}
```

- [ ] **Step 4: 编写 importSites 合并模式测试**

```java
@Test
void testImportSites_merge_updatesExisting() {
    // 准备本地数据
    Site localSite = new Site();
    localSite.setId(1);
    localSite.setUrl("http://test.com");
    localSite.setName("Old Name");
    when(siteRepository.findByUrl("http://test.com")).thenReturn(Optional.of(localSite));
    
    // 准备远端数据
    Site remoteSite = new Site();
    remoteSite.setUrl("http://test.com");
    remoteSite.setName("New Name");
    
    // 执行
    SyncResult result = syncService.importSites(List.of(remoteSite), MergeStrategy.MERGE);
    
    // 验证
    assertEquals(0, result.getImported());
    assertEquals(1, result.getUpdated());
    assertEquals(0, result.getFailed());
    verify(siteRepository, times(1)).save(any(Site.class));
}
```

- [ ] **Step 5: 编写 importSites 覆盖模式测试**

```java
@Test
void testImportSites_overwrite_deletesAll() {
    // 准备远端数据
    Site remoteSite = new Site();
    remoteSite.setUrl("http://new.com");
    
    // 执行
    syncService.importSites(List.of(remoteSite), MergeStrategy.OVERWRITE);
    
    // 验证
    verify(siteRepository, times(1)).deleteAll();
    verify(siteRepository, times(1)).save(any(Site.class));
}
```

- [ ] **Step 6: 运行测试**

Run: `mvn test -Dtest=SyncServiceTest`
Expected: All tests pass

- [ ] **Step 7: 提交**

```bash
git add src/test/java/cn/har01d/alist_tvbox/service/sync/SyncServiceTest.java
git commit -m "test(sync): 添加 SyncService 单元测试"
```

---

## Task 19: 文档和最终验证

**Files:**
- Modify: `README.md` or create API documentation

- [ ] **Step 1: 添加 API 文档到 README（可选）**

在 `README.md` 中添加同步 API 说明：
```markdown
## 远程同步 API

### 连接测试
POST /api/sync/connect
```json
{
  "url": "http://remote:4567",
  "username": "admin",
  "password": "password"
}
```

### 导出数据
GET /api/sync/export?modules=sites,shares

### 导入数据
POST /api/sync/import

### 推送到远端
POST /api/sync/push

### 从远端拉取
POST /api/sync/pull
```

- [ ] **Step 2: 完整构建测试**

Run: `mvn clean package`
Expected: BUILD SUCCESS，生成 alist-tvbox-1.0.jar

- [ ] **Step 3: 运行完整测试套件**

Run: `mvn test`
Expected: All tests pass

- [ ] **Step 4: 验证前端构建**

Run: `cd web-ui && npm run build`
Expected: BUILD SUCCESS，静态文件生成到 `src/main/resources/static/`

- [ ] **Step 5: 启动应用并手动验证核心流程**

```bash
java -jar target/alist-tvbox-1.0.jar
```

验证清单：
- [ ] 设置页面显示"远程同步配置"按钮
- [ ] 点击按钮打开对话框
- [ ] 步骤 1：连接测试成功
- [ ] 步骤 2：模块选择和策略配置正常
- [ ] 步骤 3：同步结果正确显示

- [ ] **Step 6: 最终提交**

```bash
git add README.md
git commit -m "docs(sync): 添加远程同步 API 文档"
```

---

## 自检清单

完成所有任务后，回顾设计文档确认实现完整性：

### 功能覆盖

- [x] 双向同步（推送 + 拉取）
- [x] 模块选择（7 个模块：sites, shares, accounts, driverAccounts, pikpakAccounts, subscriptions, settings）
- [x] 合并策略（覆盖 + 智能合并）
- [x] 版本校验（默认强制，force 参数绕过）
- [x] 用户认证（用户名密码登录）
- [x] 临时操作（token 内存存储，不持久化）

### 业务键实现

- [x] Site: url
- [x] Share: type:shareId
- [x] Account: nickname
- [x] DriverAccount: type+username（优先）或 type+name
- [x] PikPakAccount: username
- [x] Subscription: url
- [x] Plugin: externalId（优先）或 url
- [x] PluginFilter: url

### Setting 白名单

- [x] 34 个白名单 Setting 已定义
- [x] 排除系统标识（api_key, system_id, install_mode）
- [x] 排除运行时状态字段

### API 端点

- [x] POST /api/sync/connect
- [x] GET /api/sync/export
- [x] POST /api/sync/import
- [x] POST /api/sync/push
- [x] POST /api/sync/pull

### 前端流程

- [x] 步骤 1：连接远端
- [x] 步骤 2：配置同步（方向、策略、模块）
- [x] 步骤 3：同步结果展示
- [x] 版本不匹配弹窗确认

### 异常处理

- [x] 网络错误
- [x] 认证失败
- [x] 版本不匹配
- [x] 模块级独立事务
- [x] 部分失败不影响其他模块

### 测试

- [x] 单元测试（SyncServiceTest）
- [x] 手动测试（后端 API）
- [x] 端到端测试（完整流程）

---

## 预计工作量

- **后端**: Task 1-11（约 4-5 小时）
- **前端**: Task 13-16（约 2-3 小时）
- **测试**: Task 12, 17-18（约 2-3 小时）
- **总计**: 8-11 小时

---

## 后续优化建议

实现计划未包含的优化方向（参考设计文档第十一节）：

1. **增量同步**：仅同步变更记录（需时间戳字段）
2. **批量优化**：大数据量分批导入
3. **冲突解决 UI**：显示冲突列表，用户手动选择
4. **配置模板**：保存常用远端配置
5. **定时同步**：周期性自动同步
6. **双向合并**：推送时也支持合并策略

这些可作为后续迭代功能开发。

