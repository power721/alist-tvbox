# Proxy Chunk Size KB Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Change cloud-drive proxy `chunk_size` from byte-based values to KB-based values in the netdisk config dialog and pass those KB values directly through `site.ext`.

**Architecture:** First lock in the new backend contract by changing `SubscriptionServiceTest` to expect KB-sized `chunk_size` values inside `local_proxy_config`. Then update `DriverAccountView.vue` so its defaults, labels, minimums, and step values all use KB units and it saves those KB values unchanged. `SubscriptionService` already forwards `local_proxy_config` without conversion, so no backend production-code unit conversion change is needed beyond keeping that behavior intact.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Mockito, Vue 3, TypeScript, Element Plus, Vite, vue-tsc

---

### Task 1: Lock In KB Units With Failing Backend Tests

**Files:**
- Modify: `src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceTest.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceTest.java`

- [ ] **Step 1: Change the stored JSON fixture in `buildSiteShouldEmitStoredLocalProxyConfig` from byte values to KB values**

```java
when(settingRepository.findById("local_proxy_config")).thenReturn(Optional.of(new Setting(
        "local_proxy_config",
        "{\"QUARK\":{\"enabled\":true,\"concurrency\":20,\"chunk_size\":1024},\"UC\":{\"enabled\":false,\"concurrency\":10,\"chunk_size\":256}}"
)));
```

- [ ] **Step 2: Update the assertions in the same test to expect KB values**

```java
assertThat(((Map<String, Object>) localProxyConfig.get("QUARK"))).containsEntry("concurrency", 20);
assertThat(((Map<String, Object>) localProxyConfig.get("QUARK"))).containsEntry("chunk_size", 1024);
assertThat(((Map<String, Object>) localProxyConfig.get("UC"))).containsEntry("enabled", false);
assertThat(((Map<String, Object>) localProxyConfig.get("UC"))).containsEntry("chunk_size", 256);
```

- [ ] **Step 3: Run the targeted backend test to verify it fails for the right reason**

Run: `mvn -Dtest=SubscriptionServiceTest test`

Expected: FAIL because the current fixture/assertion change expects KB values while the saved example or UI defaults still use byte-sized values elsewhere in the implementation flow.

- [ ] **Step 4: Commit the red test update**

```bash
git add src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceTest.java
git commit -m "test: expect proxy chunk size in kb"
```

### Task 2: Update `DriverAccountView` To Use KB Units End-to-End

**Files:**
- Modify: `web-ui/src/views/DriverAccountView.vue`
- Verify: `web-ui/src/views/DriverAccountView.vue`

- [ ] **Step 1: Change the default `localProxyConfig` values from bytes to KB**

```ts
const defaultLocalProxyConfig = (): LocalProxyConfig => ({
  ALI: {enabled: true, concurrency: 20, chunk_size: 1024},
  QUARK: {enabled: true, concurrency: 20, chunk_size: 1024},
  UC: {enabled: true, concurrency: 10, chunk_size: 256},
  PAN115: {enabled: true, concurrency: 2, chunk_size: 1024},
  PAN123: {enabled: true, concurrency: 4, chunk_size: 256},
  PAN139: {enabled: true, concurrency: 4, chunk_size: 256},
  BAIDU: {enabled: true, concurrency: 5, chunk_size: 2048},
})
```

- [ ] **Step 2: Make the dialog label explicit about KB**

```vue
<span>分片大小(KB)</span>
```

- [ ] **Step 3: Change the `el-input-number` bounds and step from byte values to KB values**

```vue
<el-input-number
  v-model="localProxyConfig[item.key].chunk_size"
  :min="256"
  :step="256"
/>
```

- [ ] **Step 4: Keep the save path as a raw JSON pass-through with no unit conversion**

```ts
const updateLocalProxyConfig = () => {
  axios.post('/api/settings', {
    name: 'local_proxy_config',
    value: JSON.stringify(localProxyConfig.value),
  }).then(() => {
    ElMessage.success('更新成功')
    configVisible.value = false
  })
}
```

- [ ] **Step 5: Run frontend type-check and build verification**

Run: `npm --prefix web-ui run build`

Expected: PASS with `vue-tsc --noEmit` and `vite build` both succeeding.

- [ ] **Step 6: Commit the frontend KB-unit change**

```bash
git add web-ui/src/views/DriverAccountView.vue
git commit -m "feat: use kb for proxy chunk size"
```

### Task 3: Final Verification

**Files:**
- Verify: `src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceTest.java`
- Verify: `web-ui/src/views/DriverAccountView.vue`
- Verify: `src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java`

- [ ] **Step 1: Re-run the targeted backend test**

Run: `mvn -Dtest=SubscriptionServiceTest test`

Expected: PASS

- [ ] **Step 2: Re-run the repository test target**

Run: `mvn test`

Expected: PASS

- [ ] **Step 3: Re-run the frontend build**

Run: `npm --prefix web-ui run build`

Expected: PASS

- [ ] **Step 4: Inspect the final diff**

Run: `git diff -- src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceTest.java web-ui/src/views/DriverAccountView.vue docs/superpowers/specs/2026-05-08-enable-local-proxy-design.md`

Expected: Only the KB-unit changes to tests, UI defaults/labels/input bounds, and the already-reviewed spec update appear.
