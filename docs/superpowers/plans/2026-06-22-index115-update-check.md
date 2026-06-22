# 115 分享索引更新检查 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a read-only update check for the 115 share index on the Advanced Settings page — compares local `index115.share_code` vs remote `d.har01d.cn/115.version.txt` shareCode and shows 有更新/已是最新/检查失败 + version strings, without triggering a download.

**Architecture:** New `GET /api/index115/check` endpoint backed by `Index115Service.check()`, returning an `Index115CheckResult` record. Frontend `ConfigView.vue` calls it on mount when a 115 account exists, renders an `el-tag` badge + a gray version span next to the existing 下载 button.

**Tech Stack:** Java 21 / Spring Boot 3, Mockito/JUnit 5, Vue 3 + Element Plus + TypeScript.

---

## File Structure

- **Create** `src/main/java/cn/har01d/alist_tvbox/dto/Index115CheckResult.java` — record DTO returned by the check endpoint.
- **Modify** `src/main/java/cn/har01d/alist_tvbox/service/Index115Service.java` — add `check()` method (read-only version compare).
- **Modify** `src/main/java/cn/har01d/alist_tvbox/web/Index115Controller.java` — add `GET /check` mapping.
- **Modify** `src/test/java/cn/har01d/alist_tvbox/service/Index115ServiceTest.java` — 5 Mockito branches for `check()`.
- **Modify** `web-ui/src/views/ConfigView.vue` — reactive state, onMounted chain, UI badge + version span.
- **Regenerate** `src/main/resources/META-INF/native-image/reflect-config.json` — pick up the new record (auto via Main.java dto scan).

---

### Task 1: Create Index115CheckResult record

**Files:**
- Create: `src/main/java/cn/har01d/alist_tvbox/dto/Index115CheckResult.java`

- [ ] **Step 1: Create the record**

```java
package cn.har01d.alist_tvbox.dto;

public record Index115CheckResult(boolean hasAccount, boolean hasUpdate, String localVersion, String remoteVersion, String error) {}
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn compile -q`
Expected: BUILD SUCCESS (no errors).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/dto/Index115CheckResult.java
git commit -m "feat: add Index115CheckResult dto"
```

---

### Task 2: Index115Service.check() via TDD

**Files:**
- Test: `src/test/java/cn/har01d/alist_tvbox/service/Index115ServiceTest.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/Index115Service.java`

- [ ] **Step 1: Add test imports**

In `src/test/java/cn/har01d/alist_tvbox/service/Index115ServiceTest.java`, extend the existing static-import line:

```java
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
```

And add the DTO import near the other `cn.har01d.alist_tvbox.dto` import:

```java
import cn.har01d.alist_tvbox.dto.Index115CheckResult;
```

- [ ] **Step 2: Write the five failing tests**

Append these tests inside `class Index115ServiceTest` (after `has115AccountFalseWhenNone`):

```java
    @Test
    void checkReturnsNoAccountWhenPan115Absent() {
        when(driverAccountRepository.findByTypeAndMasterTrue(DriverType.PAN115)).thenReturn(Optional.empty());

        Index115CheckResult result = service.check();

        assertFalse(result.hasAccount());
        assertFalse(result.hasUpdate());
        verify(versionClient, never()).fetch();
    }

    @Test
    void checkNoUpdateWhenLocalEqualsRemote() {
        when(driverAccountRepository.findByTypeAndMasterTrue(DriverType.PAN115)).thenReturn(Optional.of(new DriverAccount()));
        when(versionClient.fetch()).thenReturn(new Index115ShareRef("sw1", "6666"));
        when(settingRepository.findById("index115.share_code")).thenReturn(Optional.of(setting("sw1")));

        Index115CheckResult result = service.check();

        assertTrue(result.hasAccount());
        assertFalse(result.hasUpdate());
        assertEquals("sw1", result.localVersion());
        assertEquals("sw1", result.remoteVersion());
        assertNull(result.error());
    }

    @Test
    void checkHasUpdateWhenLocalDiffersFromRemote() {
        when(driverAccountRepository.findByTypeAndMasterTrue(DriverType.PAN115)).thenReturn(Optional.of(new DriverAccount()));
        when(versionClient.fetch()).thenReturn(new Index115ShareRef("sw2", "7777"));
        when(settingRepository.findById("index115.share_code")).thenReturn(Optional.of(setting("sw1")));

        Index115CheckResult result = service.check();

        assertTrue(result.hasAccount());
        assertTrue(result.hasUpdate());
        assertEquals("sw1", result.localVersion());
        assertEquals("sw2", result.remoteVersion());
    }

    @Test
    void checkReturnsErrorWhenRemoteFetchFails() {
        when(driverAccountRepository.findByTypeAndMasterTrue(DriverType.PAN115)).thenReturn(Optional.of(new DriverAccount()));
        when(versionClient.fetch()).thenReturn(null);

        Index115CheckResult result = service.check();

        assertTrue(result.hasAccount());
        assertFalse(result.hasUpdate());
        assertNotNull(result.error());
    }

    @Test
    void checkHasUpdateWhenLocalEmptyAndRemotePresent() {
        when(driverAccountRepository.findByTypeAndMasterTrue(DriverType.PAN115)).thenReturn(Optional.of(new DriverAccount()));
        when(versionClient.fetch()).thenReturn(new Index115ShareRef("sw2", "7777"));
        when(settingRepository.findById("index115.share_code")).thenReturn(Optional.empty());

        Index115CheckResult result = service.check();

        assertTrue(result.hasUpdate());
        assertEquals("", result.localVersion());
        assertEquals("sw2", result.remoteVersion());
    }
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn test -Dtest=Index115CheckResult -q` (filter by the new tests) or `mvn test -Dtest=Index115ServiceTest -q`
Expected: COMPILE ERROR — `method check() does not exist in Index115Service` (this is the red state).

- [ ] **Step 4: Implement check()**

In `src/main/java/cn/har01d/alist_tvbox/service/Index115Service.java`, add the import at the top with the other dto imports:

```java
import cn.har01d.alist_tvbox.dto.Index115CheckResult;
```

Add this method to the `Index115Service` class (after `has115Account()`):

```java
    public Index115CheckResult check() {
        if (!has115Account()) {
            return new Index115CheckResult(false, false, "", "", null);
        }
        String local = settingRepository.findById(SHARE_CODE_KEY).map(Setting::getValue).orElse("");
        Index115ShareRef ref = versionClient.fetch();
        if (ref == null) {
            return new Index115CheckResult(true, false, local, "", "无法获取远端版本");
        }
        String remote = ref.shareCode();
        boolean hasUpdate = !local.equals(remote);
        return new Index115CheckResult(true, hasUpdate, local, remote, null);
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -Dtest=Index115ServiceTest -q`
Expected: BUILD SUCCESS, `Tests run: 11, Failures: 0` (6 existing + 5 new).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/Index115Service.java src/test/java/cn/har01d/alist_tvbox/service/Index115ServiceTest.java
git commit -m "feat: Index115Service.check() compares local vs remote share code"
```

---

### Task 3: Add GET /api/index115/check endpoint

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/web/Index115Controller.java`

Note: This controller follows the existing pattern — thin delegation, no controller-level test exists for this controller. The behavior is covered by `Index115ServiceTest`. Auth is inherited from the global `/api/**` → ADMIN/CLIENT rule, so no security annotation is needed (matches the existing `/status` and `/update` mappings).

- [ ] **Step 1: Add the mapping**

In `src/main/java/cn/har01d/alist_tvbox/web/Index115Controller.java`, add the import:

```java
import cn.har01d.alist_tvbox.dto.Index115CheckResult;
```

Add this method to the controller class (after `status()`):

```java
    @GetMapping("/check")
    public Index115CheckResult check() {
        return index115Service.check();
    }
```

- [ ] **Step 2: Verify compile + existing tests still green**

Run: `mvn test -Dtest=Index115ServiceTest -q`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/web/Index115Controller.java
git commit -m "feat: GET /api/index115/check returns update status"
```

---

### Task 4: Frontend — show update badge + versions in ConfigView

**Files:**
- Modify: `web-ui/src/views/ConfigView.vue`

- [ ] **Step 1: Add reactive state**

Find the existing lines (around line 477-478):

```js
const has115Account = ref(false)
const index115Loading = ref(false)
```

Replace with:

```js
const has115Account = ref(false)
const index115Loading = ref(false)
const index115Checking = ref(false)
const index115Check = ref({hasAccount: false, hasUpdate: false, localVersion: '', remoteVersion: '', error: null})
```

- [ ] **Step 2: Add the checkIndex115 function**

Find the existing `updateIndex115` function (around line 785) and add `checkIndex115` immediately before it:

```js
const checkIndex115 = () => {
  index115Checking.value = true
  axios.get('/api/index115/check').then(({data}) => {
    index115Check.value = data
  }).catch(() => {
    index115Check.value = {hasAccount: true, hasUpdate: false, localVersion: '', remoteVersion: '', error: '检查失败'}
  }).finally(() => {
    index115Checking.value = false
  })
}

const updateIndex115 = () => {
```

(Leave the body of `updateIndex115` unchanged — do NOT auto-recheck after download, since `/update` returns 202 before the async task finishes.)

- [ ] **Step 3: Chain the check after status resolves in onMounted**

Find the existing block (around line 857):

```js
  axios.get('/api/index115/status').then(({data}) => {
    has115Account.value = data.hasAccount
  })
```

Replace with:

```js
  axios.get('/api/index115/status').then(({data}) => {
    has115Account.value = data.hasAccount
    if (data.hasAccount) {
      checkIndex115()
    }
  })
```

- [ ] **Step 4: Update the 115索引 form-item UI**

Find the existing form item (around line 209):

```html
        <el-form-item label="115索引" v-if="has115Account">
          <el-button type="primary" :loading="index115Loading" @click="updateIndex115">下载</el-button>
        </el-form-item>
```

Replace with:

```html
        <el-form-item label="115索引" v-if="has115Account">
          <el-button type="primary" :loading="index115Loading" @click="updateIndex115">下载</el-button>
          <el-tag v-if="index115Checking" type="info" style="margin-left: 8px">检查中</el-tag>
          <el-tag v-else-if="index115Check.error" type="danger" style="margin-left: 8px">检查失败</el-tag>
          <el-tag v-else-if="index115Check.hasUpdate" type="warning" style="margin-left: 8px">有更新</el-tag>
          <el-tag v-else type="success" style="margin-left: 8px">已是最新</el-tag>
          <span class="hint" style="margin-left: 8px">当前: {{ index115Check.localVersion || '未下载' }}　最新: {{ index115Check.remoteVersion || '-' }}</span>
        </el-form-item>
```

- [ ] **Step 5: Lint + build the frontend**

Run:
```bash
cd web-ui && npm run lint && npm run build
```
Expected: lint passes with no errors; build completes.

- [ ] **Step 6: Commit**

```bash
git add web-ui/src/views/ConfigView.vue
git commit -m "feat: show 115 index update status badge on Advanced Settings"
```

---

### Task 5: Regenerate native-image reflect-config + final verification

**Files:**
- Regenerate: `src/main/resources/META-INF/native-image/reflect-config.json`

`Main.java` auto-scans `cn.har01d.alist_tvbox.dto`, so `Index115CheckResult` will be picked up when Main runs.

- [ ] **Step 1: Compile and regenerate reflect-config**

Run:
```bash
mvn compile -q
java -cp target/classes cn.har01d.alist_tvbox.Main
```
Expected: Main runs and rewrites `src/main/resources/META-INF/native-image/reflect-config.json`.

- [ ] **Step 2: Confirm the new record was registered**

Run: `grep -c "Index115CheckResult" src/main/resources/META-INF/native-image/reflect-config.json`
Expected: a count ≥ 1. If 0, add `"cn.har01d.alist_tvbox.dto.Index115CheckResult"` to `CUSTOM_REFLECTION_CLASSES` in `src/main/java/cn/har01d/alist_tvbox/Main.java`, re-run Step 1, re-check.

- [ ] **Step 3: Commit if reflect-config changed**

```bash
git add src/main/resources/META-INF/native-image/reflect-config.json src/main/java/cn/har01d/alist_tvbox/Main.java
git diff --cached --quiet || git commit -m "chore: register Index115CheckResult in native-image reflect-config"
```

- [ ] **Step 4: Full backend build + test**

Run: `mvn clean package -q`
Expected: BUILD SUCCESS, all tests green.

- [ ] **Step 5: Manual verification**

Run the app (`mvn spring-boot:run`), open Advanced Settings with a 115 account configured:
- "115索引" row shows a tag: 检查中 → then 有更新 or 已是最新.
- Version span shows `当前: <local shareCode or 未下载>` and `最新: <remote shareCode or ->`.
- Without a 115 account, the row is hidden.
- 下载 button still works as before.

---

## Self-Review

**Spec coverage:**
- DTO `Index115CheckResult` → Task 1 ✓
- `Index115Service.check()` (no-account / local==remote / local!=remote / fetch-null / local-empty) → Task 2 ✓
- `GET /api/index115/check` → Task 3 ✓
- Frontend auto-check on mount + badge + versions, 下载 retained → Task 4 ✓
- Native image reflect-config → Task 5 ✓
- Non-goals respected: no polling/cache, no retry button, `update()` untouched (Task 4 Step 2 explicitly leaves it) ✓

**Placeholder scan:** None — all steps contain concrete code/commands.

**Type consistency:** Record fields `(hasAccount, hasUpdate, localVersion, remoteVersion, error)` used identically in service, controller return, tests, and frontend template. Accessor names match (`hasAccount()`, `hasUpdate()`, `localVersion()`, `remoteVersion()`, `error()`). ✓
