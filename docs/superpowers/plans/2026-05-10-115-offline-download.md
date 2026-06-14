# 115 Offline Download Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add configurable 115 offline download support with a synchronous `POST /api/offline_download` endpoint that waits up to 10 seconds for task completion and returns a TvBox playlist.

**Architecture:** Introduce a dedicated offline-download service/controller pair that owns configuration persistence, AList `set_115` synchronization, task submission, polling, and playlist conversion. Extend the existing netdisk account configuration dialog to manage the new setting and keep AList's `temp_dir` in sync with the selected 115 account.

**Tech Stack:** Spring Boot, Jackson, RestTemplate, Vue 3, Element Plus, MockMvc, JUnit 5, Mockito

---

### Task 1: Add Failing Backend Tests For Offline Download Configuration And API

**Files:**
- Create: `src/test/java/cn/har01d/alist_tvbox/web/OfflineDownloadControllerTest.java`
- Create: `src/test/java/cn/har01d/alist_tvbox/service/OfflineDownloadServiceTest.java`
- Reference: `src/test/java/cn/har01d/alist_tvbox/web/TvBoxControllerTest.java`
- Reference: `src/main/java/cn/har01d/alist_tvbox/config/RestErrorHandler.java`

- [ ] **Step 1: Write the failing controller tests**

```java
package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.config.RestErrorHandler;
import cn.har01d.alist_tvbox.service.OfflineDownloadService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OfflineDownloadControllerTest {
    @Mock
    private OfflineDownloadService offlineDownloadService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        OfflineDownloadController controller = new OfflineDownloadController(offlineDownloadService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RestErrorHandler())
                .build();
    }

    @Test
    void shouldGetOfflineDownloadConfig() throws Exception {
        when(offlineDownloadService.getConfig()).thenReturn(new OfflineDownloadService.ConfigResponse(true, "PAN115", 12, "/115云盘/测试"));

        mockMvc.perform(get("/api/offline_download/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.driverType").value("PAN115"))
                .andExpect(jsonPath("$.accountId").value(12))
                .andExpect(jsonPath("$.folder").value("/115云盘/测试"));
    }

    @Test
    void shouldSaveOfflineDownloadConfig() throws Exception {
        mockMvc.perform(post("/api/offline_download/config")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"driverType":"PAN115","accountId":12}
                                """))
                .andExpect(status().isOk());

        verify(offlineDownloadService).saveConfig(any());
    }

    @Test
    void shouldReturnPlaylistForOfflineDownload() throws Exception {
        when(offlineDownloadService.download(any())).thenReturn(Map.of("list", "ok"));

        mockMvc.perform(post("/api/offline_download")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"url":"magnet:?xt=urn:btih:test"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list").value("ok"));
    }
}
```

- [ ] **Step 2: Write the failing service tests**

```java
package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OfflineDownloadServiceTest {
    @Mock
    private SettingRepository settingRepository;
    @Mock
    private DriverAccountRepository driverAccountRepository;
    @Mock
    private AListLocalService aListLocalService;
    @Mock
    private AccountService accountService;
    @Mock
    private TvBoxService tvBoxService;
    @Mock
    private org.springframework.web.client.RestTemplate restTemplate;

    private OfflineDownloadService service;

    @BeforeEach
    void setUp() {
        service = new OfflineDownloadService(
                settingRepository,
                driverAccountRepository,
                aListLocalService,
                accountService,
                tvBoxService,
                restTemplate,
                new ObjectMapper()
        );
    }

    @Test
    void saveConfigShouldRejectMissingAccountWhenEnabled() {
        assertThrows(BadRequestException.class, () ->
                service.saveConfig(new OfflineDownloadService.ConfigRequest(true, "PAN115", null)));
    }

    @Test
    void saveConfigShouldPersistAndSyncSet115() {
        DriverAccount account = new DriverAccount();
        account.setId(12);
        account.setType(DriverType.PAN115);
        account.setFolder("/115云盘/测试");
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));

        service.saveConfig(new OfflineDownloadService.ConfigRequest(true, "PAN115", 12));

        verify(settingRepository).save(any(Setting.class));
        verify(aListLocalService).set115TempDir("/115云盘/测试/alist-tvbox-offline");
    }

    @Test
    void downloadShouldRejectUnsupportedScheme() {
        assertThrows(BadRequestException.class, () ->
                service.download(new OfflineDownloadService.DownloadRequest("ftp://example.com/test")));
    }

    @Test
    void downloadShouldRejectDisabledConfig() {
        when(settingRepository.findById("offline_download_config")).thenReturn(Optional.empty());

        assertThrows(BadRequestException.class, () ->
                service.download(new OfflineDownloadService.DownloadRequest("magnet:?xt=urn:btih:test")));
    }

    @Test
    void downloadShouldReturnPlaylistOnSuccess() {
        DriverAccount account = new DriverAccount();
        account.setId(12);
        account.setType(DriverType.PAN115);
        account.setFolder("/115云盘/测试");
        when(settingRepository.findById("offline_download_config"))
                .thenReturn(Optional.of(new Setting("offline_download_config", "{\"enabled\":true,\"driverType\":\"PAN115\",\"accountId\":12}")));
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));
        when(accountService.login()).thenReturn("Bearer test-token");
        when(tvBoxService.getDetail("", "1$/115云盘/测试/alist-tvbox-offline/任务名/~playlist")).thenReturn(Map.of("vod_name", "ok"));
        when(restTemplate.postForObject(eq("/api/fs/add_offline_download"), any(), eq(Map.class)))
                .thenReturn(Map.of(
                        "code", 200,
                        "data", Map.of(
                                "tasks", java.util.List.of(Map.of(
                                        "id", "task-1",
                                        "status", "pending",
                                        "name", "任务名",
                                        "progress", 0
                                ))
                        )
                ));
        when(restTemplate.postForObject(eq("/api/task/offline_download/info?tid=task-1"), any(), eq(Map.class)))
                .thenReturn(Map.of(
                        "code", 200,
                        "data", Map.of(
                                "state", 2,
                                "status", "succeeded",
                                "progress", 100,
                                "error", "",
                                "name", "任务名"
                        )
                ));

        Map<String, Object> result = service.download(new OfflineDownloadService.DownloadRequest("magnet:?xt=urn:btih:test"));

        assertEquals("ok", result.get("vod_name"));
    }
}
```

- [ ] **Step 3: Run the focused backend tests to verify they fail**

Run: `./mvnw -Dtest=OfflineDownloadControllerTest,OfflineDownloadServiceTest test`

Expected: FAIL because `OfflineDownloadController` and `OfflineDownloadService` do not exist yet.

- [ ] **Step 4: Commit the failing tests**

```bash
git add src/test/java/cn/har01d/alist_tvbox/web/OfflineDownloadControllerTest.java src/test/java/cn/har01d/alist_tvbox/service/OfflineDownloadServiceTest.java
git commit -m "test: add offline download backend tests"
```

### Task 2: Implement Backend Models, Service, And Controller

**Files:**
- Create: `src/main/java/cn/har01d/alist_tvbox/web/OfflineDownloadController.java`
- Create: `src/main/java/cn/har01d/alist_tvbox/service/OfflineDownloadService.java`
- Create: `src/main/java/cn/har01d/alist_tvbox/dto/OfflineDownloadConfigDto.java`
- Create: `src/main/java/cn/har01d/alist_tvbox/dto/OfflineDownloadRequest.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/AListLocalService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/DriverAccountService.java`

- [ ] **Step 1: Implement the request/response DTOs**

```java
package cn.har01d.alist_tvbox.dto;

public record OfflineDownloadRequest(String url) {
}
```

```java
package cn.har01d.alist_tvbox.dto;

public record OfflineDownloadConfigDto(boolean enabled, String driverType, Integer accountId, String folder) {
}
```

- [ ] **Step 2: Extend `AListLocalService` with explicit offline-download helpers**

```java
public SettingResponse set115TempDir(String tempDir) {
    HttpHeaders headers = new HttpHeaders();
    Site site = siteRepository.findById(1).orElseThrow();
    headers.set(HttpHeaders.AUTHORIZATION, site.getToken());
    headers.setContentType(MediaType.APPLICATION_JSON);
    Map<String, Object> body = new HashMap<>();
    body.put("temp_dir", tempDir);
    HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
    return restTemplate.postForObject("/api/admin/setting/set_115", entity, SettingResponse.class);
}
```

Keep the method focused on the AList admin call rather than routing this through generic `updateSetting`.

- [ ] **Step 3: Implement `OfflineDownloadService` with config persistence and polling**

```java
@Service
public class OfflineDownloadService {
    static final String SETTING_NAME = "offline_download_config";
    static final String DRIVER_TYPE = "PAN115";
    static final String OFFLINE_DIR = "/alist-tvbox-offline";

    public record ConfigRequest(boolean enabled, String driverType, Integer accountId) {}
    public record ConfigResponse(boolean enabled, String driverType, Integer accountId, String folder) {}
    public record DownloadRequest(String url) {}

    private final SettingRepository settingRepository;
    private final DriverAccountRepository driverAccountRepository;
    private final AListLocalService aListLocalService;
    private final AccountService accountService;
    private final TvBoxService tvBoxService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public OfflineDownloadService(SettingRepository settingRepository,
                                  DriverAccountRepository driverAccountRepository,
                                  AListLocalService aListLocalService,
                                  AccountService accountService,
                                  TvBoxService tvBoxService,
                                  RestTemplate restTemplate,
                                  ObjectMapper objectMapper) {
        this.settingRepository = settingRepository;
        this.driverAccountRepository = driverAccountRepository;
        this.aListLocalService = aListLocalService;
        this.accountService = accountService;
        this.tvBoxService = tvBoxService;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public ConfigResponse getConfig() {
        Setting setting = settingRepository.findById(SETTING_NAME).orElse(null);
        if (setting == null || setting.getValue() == null || setting.getValue().isBlank()) {
            return new ConfigResponse(false, DRIVER_TYPE, null, "");
        }
        ConfigRequest config = objectMapper.readValue(setting.getValue(), ConfigRequest.class);
        DriverAccount account = config.accountId() == null ? null : driverAccountRepository.findById(config.accountId()).orElse(null);
        String folder = account == null ? "" : account.getFolder();
        return new ConfigResponse(config.enabled(), config.driverType(), config.accountId(), folder);
    }

    public ConfigResponse saveConfig(ConfigRequest request) {
        validateConfig(request);
        settingRepository.save(new Setting(SETTING_NAME, objectMapper.writeValueAsString(request)));
        if (request.enabled()) {
            DriverAccount account = get115Account(request.accountId());
            aListLocalService.set115TempDir(account.getFolder() + OFFLINE_DIR);
            return new ConfigResponse(true, DRIVER_TYPE, account.getId(), account.getFolder());
        }
        return new ConfigResponse(false, DRIVER_TYPE, request.accountId(), "");
    }

    public Object download(DownloadRequest request) {
        validateUrl(request.url());
        ConfigRequest config = loadEnabledConfig();
        DriverAccount account = get115Account(config.accountId());
        String path = account.getFolder() + OFFLINE_DIR;
        String token = accountService.login();
        HttpHeaders headers = new HttpHeaders();
        headers.set(org.springframework.http.HttpHeaders.AUTHORIZATION, token);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(Map.of(
                "urls", java.util.List.of(request.url()),
                "path", path,
                "tool", "115 Cloud",
                "delete_policy", "delete_never"
        ), headers);
        Map<String, Object> addResponse = restTemplate.postForObject("/api/fs/add_offline_download", entity, Map.class);
        java.util.List<Map<String, Object>> tasks = (java.util.List<Map<String, Object>>) ((Map<String, Object>) addResponse.get("data")).get("tasks");
        String taskId = tasks.getFirst().get("id").toString();
        for (int i = 0; i < 10; i++) {
            Map<String, Object> infoResponse = restTemplate.postForObject("/api/task/offline_download/info?tid=" + taskId, new HttpEntity<>(headers), Map.class);
            Map<String, Object> data = (Map<String, Object>) infoResponse.get("data");
            int state = ((Number) data.get("state")).intValue();
            if (state == 2) {
                String name = data.get("name").toString();
                return tvBoxService.getDetail("", "1$" + path + "/" + name + "/~playlist");
            }
            if (state == 5 || state == 6 || state == 7) {
                throw new BadRequestException("task failed: " + java.util.Objects.toString(data.get("error"), data.get("status").toString()));
            }
            Thread.sleep(1000);
        }
        throw new BadRequestException("离线下载任务未在10秒内完成");
    }

    void syncSelectedAccountTempDir(Integer accountId) {
        Setting setting = settingRepository.findById(SETTING_NAME).orElse(null);
        if (setting == null || setting.getValue() == null || setting.getValue().isBlank()) {
            return;
        }
        ConfigRequest config = objectMapper.readValue(setting.getValue(), ConfigRequest.class);
        if (config.enabled() && java.util.Objects.equals(config.accountId(), accountId)) {
            DriverAccount account = get115Account(accountId);
            aListLocalService.set115TempDir(account.getFolder() + OFFLINE_DIR);
        }
    }
}
```

Core implementation rules:

- Persist only `enabled`, `driverType`, and `accountId`.
- Use the selected account's current `folder` each time.
- Build task path as `folder + OFFLINE_DIR`.
- Poll every 1000 ms.
- Stop after 10 attempts.
- Treat state `5`, `6`, and `7` as failure.
- Return `tvBoxService.getDetail("", "1$" + path + "/~playlist")` on success.

- [ ] **Step 4: Implement `OfflineDownloadController`**

```java
@RestController
@RequestMapping("/api/offline_download")
public class OfflineDownloadController {
    private final OfflineDownloadService offlineDownloadService;

    public OfflineDownloadController(OfflineDownloadService offlineDownloadService) {
        this.offlineDownloadService = offlineDownloadService;
    }

    @GetMapping("/config")
    public OfflineDownloadService.ConfigResponse getConfig() {
        return offlineDownloadService.getConfig();
    }

    @PostMapping("/config")
    public OfflineDownloadService.ConfigResponse saveConfig(@RequestBody OfflineDownloadService.ConfigRequest request) {
        return offlineDownloadService.saveConfig(request);
    }

    @PostMapping
    public Object download(@RequestBody OfflineDownloadRequest request) {
        return offlineDownloadService.download(new OfflineDownloadService.DownloadRequest(request.url()));
    }
}
```

- [ ] **Step 5: Trigger `set_115` re-sync when the selected 115 account folder changes**

In `DriverAccountService`, inject `OfflineDownloadService` and add logic after `driverAccountRepository.save(account);`:

```java
String previousFolder = account.getFolder();
// apply dto updates...
driverAccountRepository.save(account);
if (account.getType() == DriverType.PAN115 && !Objects.equals(previousFolder, account.getFolder())) {
    offlineDownloadService.syncSelectedAccountTempDir(account.getId());
}
```

Keep the sync call narrow so only the configured account triggers an update.

- [ ] **Step 6: Run the focused backend tests to verify they pass**

Run: `./mvnw -Dtest=OfflineDownloadControllerTest,OfflineDownloadServiceTest test`

Expected: PASS

- [ ] **Step 7: Commit the backend implementation**

```bash
git add src/main/java/cn/har01d/alist_tvbox/web/OfflineDownloadController.java src/main/java/cn/har01d/alist_tvbox/service/OfflineDownloadService.java src/main/java/cn/har01d/alist_tvbox/dto/OfflineDownloadConfigDto.java src/main/java/cn/har01d/alist_tvbox/dto/OfflineDownloadRequest.java src/main/java/cn/har01d/alist_tvbox/service/AListLocalService.java src/main/java/cn/har01d/alist_tvbox/service/DriverAccountService.java
git commit -m "feat: add 115 offline download backend"
```

### Task 3: Extend Frontend Configuration Dialog For Offline Download

**Files:**
- Modify: `web-ui/src/views/DriverAccountView.vue`

- [ ] **Step 1: Add failing frontend state usage by wiring offline-download config into the dialog**

Add state types and defaults near the existing config dialog state:

```ts
type OfflineDownloadConfig = {
  enabled: boolean
  driverType: 'PAN115'
  accountId: number | null
}

const offlineDownloadConfig = ref<OfflineDownloadConfig>({
  enabled: false,
  driverType: 'PAN115',
  accountId: null,
})
```

Add derived helper:

```ts
const offline115Accounts = computed(() => accounts.value.filter((item: any) => item.type === 'PAN115'))
const offlineMountFolder = computed(() => {
  const account = offline115Accounts.value.find((item: any) => item.id === offlineDownloadConfig.value.accountId)
  return account?.folder || ''
})
```

- [ ] **Step 2: Add config load/save functions for offline download**

```ts
const loadOfflineDownloadConfig = () => {
  axios.get('/api/offline_download/config').then(({data}) => {
    offlineDownloadConfig.value = {
      enabled: !!data?.enabled,
      driverType: 'PAN115',
      accountId: data?.accountId ?? null,
    }
  })
}

const updateOfflineDownloadConfig = () => {
  return axios.post('/api/offline_download/config', offlineDownloadConfig.value)
}
```

Wire `openConfig()` to load both local proxy config and offline-download config before opening the dialog.

- [ ] **Step 3: Extend the config dialog template**

Add a second section below the proxy config:

```vue
<el-divider>离线下载</el-divider>
<el-form label-width="140">
  <el-form-item label="开启离线下载">
    <el-switch
      v-model="offlineDownloadConfig.enabled"
      inline-prompt
      active-text="开启"
      inactive-text="关闭"
    />
  </el-form-item>
  <el-form-item label="网盘类型">
    <el-select v-model="offlineDownloadConfig.driverType" :disabled="true">
      <el-option label="115云盘" value="PAN115" />
    </el-select>
  </el-form-item>
  <el-form-item label="115账号">
    <el-select v-model="offlineDownloadConfig.accountId" clearable :disabled="!offlineDownloadConfig.enabled">
      <el-option
        v-for="item in offline115Accounts"
        :key="item.id"
        :label="item.name"
        :value="item.id"
      />
    </el-select>
  </el-form-item>
  <el-form-item label="当前挂载目录">
    <el-input :model-value="offlineMountFolder" readonly />
  </el-form-item>
</el-form>
```

- [ ] **Step 4: Save both config groups from the existing save button**

Update the existing save handler:

```ts
const updateConfig = async () => {
  await axios.post('/api/settings', {
    name: 'local_proxy_config',
    value: JSON.stringify(localProxyConfig.value),
  })
  await updateOfflineDownloadConfig()
  ElMessage.success('更新成功')
  configVisible.value = false
}
```

Point the dialog footer button to `updateConfig`.

- [ ] **Step 5: Run the frontend build to catch type/template regressions**

Run: `npm --prefix web-ui run build`

Expected: PASS

- [ ] **Step 6: Commit the frontend changes**

```bash
git add web-ui/src/views/DriverAccountView.vue
git commit -m "feat: add offline download config ui"
```

### Task 4: Verify End-To-End Behavior And Update API Docs

**Files:**
- Modify: `api.md`
- Optional Modify: `src/test/java/cn/har01d/alist_tvbox/web/TvBoxControllerTest.java`

- [ ] **Step 1: Add API documentation entries**

Update `api.md` with:

```md
### OfflineDownloadController
115离线下载

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/offline_download/config` | 获取离线下载配置 |
| POST | `/api/offline_download/config` | 保存离线下载配置 |
| POST | `/api/offline_download` | 提交离线下载并同步返回播放列表 |
```

- [ ] **Step 2: Run the focused backend tests again**

Run: `./mvnw -Dtest=OfflineDownloadControllerTest,OfflineDownloadServiceTest test`

Expected: PASS

- [ ] **Step 3: Run the frontend build again**

Run: `npm --prefix web-ui run build`

Expected: PASS

- [ ] **Step 4: Run a broader compile check**

Run: `./mvnw -DskipTests compile`

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit the verification/docs changes**

```bash
git add api.md
git commit -m "docs: document offline download api"
```
