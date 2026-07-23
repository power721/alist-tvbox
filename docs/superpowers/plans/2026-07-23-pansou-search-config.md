# PanSou 盘搜 Search Config Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose four upstream `/api/search` params (`conc`, `refresh`, `res`, `filter`) as global PanSou config, and move all PanSou config into a dedicated `盘搜` tab in `PlayConfig.vue`.

**Architecture:** Follow the existing flat `pan_sou_*` setting pattern. Backend: DTO → AppProperties → SettingService (load+update) → RemoteSearchService.search() wiring. Frontend: extract PanSou block into a new tab + add 4 controls sharing one `更新` button. Skip-when-empty preserves byte-identical upstream requests when nothing is configured.

**Tech Stack:** Java 21 / Spring Boot 4, Lombok, Jackson, JUnit 5 + MockRestServiceServer; Vue 3 + Element Plus, `node:test`.

**Spec:** `docs/superpowers/specs/2026-07-23-pansou-search-config-design.md`

## Global Constraints
- Backend: 4-space indent, Lombok, no API break, diffs small.
- Frontend: Vue 3 `<script setup>` + TS (file has `@ts-nocheck`), Element Plus, raw axios to `/api/settings`.
- `mvn clean package` must pass; `node --test src/components/PlayConfig.test.mjs` must pass.
- No new DTO packages (native-image reflect-config untouched).

## File Structure
- `src/main/java/cn/har01d/alist_tvbox/dto/pansou/SearchRequest.java` — add `conc`/`refresh` (field-level `@JsonInclude(NON_NULL)`).
- `src/test/java/cn/har01d/alist_tvbox/dto/pansou/SearchRequestTest.java` — **create**, DTO serialization contract.
- `src/main/java/cn/har01d/alist_tvbox/config/AppProperties.java` — add 5 fields.
- `src/main/java/cn/har01d/alist_tvbox/service/RemoteSearchService.java` — wire 4 params in `search()`.
- `src/test/java/cn/har01d/alist_tvbox/service/RemoteSearchServiceTest.java` — add wiring test.
- `src/main/java/cn/har01d/alist_tvbox/service/SettingService.java` — 5 keys in `setup()` + `update()`.
- `src/test/java/cn/har01d/alist_tvbox/service/SettingServicePanSouConfigTest.java` — **create**, round-trip.
- `web-ui/src/components/PlayConfig.vue` — refs/load/updatePanSouSearch + new tab + controls.
- `web-ui/src/components/PlayConfig.test.mjs` — add source-assertion tests.

---

### Task 1: SearchRequest DTO — add `conc`/`refresh` with NON_NULL

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/dto/pansou/SearchRequest.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/dto/pansou/SearchRequestTest.java` (create)

**Interfaces:**
- Produces: `SearchRequest.setConc(Integer)`, `setRefresh(Boolean)`; field-level `@JsonInclude(NON_NULL)` so null → omitted from JSON. Constructor `new SearchRequest(kw, channels, src)` unchanged.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/cn/har01d/alist_tvbox/dto/pansou/SearchRequestTest.java`:
```java
package cn.har01d.alist_tvbox.dto.pansou;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchRequestTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void omitsConcAndRefreshWhenNull() throws Exception {
        SearchRequest request = new SearchRequest("kw", List.of("c"), "all");
        String json = objectMapper.writeValueAsString(request);
        assertThat(json).doesNotContain("\"conc\"").doesNotContain("\"refresh\"");
        assertThat(json).contains("\"res\":\"merge\""); // res is non-null default, still present
    }

    @Test
    void includesConcAndRefreshWhenSet() throws Exception {
        SearchRequest request = new SearchRequest("kw", List.of("c"), "all");
        request.setConc(20);
        request.setRefresh(true);
        String json = objectMapper.writeValueAsString(request);
        assertThat(json).contains("\"conc\":20").contains("\"refresh\":true");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=SearchRequestTest`
Expected: COMPILE ERROR — `setConc`/`setRefresh` not found.

- [ ] **Step 3: Implement**

In `SearchRequest.java`, add the import after the existing Jackson import (line 3):
```java
import com.fasterxml.jackson.annotation.JsonInclude;
```
Add two fields (after line 18 `private Filter filter;`, before `private Map<String, Object> ext;`):
```java
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer conc;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean refresh;
```
(`@Data` generates getters/setters. `res`/`filter` already present.)

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=SearchRequestTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/dto/pansou/SearchRequest.java src/test/java/cn/har01d/alist_tvbox/dto/pansou/SearchRequestTest.java
git commit -m "feat(pansou): add conc/refresh to SearchRequest DTO with NON_NULL"
```

---

### Task 2: Wire the 4 params in RemoteSearchService.search()

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/config/AppProperties.java:47`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/RemoteSearchService.java:187-197`
- Test: `src/test/java/cn/har01d/alist_tvbox/service/RemoteSearchServiceTest.java`

**Interfaces:**
- Consumes (Task 1): `SearchRequest.setConc` / `setRefresh` / `setRes` / `setFilter`.
- Produces: `AppProperties.getPanSouConc()` (Integer), `getPanSouRefresh()` (Boolean), `getPanSouRes()` (String), `getPanSouFilterInclude()` / `getPanSouFilterExclude()` (`List<String>`).

- [ ] **Step 1: Add AppProperties fields (scaffolding)**

In `AppProperties.java`, after line 47 (`private int panSouLinkCheckMaxCount = 30;`), before line 48 (`private String systemId;`):
```java
    private Integer panSouConc;
    private Boolean panSouRefresh = false;
    private String panSouRes = "merge";
    private List<String> panSouFilterInclude;
    private List<String> panSouFilterExclude;
```

- [ ] **Step 2: Write the failing test**

In `RemoteSearchServiceTest.java`, add this test (after `searchUsesPanSouBuiltinChannelsWhenConfigured`, before `detailBackfillsSearchResultTitleForPanSou`):
```java
    @Test
    void searchSendsNewParamsWhenConfigured() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties appProperties = new AppProperties();
        appProperties.setPanSouUrl("http://pansou.example");
        appProperties.setPanSouChannels("pansou");
        appProperties.setPanSouSource("all");
        appProperties.setPanSouConc(20);
        appProperties.setPanSouRefresh(true);
        appProperties.setPanSouRes("results");
        appProperties.setPanSouFilterInclude(List.of("1080"));
        appProperties.setPanSouFilterExclude(List.of("枪版"));
        OfflineDownloadService offlineDownloadService = mock(OfflineDownloadService.class);
        when(offlineDownloadService.getConfig()).thenReturn(new OfflineDownloadConfigDto(false, "", null, ""));

        RemoteSearchService service = new RemoteSearchService(
                appProperties, restTemplateBuilder(restTemplate), objectMapper,
                mock(TelegramChannelRepository.class), mock(ShareService.class),
                mock(TvBoxService.class), offlineDownloadService);

        server.expect(once(), requestTo("http://pansou.example/api/health"))
                .andRespond(withSuccess("""
                        {"channels":["builtin-a"],"channels_count":1,"auth_enabled":false}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://pansou.example/api/search"))
                .andExpect(content().json("""
                        {"kw":"movie","src":"all","conc":20,"refresh":true,"res":"results",
                         "filter":{"include":["1080"],"exclude":["枪版"]}}
                        """))
                .andRespond(withSuccess("""
                        {"code":0,"message":"ok","data":{"total":0,"results":[],"merged_by_type":{}}}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        service.search("movie", List.of());

        server.verify();
    }
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -q test -Dtest=RemoteSearchServiceTest#searchSendsNewParamsWhenConfigured`
Expected: FAIL — upstream request missing `conc`/`refresh`/`res:"results"`/`filter`.

- [ ] **Step 4: Implement wiring**

In `RemoteSearchService.java` `search()` (line 187). Replace the commented line 192:
```java
//            request.setFilter(new SearchRequest.Filter(List.of(keyword), List.of()));
            request.setCloudTypes(getPanSouCloudTypes());
```
with the cloud-types line kept, then after the plugins block (after line 197 `request.setPlugins(appProperties.getPanSouPlugins());` closing `}`), insert before `String url = ...` (line 198):
```java
        if (appProperties.getPanSouConc() != null && appProperties.getPanSouConc() > 0) {
            request.setConc(appProperties.getPanSouConc());
        }
        if (Boolean.TRUE.equals(appProperties.getPanSouRefresh())) {
            request.setRefresh(true);
        }
        request.setRes(StringUtils.defaultIfBlank(appProperties.getPanSouRes(), "merge"));
        List<String> filterInclude = appProperties.getPanSouFilterInclude();
        List<String> filterExclude = appProperties.getPanSouFilterExclude();
        if (!CollectionUtils.isEmpty(filterInclude) || !CollectionUtils.isEmpty(filterExclude)) {
            request.setFilter(new SearchRequest.Filter(
                    CollectionUtils.isEmpty(filterInclude) ? List.of() : filterInclude,
                    CollectionUtils.isEmpty(filterExclude) ? List.of() : filterExclude));
        }
```
`StringUtils` (line 22) and `CollectionUtils` (line 30) already imported. `List` (line 44) imported.

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -q test -Dtest=RemoteSearchServiceTest`
Expected: PASS — new test passes AND existing `searchUsesPanSouBuiltinChannelsWhenConfigured` still passes (backward-compat: nothing configured → `conc`/`refresh` omitted, `res:"merge"`, `filter:null` unchanged).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/config/AppProperties.java src/main/java/cn/har01d/alist_tvbox/service/RemoteSearchService.java src/test/java/cn/har01d/alist_tvbox/service/RemoteSearchServiceTest.java
git commit -m "feat(pansou): wire conc/refresh/res/filter into search request"
```

---

### Task 3: Persist the 5 settings in SettingService

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/SettingService.java:115` (load) and `:506` (update)
- Test: `src/test/java/cn/har01d/alist_tvbox/service/SettingServicePanSouConfigTest.java` (create)

**Interfaces:**
- Consumes (Task 2): `AppProperties` setters for the 5 fields.
- Produces: `Setting` keys `pan_sou_conc`, `pan_sou_refresh`, `pan_sou_res`, `pan_sou_filter_include`, `pan_sou_filter_exclude` (string values; include/exclude comma-separated).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/cn/har01d/alist_tvbox/service/SettingServicePanSouConfigTest.java`:
```java
package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.Setting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.har01d.alist_tvbox.auth.TokenFilter;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.service.backup.DatabaseBackupService;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettingServicePanSouConfigTest {
    @Mock JdbcTemplate jdbcTemplate;
    @Mock Environment environment;
    @Mock TmdbService tmdbService;
    @Mock AListLocalService aListLocalService;
    @Mock TokenFilter tokenFilter;
    @Mock SettingRepository settingRepository;
    @Mock DriverAccountRepository driverAccountRepository;
    @Mock ObjectMapper objectMapper;
    @Mock GitHubProxyService gitHubProxyService;
    @Mock DatabaseBackupService databaseBackupService;

    private SettingService service;
    private final AppProperties appProperties = new AppProperties();
    private final Map<String, String> store = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = new SettingService(jdbcTemplate, environment, appProperties, tmdbService,
                aListLocalService, tokenFilter, settingRepository, driverAccountRepository,
                objectMapper, gitHubProxyService, databaseBackupService);
        lenient().when(settingRepository.findById(any())).thenAnswer(inv -> {
            String k = inv.getArgument(0);
            return store.containsKey(k) ? Optional.of(new Setting(k, store.get(k))) : Optional.empty();
        });
    }

    @Test
    void updateParsesAllFiveKeys() {
        when(settingRepository.save(any(Setting.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(new Setting("pan_sou_conc", "20"));
        assertEquals(20, appProperties.getPanSouConc());
        service.update(new Setting("pan_sou_refresh", "true"));
        assertTrue(appProperties.getPanSouRefresh());
        service.update(new Setting("pan_sou_res", "results"));
        assertEquals("results", appProperties.getPanSouRes());
        service.update(new Setting("pan_sou_filter_include", "1080, 4K"));
        assertEquals(java.util.List.of("1080", "4K"), appProperties.getPanSouFilterInclude());
        service.update(new Setting("pan_sou_filter_exclude", "枪版,广告"));
        assertEquals(java.util.List.of("枪版", "广告"), appProperties.getPanSouFilterExclude());
    }

    @Test
    void blankConcBecomesNull() {
        service.update(new Setting("pan_sou_conc", ""));
        assertNull(appProperties.getPanSouConc());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=SettingServicePanSouConfigTest`
Expected: FAIL — `update()` doesn't handle the new keys; assertions on parsed values fail.

- [ ] **Step 3: Implement load (setup() @PostConstruct)**

In `SettingService.java`, after line 115 (`setPanSouLinkCheckMaxCount`), before line 116 (`setTgSortField`), add:
```java
        appProperties.setPanSouConc(settingRepository.findById("pan_sou_conc").map(Setting::getValue)
                .filter(StringUtils::isNotBlank).map(v -> Integer.parseInt(v.trim())).orElse(null));
        appProperties.setPanSouRefresh(settingRepository.findById("pan_sou_refresh").map(Setting::getValue).orElse("").equals("true"));
        appProperties.setPanSouRes(settingRepository.findById("pan_sou_res").map(Setting::getValue).filter(StringUtils::isNotBlank).orElse("merge"));
        appProperties.setPanSouFilterInclude(parseList(settingRepository.findById("pan_sou_filter_include").map(Setting::getValue).orElse("")));
        appProperties.setPanSouFilterExclude(parseList(settingRepository.findById("pan_sou_filter_exclude").map(Setting::getValue).orElse("")));
```
Add a private helper near `normalizePanSouChannels` (e.g. after it):
```java
    private List<String> parseList(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(StringUtils::isNotBlank).toList();
    }
```
(`StringUtils`, `Arrays`, `List` already imported in SettingService.)

- [ ] **Step 4: Implement update()**

In `SettingService.java` `update()`, after the `panSouPlugins` block (lines 504-506), before `tg_sort_field` (line 507), add:
```java
        if ("pan_sou_conc".equals(setting.getName())) {
            if (StringUtils.isBlank(setting.getValue())) {
                appProperties.setPanSouConc(null);
            } else {
                appProperties.setPanSouConc(Math.max(0, Integer.parseInt(setting.getValue().trim())));
            }
        }
        if ("pan_sou_refresh".equals(setting.getName())) {
            appProperties.setPanSouRefresh("true".equals(setting.getValue()));
        }
        if ("pan_sou_res".equals(setting.getName())) {
            appProperties.setPanSouRes(StringUtils.isBlank(setting.getValue()) ? "merge" : setting.getValue());
        }
        if ("pan_sou_filter_include".equals(setting.getName())) {
            appProperties.setPanSouFilterInclude(parseList(setting.getValue()));
        }
        if ("pan_sou_filter_exclude".equals(setting.getName())) {
            appProperties.setPanSouFilterExclude(parseList(setting.getValue()));
        }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -q test -Dtest=SettingServicePanSouConfigTest,SettingServiceBasicAuthTest,RemoteSearchServiceTest`
Expected: PASS (new + existing green).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/SettingService.java src/test/java/cn/har01d/alist_tvbox/service/SettingServicePanSouConfigTest.java
git commit -m "feat(pansou): persist conc/refresh/res/filter settings"
```

---

### Task 4: Frontend — new `盘搜` tab + 4 controls

**Files:**
- Modify: `web-ui/src/components/PlayConfig.vue` (refs ~42, load ~431, fn ~256, template ~469-556)
- Test: `web-ui/src/components/PlayConfig.test.mjs`

**Interfaces:**
- Consumes (Task 3): `/api/settings` keys `pan_sou_conc`, `pan_sou_refresh`, `pan_sou_res`, `pan_sou_filter_include`, `pan_sou_filter_exclude`.

- [ ] **Step 1: Add refs**

In `PlayConfig.vue`, after line 42 (`const panSouLinkCheckMaxCount = ref(30)`), before line 43 (`const plugins = ref([])`), add:
```js
const panSouConc = ref<number | null>(null)
const panSouRefresh = ref(false)
const panSouRes = ref('merge')
const panSouFilterInclude = ref('')
const panSouFilterExclude = ref('')
const resOptions = [
  {label: '聚合', value: 'merge'},
  {label: '仅结果', value: 'results'},
  {label: '全部', value: 'all'},
]
```

- [ ] **Step 2: Add update function**

After `updatePlugins` (ends line 256), before `updateOrder` (line 258), add:
```js
const updatePanSouSearch = () => {
  axios.post('/api/settings', {name: 'pan_sou_conc', value: panSouConc.value || ''}).then()
  axios.post('/api/settings', {name: 'pan_sou_refresh', value: panSouRefresh.value}).then()
  axios.post('/api/settings', {name: 'pan_sou_res', value: panSouRes.value}).then()
  axios.post('/api/settings', {name: 'pan_sou_filter_include', value: panSouFilterInclude.value}).then()
  axios.post('/api/settings', {name: 'pan_sou_filter_exclude', value: panSouFilterExclude.value}).then(() => {
    ElMessage.success('更新成功')
  })
}
```

- [ ] **Step 3: Extend load**

In the `onMounted` `axios.get('/api/settings')` handler, after line 431 (`panSouLinkCheckMaxCount.value = ...`), before line 432 (`tgSortField.value = ...`), add:
```js
    panSouConc.value = data.pan_sou_conc ? +data.pan_sou_conc : null
    panSouRefresh.value = data.pan_sou_refresh === 'true'
    panSouRes.value = data.pan_sou_res || 'merge'
    panSouFilterInclude.value = data.pan_sou_filter_include || ''
    panSouFilterExclude.value = data.pan_sou_filter_exclude || ''
```

- [ ] **Step 4: Extract PanSou block into a new tab**

In `<template>`, **delete** the PanSou form-items from the `基本配置` tab — lines 469 through 526 (from `<el-form-item label="PanSou地址">` through the `updatePanSouLinkCheck` `</el-form-item>` at 524-526). Keep lines 451-468 (timeout, TG-Search) and 527-555 (网盘顺序, 排序字段, 默认壁纸) in `基本配置`.

**Insert** a new tab pane immediately **after** the `基本配置` `</el-tab-pane>` (line 556) and before `<el-tab-pane label="频道管理" name="second">` (line 557):
```html
    <el-tab-pane label="盘搜" name="pansou">
      <el-form label-width="140">
        <el-form-item label="PanSou地址">
          <el-input v-model="panSouUrl" placeholder="http://IP:8888"/>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="updatePanSouUrl">更新</el-button>
          <a class="hint" target="_blank" title="部署纯后端" href="https://github.com/fish2018/pansou">部署</a>
          <a class="hint" target="_blank" title="部署前端后端" href="https://github.com/fish2018/pansou-web">部署</a>
        </el-form-item>
        <el-form-item label="PanSou用户名" v-if="panSouUrl && panSouAuthEnabled">
          <el-input v-model="panSouUsername"/>
        </el-form-item>
        <el-form-item label="PanSou密码" v-if="panSouUrl && panSouAuthEnabled">
          <el-input v-model="panSouPassword" type="password" show-password/>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="updatePanSouAuth" v-if="panSouUrl && panSouAuthEnabled">更新</el-button>
        </el-form-item>
        <el-form-item label="PanSou数据源" v-if="panSouUrl">
          <el-radio-group v-model="panSouSource" class="ml-4">
            <el-radio size="large" v-for="item in sources" :key="item.value" :value="item.value">
              {{ item.label }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="updatePanSouSource" v-if="panSouUrl">更新</el-button>
        </el-form-item>
        <el-form-item label="PanSou频道列表" v-if="panSouUrl">
          <el-radio-group v-model="panSouChannels" class="ml-4">
            <el-radio size="large" v-for="item in panSouChannelLists" :key="item.value" :value="item.value">
              {{ item.label }}({{ getPanSouChannelCount(item.value) }})
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="updatePanSouChannels" v-if="panSouUrl">更新</el-button>
        </el-form-item>
        <el-form-item label="PanSou插件" v-if="panSouUrl">
          <el-checkbox-group v-model="panSouPlugins">
            <el-checkbox v-for="item in plugins" :label="item" :value="item" :key="item"/>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item v-if="panSouUrl">
          <el-button type="primary" @click="updatePlugins">更新</el-button>
          <span class="hint">已启用插件 {{ panSouPluginCount }} 个</span>
          <span class="hint">留空使用全部插件搜索</span>
        </el-form-item>
        <el-form-item label="链接检测" v-if="panSouUrl">
          <el-switch v-model="panSouLinkCheckEnabled"/>
          <span class="hint">自动检查盘搜搜索结果的有效性</span>
        </el-form-item>
        <el-form-item label="检测数量上限" v-if="panSouUrl">
          <el-input-number v-model="panSouLinkCheckMaxCount" :min="0" :max="500"/>
          <span class="hint">仅当网盘结果数量小于等于该值时检查，磁力和ED2K不计算数量</span>
        </el-form-item>
        <el-form-item v-if="panSouUrl">
          <el-button type="primary" @click="updatePanSouLinkCheck">更新</el-button>
        </el-form-item>
        <el-form-item label="并发数" v-if="panSouUrl">
          <el-input-number v-model="panSouConc" :min="0" placeholder="自动"/>
          <span class="hint">留空或 0 使用上游自动并发（频道数+插件数+10）</span>
        </el-form-item>
        <el-form-item label="强制刷新" v-if="panSouUrl">
          <el-switch v-model="panSouRefresh"/>
          <span class="hint">跳过缓存，获取最新数据</span>
        </el-form-item>
        <el-form-item label="结果类型" v-if="panSouUrl">
          <el-select v-model="panSouRes" style="width: 160px">
            <el-option v-for="item in resOptions" :key="item.value" :label="item.label" :value="item.value"/>
          </el-select>
        </el-form-item>
        <el-form-item label="包含词" v-if="panSouUrl">
          <el-input v-model="panSouFilterInclude" placeholder="多个用逗号分隔，如 1080,4K"/>
        </el-form-item>
        <el-form-item label="排除词" v-if="panSouUrl">
          <el-input v-model="panSouFilterExclude" placeholder="多个用逗号分隔，如 枪版,广告"/>
        </el-form-item>
        <el-form-item v-if="panSouUrl">
          <el-button type="primary" @click="updatePanSouSearch">更新</el-button>
          <span class="hint">并发数/强制刷新/结果类型/包含词/排除词</span>
        </el-form-item>
      </el-form>
    </el-tab-pane>
```

- [ ] **Step 5: Write the failing test**

In `PlayConfig.test.mjs`, append:
```js
test('play config exposes PanSou search behavior controls', () => {
  assert.equal(componentSource.includes(`const panSouConc = ref<number | null>(null)`), true)
  assert.equal(componentSource.includes(`const panSouRefresh = ref(false)`), true)
  assert.equal(componentSource.includes(`const panSouRes = ref('merge')`), true)
  assert.equal(componentSource.includes(`{label: '聚合', value: 'merge'}`), true)
  assert.equal(componentSource.includes(`const updatePanSouSearch = () => {`), true)
  assert.equal(componentSource.includes(`{name: 'pan_sou_conc', value: panSouConc.value || ''}`), true)
  assert.equal(componentSource.includes(`panSouRes.value = data.pan_sou_res || 'merge'`), true)
})

test('play config isolates PanSou config in its own tab', () => {
  assert.equal(componentSource.includes(`<el-tab-pane label="盘搜" name="pansou">`), true)
  // PanSou address moved out of the basic tab into the dedicated tab
  assert.equal(componentSource.includes(`label="PanSou地址"`), true)
})
```

- [ ] **Step 6: Run test to verify it passes**

Run: `cd web-ui && node --test src/components/PlayConfig.test.mjs`
Expected: PASS (all, including new tests).

- [ ] **Step 7: Lint + type-check**

Run: `cd web-ui && npm run lint && npm run type-check`
Expected: no errors (`@ts-nocheck` is set; lint should pass — fix any style nits it reports).

- [ ] **Step 8: Commit**

```bash
git add web-ui/src/components/PlayConfig.vue web-ui/src/components/PlayConfig.test.mjs
git commit -m "feat(pansou): dedicated 盘搜 tab + conc/refresh/res/filter controls"
```

---

### Task 5: Full build verification

- [ ] **Step 1: Backend build + all tests**

Run: `mvn clean package`
Expected: BUILD SUCCESS, all tests green.

- [ ] **Step 2: Frontend build**

Run: `cd web-ui && npm run build`
Expected: success.

- [ ] **Step 3 (if all green): open PR against `master`** — branch `feat/pansou-search-config`.

---

## Self-Review notes
- **Spec coverage:** conc/refresh → DTO (T1) + wiring (T2); res/filter → wiring (T2); persistence (T3); tab + controls (T4). All 4 params + tab extraction covered.
- **Backward-compat guard:** existing `RemoteSearchServiceTest#searchUsesPanSouBuiltinChannelsWhenConfigured` already asserts `res:"merge"` and (leniently) tolerates the unchanged `filter:null`/`ext` — still green after T2. New `SearchRequestTest#omitsConcAndRefreshWhenNull` locks the NON_NULL contract.
- **Type consistency:** `panSouConc` Integer everywhere (DTO `conc`, AppProperties, SettingService parse). `panSouRefresh` Boolean. `panSouRes` String. include/exclude `List<String>` with blank-trimmed comma-split (`parseList`) so `[""]` can't leak into the upstream filter.
- **Out of scope (per spec):** `ext`, explicit `cloud_types`, subscription override, per-query UI — untouched.
