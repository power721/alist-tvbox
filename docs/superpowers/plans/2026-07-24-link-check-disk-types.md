# 链接检测 — 按网盘类型开启 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users choose which of the 9 supported disk types get link-checked in PanSou search results, instead of the current all-or-nothing switch.

**Architecture:** Add a `pan_sou_link_check_types` setting (comma-list ↔ `List<String>`, same convention as `tg_drivers`/`panSouPlugins`). The master on/off switch stays; the new list further restricts which cloud types `filterInvalidPanSouLinks` actually sends to the external `/api/check/links`. Empty list = all 9 supported types (backward compatible).

**Tech Stack:** Java 21 + Spring Boot 4, Lombok, JUnit 5 + Mockito + Spring `MockRestServiceServer`; Vue 3 + TS + Element Plus.

## Global Constraints

- Lombok `@Data` for getters/setters — no hand-written accessors.
- New setting key exactly `pan_sou_link_check_types`; stored comma-separated; `parseList` parses it (blank → `null`).
- Supported 9 cloud types: `baidu aliyun quark tianyi uc mobile 115 xunlei 123` (NOT pikpak/guangya — those stop being checked).
- No DB migration (settings are a key/value table). No API-breaking change. Keep diffs small.
- Conventional-commit messages, `feat(pansou): ...`. End every commit message with `Co-Authored-By: Claude <noreply@anthropic.com>`.
- Project rule: feature branch, no direct `master` commits.

Spec: `docs/superpowers/specs/2026-07-24-link-check-disk-types-design.md`

---

## File Structure

- `src/main/java/cn/har01d/alist_tvbox/config/AppProperties.java` — add `panSouLinkCheckTypes` field.
- `src/main/java/cn/har01d/alist_tvbox/service/SettingService.java` — load + update the new key.
- `src/main/java/cn/har01d/alist_tvbox/service/sync/SyncService.java` — add key to backup whitelist.
- `src/main/java/cn/har01d/alist_tvbox/service/RemoteSearchService.java` — `PAN_SOU_CHECK_TYPES` constant + `getEnabledLinkCheckTypes()` + filter in `filterInvalidPanSouLinks`.
- `src/test/java/cn/har01d/alist_tvbox/service/SettingServicePanSouConfigTest.java` — parse test.
- `src/test/java/cn/har01d/alist_tvbox/service/RemoteSearchServiceTest.java` — filter behavior test.
- `web-ui/src/components/PlayConfig.vue` — ref + load + persist + checkbox-group UI.

---

### Task 0: Feature branch + commit spec

**Files:** none (git only)

- [ ] **Step 1: Create branch (carries the already-written spec doc in the working tree)**

Run:
```bash
git checkout -b feat/link-check-disk-types
```
Expected: `Switched to a new branch 'feat/link-check-disk-types'`.

- [ ] **Step 2: Commit the design spec**

Run:
```bash
git add docs/superpowers/specs/2026-07-24-link-check-disk-types-design.md docs/superpowers/plans/2026-07-24-link-check-disk-types.md
git commit -m "docs(pansou): spec+plan for per-disk-type link check

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 1: Backend config plumbing (AppProperties + SettingService + SyncService)

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/config/AppProperties.java:47`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/SettingService.java:115` and `:516`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/sync/SyncService.java:43`
- Test: `src/test/java/cn/har01d/alist_tvbox/service/SettingServicePanSouConfigTest.java`

**Interfaces:**
- Produces: `AppProperties.getPanSouLinkCheckTypes()` → `List<String>` (null when unset). Consumed by Task 2.

- [ ] **Step 1: Write the failing test**

Append to `SettingServicePanSouConfigTest.java` (inside the class, before the closing `}`):

```java
    @Test
    void linkCheckTypesParsesCsv() {
        service.update(new Setting("pan_sou_link_check_types", "quark, baidu, 115"));
        assertEquals(java.util.List.of("quark", "baidu", "115"), appProperties.getPanSouLinkCheckTypes());
    }

    @Test
    void blankLinkCheckTypesBecomesNull() {
        service.update(new Setting("pan_sou_link_check_types", ""));
        assertNull(appProperties.getPanSouLinkCheckTypes());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=SettingServicePanSouConfigTest`
Expected: FAIL — `getPanSouLinkCheckTypes()` unresolved / setting ignored.

- [ ] **Step 3: Add the AppProperties field**

In `AppProperties.java`, immediately after line 47 (`private int panSouLinkCheckMaxCount = 30;`), add:

```java
    private List<String> panSouLinkCheckTypes;
```
(`List` is already imported in this file — confirm `import java.util.List;` exists.)

- [ ] **Step 4: Load the setting on startup**

In `SettingService.java`, immediately after line 115 (the `panSouLinkCheckMaxCount` load), add:

```java
        appProperties.setPanSouLinkCheckTypes(parseList(settingRepository.findById("pan_sou_link_check_types").map(Setting::getValue).orElse("")));
```

- [ ] **Step 5: Handle the setting on update**

In `SettingService.java`, immediately after the `pan_sou_link_check_max_count` update block (ends line 516), add:

```java
        if ("pan_sou_link_check_types".equals(setting.getName())) {
            appProperties.setPanSouLinkCheckTypes(parseList(setting.getValue()));
        }
```

- [ ] **Step 6: Add to backup whitelist**

In `SyncService.java`, change line 43 from:

```java
        "pan_sou_link_check_enabled", "pan_sou_link_check_max_count", "panSouPlugins",
```
to:

```java
        "pan_sou_link_check_enabled", "pan_sou_link_check_max_count", "pan_sou_link_check_types", "panSouPlugins",
```

- [ ] **Step 7: Run test to verify it passes**

Run: `mvn -q test -Dtest=SettingServicePanSouConfigTest`
Expected: PASS (all tests in the class).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/config/AppProperties.java \
        src/main/java/cn/har01d/alist_tvbox/service/SettingService.java \
        src/main/java/cn/har01d/alist_tvbox/service/sync/SyncService.java \
        src/test/java/cn/har01d/alist_tvbox/service/SettingServicePanSouConfigTest.java
git commit -m "feat(pansou): add pan_sou_link_check_types setting plumbing

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: Backend filter logic (RemoteSearchService)

**Why a pure unit test, not an HTTP test:** `filterInvalidPanSouLinks` fans out to `checkPanSouLinks` via `CompletableFuture.supplyAsync` and swallows failures in a `try/catch`, so a `MockRestServiceServer` test cannot reliably prove an unselected type was *not* checked (unexpected-request errors get swallowed; Mockito verification across ForkJoinPool threads is thread-local and unreliable). Instead, extract the *selection* of which messages to check into a small package-private method and test it directly — synchronous, no HTTP, no async.

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/RemoteSearchService.java` (import `:46`, constant near `:54`, helper, new `selectCheckable` method, call site in `filterInvalidPanSouLinks` `:256-259`)
- Test: `src/test/java/cn/har01d/alist_tvbox/service/RemoteSearchServiceTest.java`

**Interfaces:**
- Consumes: `AppProperties.getPanSouLinkCheckTypes()` from Task 1.
- Produces: `List<Message> selectCheckable(List<Message> messages)` (package-private) — the messages that are eligible for link checking.

- [ ] **Step 1: Write the failing tests**

Add import at top of `RemoteSearchServiceTest.java`:

```java
import cn.har01d.alist_tvbox.dto.tg.Message;
```

Add these tests (and a helper) inside the class, before the private `restTemplateBuilder` helper:

```java
    @Test
    void selectCheckableHonorsSelectedDiskTypes() {
        AppProperties appProperties = new AppProperties();
        appProperties.setPanSouLinkCheckTypes(List.of("quark"));
        RemoteSearchService service = new RemoteSearchService(
                appProperties, restTemplateBuilder(new RestTemplate()), objectMapper,
                mock(TelegramChannelRepository.class), mock(ShareService.class),
                mock(TvBoxService.class), mock(OfflineDownloadService.class));

        // Only quark selected -> baidu (unselected) and pikpak (not in supported 9) are NOT checkable.
        List<Message> checkable = service.selectCheckable(List.of(
                message("5", "https://pan.quark.cn/s/q1"),
                message("10", "https://pan.baidu.com/s/b1"),
                message("1", "https://www.pikpak.com/s/p1")));

        org.assertj.core.api.Assertions.assertThat(checkable).extracting(Message::getLink)
                .containsExactly("https://pan.quark.cn/s/q1");
    }

    @Test
    void selectCheckableDefaultsToAllSupportedWhenUnset() {
        AppProperties appProperties = new AppProperties(); // panSouLinkCheckTypes unset -> all 9
        RemoteSearchService service = new RemoteSearchService(
                appProperties, restTemplateBuilder(new RestTemplate()), objectMapper,
                mock(TelegramChannelRepository.class), mock(ShareService.class),
                mock(TvBoxService.class), mock(OfflineDownloadService.class));

        // Unset -> all supported types checkable; pikpak (not supported) still excluded.
        List<Message> checkable = service.selectCheckable(List.of(
                message("5", "https://pan.quark.cn/s/q1"),
                message("10", "https://pan.baidu.com/s/b1"),
                message("1", "https://www.pikpak.com/s/p1")));

        org.assertj.core.api.Assertions.assertThat(checkable).extracting(Message::getLink)
                .containsExactlyInAnyOrder("https://pan.quark.cn/s/q1", "https://pan.baidu.com/s/b1");
    }

    private static Message message(String type, String link) {
        Message m = new Message();
        m.setType(type);
        m.setLink(link);
        return m;
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -Dtest=RemoteSearchServiceTest#selectCheckableHonorsSelectedDiskTypes+selectCheckableDefaultsToAllSupportedWhenUnset`
Expected: FAIL — `selectCheckable` does not exist (compile error).

- [ ] **Step 3: Add the `Set` import**

In `RemoteSearchService.java`, after line 46 (`import java.util.Map;`), add:

```java
import java.util.Set;
```

- [ ] **Step 4: Add the supported-types constant**

In `RemoteSearchService.java`, immediately after the `CHECK_STATE_UNCERTAIN` constant (line 54), add:

```java
    private static final Set<String> PAN_SOU_CHECK_TYPES = Set.of(
            "baidu", "aliyun", "quark", "tianyi", "uc", "mobile", "115", "xunlei", "123");
```

- [ ] **Step 5: Add the enabled-set helper**

Add this private method near the other private helpers (e.g. right after `getPanSouCloudType`, ~line 560):

```java
    private Set<String> getEnabledLinkCheckTypes() {
        List<String> configured = appProperties.getPanSouLinkCheckTypes();
        if (CollectionUtils.isEmpty(configured)) {
            return PAN_SOU_CHECK_TYPES;
        }
        return configured.stream()
                .filter(PAN_SOU_CHECK_TYPES::contains)
                .collect(Collectors.toSet());
    }
```

- [ ] **Step 6: Extract `selectCheckable` and use it**

Add this package-private method near `filterInvalidPanSouLinks`:

```java
    List<Message> selectCheckable(List<Message> messages) {
        Set<String> enabledLinkCheckTypes = getEnabledLinkCheckTypes();
        return messages.stream()
                .filter(message -> !isOfflineDownloadType(message.getType()))
                .filter(message -> StringUtils.isNotBlank(getPanSouCloudType(message.getType())))
                .filter(message -> enabledLinkCheckTypes.contains(getPanSouCloudType(message.getType())))
                .toList();
    }
```

Then in `filterInvalidPanSouLinks`, replace the inline `checkable` block (lines 256-259) — from:

```java
        List<Message> checkable = messages.stream()
                .filter(message -> !isOfflineDownloadType(message.getType()))
                .filter(message -> StringUtils.isNotBlank(getPanSouCloudType(message.getType())))
                .toList();
```
with:

```java
        List<Message> checkable = selectCheckable(messages);
```

(Behavior is identical; the logic simply moves into the testable method, now with the enabled-type filter applied.)

- [ ] **Step 7: Run tests to verify they pass**

Run: `mvn -q test -Dtest=RemoteSearchServiceTest`
Expected: PASS (all tests, including the two new ones).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/RemoteSearchService.java \
        src/test/java/cn/har01d/alist_tvbox/service/RemoteSearchServiceTest.java
git commit -m "feat(pansou): restrict link check to selected disk types

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 3: Frontend checkbox-group (PlayConfig.vue)

**Files:**
- Modify: `web-ui/src/components/PlayConfig.vue` (options const ~`:42`, ref `:42`, load `:451`, persist `:237`, template `:497`)

**Interfaces:**
- Consumes: `pan_sou_link_check_types` from the `/api/settings` dump (already auto-covered by `SettingService` `toMap`).
- Produces: POSTs `pan_sou_link_check_types=<csv>` on update.

- [ ] **Step 1: Add the options constant + ref**

In `PlayConfig.vue`, after line 42 (`const panSouLinkCheckMaxCount = ref(30)`), add:

```ts
const panSouLinkCheckTypes = ref<string[]>([])
const panSouLinkCheckTypeOptions = [
  {label: '百度网盘', value: 'baidu'},
  {label: '阿里云盘', value: 'aliyun'},
  {label: '夸克网盘', value: 'quark'},
  {label: '天翼云盘', value: 'tianyi'},
  {label: 'UC网盘', value: 'uc'},
  {label: '移动云盘', value: 'mobile'},
  {label: '115网盘', value: '115'},
  {label: '迅雷网盘', value: 'xunlei'},
  {label: '123网盘', value: '123'},
]
```

- [ ] **Step 2: Load the value from settings**

After line 451 (`panSouLinkCheckMaxCount.value = +(data.pan_sou_link_check_max_count || 30)`), add:

```ts
    panSouLinkCheckTypes.value = data.pan_sou_link_check_types ? data.pan_sou_link_check_types.split(',') : []
```

- [ ] **Step 3: Persist the value on update**

In `updatePanSouLinkCheck` (line 236), change the body to insert the types POST between the enabled and max_count posts:

```ts
const updatePanSouLinkCheck = () => {
  axios.post('/api/settings', {name: 'pan_sou_link_check_enabled', value: panSouLinkCheckEnabled.value}).then()
  axios.post('/api/settings', {name: 'pan_sou_link_check_types', value: panSouLinkCheckTypes.value.join(',')}).then()
  axios.post('/api/settings', {name: 'pan_sou_link_check_max_count', value: panSouLinkCheckMaxCount.value}).then(() => {
    ElMessage.success('更新成功')
  })
}
```

- [ ] **Step 4: Add the checkbox-group UI**

After the 链接检测 switch `<el-form-item>` (ends line 497) and before the 检测数量上限 item (line 498), insert:

```html
        <el-form-item label="检测网盘类型" v-if="panSouUrl">
          <el-checkbox-group v-model="panSouLinkCheckTypes">
            <el-checkbox v-for="t in panSouLinkCheckTypeOptions" :key="t.value" :label="t.label" :value="t.value"/>
          </el-checkbox-group>
          <span class="hint">留空=检测全部9种</span>
        </el-form-item>
```

- [ ] **Step 5: Verify build + lint**

Run:
```bash
cd web-ui && npm run build && npm run lint
```
Expected: build succeeds, lint passes (no errors).

- [ ] **Step 6: Manual smoke check**

Run dev server, open 播放配置 → 基础配置 → 链接检测: toggle types, click 更新, refresh page, confirm selection persists.

- [ ] **Step 7: Commit**

```bash
git add web-ui/src/components/PlayConfig.vue
git commit -m "feat(pansou): UI to select which disk types get link-checked

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Verification (whole feature)

- [ ] `mvn -q test` — all green.
- [ ] `cd web-ui && npm run build` — succeeds.
- [ ] End-to-end: search a term, set link-check to `quark` only, confirm only quark links get a validity state.
