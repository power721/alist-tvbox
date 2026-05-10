# Cloud Offline Download Thunder Extension Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the existing offline-download implementation so the same config/API flow supports both `PAN115` and `THUNDER` accounts.

**Architecture:** Keep the existing `OfflineDownloadService` and add a small driver mapping layer for driver type validation, temp-dir admin endpoint selection, AList tool selection, and account filtering in the frontend. Reuse the current synchronous polling and playlist return flow.

**Tech Stack:** Spring Boot, Jackson, RestTemplate, Vue 3, Element Plus, JUnit 5, Mockito

---

### Task 1: Add Failing Tests For THUNDER Support

**Files:**
- Modify: `src/test/java/cn/har01d/alist_tvbox/service/OfflineDownloadServiceTest.java`
- Modify: `src/test/java/cn/har01d/alist_tvbox/web/OfflineDownloadControllerTest.java`

- [ ] **Step 1: Add a failing config-save test for Thunder**

```java
@Test
void saveConfigShouldPersistAndSyncThunderTempDir() {
    DriverAccount account = new DriverAccount();
    account.setId(13);
    account.setType(DriverType.THUNDER);
    account.setFolder("/迅雷云盘/测试");
    when(driverAccountRepository.findById(13)).thenReturn(Optional.of(account));

    service.saveConfig(new OfflineDownloadService.ConfigRequest(true, "THUNDER", 13));

    verify(settingRepository).save(any(Setting.class));
    verify(aListLocalService).setThunderBrowserTempDir("/迅雷云盘/测试/alist-tvbox-offline");
}
```

- [ ] **Step 2: Add a failing download test for Thunder tool selection**

```java
@Test
void downloadShouldUseThunderBrowserTool() {
    DriverAccount account = new DriverAccount();
    account.setId(13);
    account.setType(DriverType.THUNDER);
    account.setFolder("/迅雷云盘/测试");
    when(settingRepository.findById("offline_download_config"))
            .thenReturn(Optional.of(new Setting("offline_download_config", "{\"enabled\":true,\"driverType\":\"THUNDER\",\"accountId\":13}")));
    when(driverAccountRepository.findById(13)).thenReturn(Optional.of(account));
    when(accountService.login()).thenReturn("Bearer test-token");
    when(restTemplate.postForObject(eq("/api/fs/add_offline_download"), any(), eq(Map.class)))
            .thenAnswer(invocation -> {
                HttpEntity<Map<String, Object>> entity = invocation.getArgument(1);
                assertEquals("ThunderBrowser", entity.getBody().get("tool"));
                return Map.of("code", 200, "data", Map.of("tasks", List.of(Map.of("id", "task-1"))));
            });
    when(restTemplate.postForObject(eq("/api/task/offline_download/info?tid=task-1"), any(), eq(Map.class)))
            .thenReturn(Map.of("code", 200, "data", Map.of("state", 2, "name", "任务名")));
    when(tvBoxService.getDetail("", "1$/迅雷云盘/测试/alist-tvbox-offline/任务名/~playlist")).thenReturn(new MovieList());

    service.download(new OfflineDownloadService.DownloadRequest("magnet:?xt=urn:btih:test"));
}
```

- [ ] **Step 3: Add a failing validation test for mismatched account type**

```java
@Test
void saveConfigShouldRejectMismatchedAccountType() {
    DriverAccount account = new DriverAccount();
    account.setId(12);
    account.setType(DriverType.PAN115);
    account.setFolder("/115云盘/测试");
    when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));

    assertThrows(BadRequestException.class, () ->
            service.saveConfig(new OfflineDownloadService.ConfigRequest(true, "THUNDER", 12)));
}
```

- [ ] **Step 4: Run the focused backend tests and confirm they fail for missing Thunder support**

Run: `./mvnw -Dtest=OfflineDownloadControllerTest,OfflineDownloadServiceTest test`

Expected: FAIL because `OfflineDownloadService` and `AListLocalService` only support `PAN115`.

### Task 2: Implement Multi-Driver Backend Support

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/OfflineDownloadService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/AListLocalService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/DriverAccountService.java`

- [ ] **Step 1: Add an AList helper for Thunder temp-dir sync**

```java
public SettingResponse setThunderBrowserTempDir(String tempDir) {
    HttpHeaders headers = new HttpHeaders();
    Site site = siteRepository.findById(1).orElseThrow();
    headers.set(HttpHeaders.AUTHORIZATION, site.getToken());
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<Map<String, Object>> entity = new HttpEntity<>(Map.of("temp_dir", tempDir), headers);
    return restTemplate.postForObject("/api/admin/setting/set_thunder_browser", entity, SettingResponse.class);
}
```

- [ ] **Step 2: Replace the single-driver constants in `OfflineDownloadService` with a driver mapping**

```java
private record DriverConf(DriverType accountType, String tool, Consumer<String> tempDirSync) {}
```

Or equivalent explicit helper methods so the service can resolve:

- `PAN115` -> `DriverType.PAN115`, `115 Cloud`, `aListLocalService::set115TempDir`
- `THUNDER` -> `DriverType.THUNDER`, `ThunderBrowser`, `aListLocalService::setThunderBrowserTempDir`

- [ ] **Step 3: Update config validation, account resolution, and temp-dir sync**

Implementation requirements:

- `driverType` must allow `PAN115` and `THUNDER`
- selected account type must match selected driver
- `saveConfig(...)` must call the right temp-dir sync method
- `syncSelectedAccountTempDir(...)` must re-sync for both drivers
- folder/path logic stays `folder + "/alist-tvbox-offline"`

- [ ] **Step 4: Update download task submission**

Implementation requirements:

- choose `tool` from selected driver type
- keep URL validation unchanged
- keep polling unchanged
- keep playlist resolution unchanged

- [ ] **Step 5: Update `DriverAccountService` folder-change resync**

Implementation requirement:

- when the configured offline-download account folder changes and its type is `PAN115` or `THUNDER`, re-run the driver-specific temp-dir sync

- [ ] **Step 6: Re-run the focused backend tests**

Run: `./mvnw -Dtest=OfflineDownloadControllerTest,OfflineDownloadServiceTest test`

Expected: PASS

### Task 3: Extend Frontend Config Filtering For Thunder

**Files:**
- Modify: `web-ui/src/views/DriverAccountView.vue`

- [ ] **Step 1: Expand the offline driver type union and selector**

```ts
type OfflineDriverType = 'PAN115' | 'THUNDER'
```

Render options:

```vue
<el-option label="115云盘" value="PAN115" />
<el-option label="迅雷云盘" value="THUNDER" />
```

- [ ] **Step 2: Filter account options by selected offline driver type**

```ts
const offlineAccounts = computed(() => accounts.value.filter((item) => item.type === offlineDownloadConfig.value.driverType))
```

- [ ] **Step 3: Clear incompatible selected account when driver type changes**

```ts
watch(() => offlineDownloadConfig.value.driverType, () => {
  const exists = offlineAccounts.value.some((item) => item.id === offlineDownloadConfig.value.accountId)
  if (!exists) {
    offlineDownloadConfig.value.accountId = null
  }
})
```

- [ ] **Step 4: Keep mount-folder display driven by filtered account list**

`offlineMountFolder` should resolve from the currently filtered account set and selected account id.

- [ ] **Step 5: Run frontend build**

Run: `npm --prefix web-ui run build`

Expected: PASS

### Task 4: Final Verification And Docs Update

**Files:**
- Modify: `api.md`

- [ ] **Step 1: Update offline-download API documentation wording**

Adjust descriptions from 115-only wording to shared cloud offline-download wording.

- [ ] **Step 2: Run focused backend tests again**

Run: `./mvnw -Dtest=OfflineDownloadControllerTest,OfflineDownloadServiceTest test`

Expected: PASS

- [ ] **Step 3: Run frontend build again**

Run: `npm --prefix web-ui run build`

Expected: PASS

- [ ] **Step 4: Run compile verification**

Run: `./mvnw -DskipTests compile`

Expected: BUILD SUCCESS
