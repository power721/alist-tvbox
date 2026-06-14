# Plugin Repository Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add plugin-repository import on the subscription page so admins can enter a GitHub repository URL or a direct `spiders.json` URL, import all plugin URLs from the repo root `spiders.json`, refresh already-existing plugin URLs, and deduplicate repeated URLs within the same import.

**Architecture:** Extend the existing plugin backend with a dedicated batch import path instead of overloading single-plugin create. `PluginService` will normalize GitHub URLs to a `spiders.json` source, download and parse a string array, create missing plugin rows, refresh already-existing plugin rows with the same URL, and return an import summary that distinguishes created, refreshed, skipped, and failed items. The subscription-page plugin dialog will add a second import form that posts the source URL and displays the summary while keeping the existing single-plugin flow unchanged.

**Tech Stack:** Spring Boot, Spring MVC, Jackson, RestTemplate, JUnit 5, Mockito, Vue 3, Element Plus, Axios.

---

### Task 1: Add Failing Backend Tests for Repository Import

**Files:**
- Modify: `src/test/java/cn/har01d/alist_tvbox/service/PluginServiceTest.java`

- [ ] **Step 1: Add a failing test for direct `spiders.json` import with duplicate URLs**

```java
@Test
void importFromSpidersJsonShouldCreateNewPluginsAndSkipExistingUrls() {
    String sourceUrl = "https://github.com/har01d5/tvbox/raw/refs/heads/master/spiders.json";
    String firstPlugin = "https://example.com/a.txt";
    String secondPlugin = "https://example.com/b.txt";
    String payload = """
            [
              "https://example.com/a.txt",
              "https://example.com/a.txt",
              "https://example.com/b.txt"
            ]
            """;

    when(restTemplate.getForObject(URI.create(sourceUrl), String.class)).thenReturn(payload);
    when(pluginRepository.findByUrl(firstPlugin)).thenReturn(Optional.empty(), Optional.of(new Plugin()));
    when(pluginRepository.findByUrl(secondPlugin)).thenReturn(Optional.empty());
    when(restTemplate.getForObject(URI.create(firstPlugin), String.class)).thenReturn("body-a");
    when(restTemplate.getForObject(URI.create(secondPlugin), String.class)).thenReturn("body-b");
    when(pluginRepository.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of(), List.of(new Plugin()));
    when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));

    PluginService.ImportResult result = pluginService.importFromSource(sourceUrl);

    assertThat(result.sourceUrl()).isEqualTo(sourceUrl);
    assertThat(result.createdCount()).isEqualTo(2);
    assertThat(result.skippedCount()).isEqualTo(1);
    assertThat(result.failedCount()).isEqualTo(0);
    assertThat(result.created()).containsExactly("a", "b");
    assertThat(result.skipped()).containsExactly(firstPlugin);
}
```

- [ ] **Step 2: Add a failing test for GitHub repository URL normalization**

```java
@Test
void importFromRepositoryUrlShouldResolveRootSpidersJson() {
    String repositoryUrl = "https://github.com/har01d5/tvbox";
    String resolvedUrl = "https://github.com/har01d5/tvbox/raw/refs/heads/master/spiders.json";
    String pluginUrl = "https://example.com/demo.txt";

    when(restTemplate.getForObject(URI.create(resolvedUrl), String.class)).thenReturn("[\"" + pluginUrl + "\"]");
    when(pluginRepository.findByUrl(pluginUrl)).thenReturn(Optional.empty());
    when(restTemplate.getForObject(URI.create(pluginUrl), String.class)).thenReturn("body");
    when(pluginRepository.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of());
    when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));

    PluginService.ImportResult result = pluginService.importFromSource(repositoryUrl);

    assertThat(result.sourceUrl()).isEqualTo(resolvedUrl);
    assertThat(result.created()).containsExactly("demo");
}
```

- [ ] **Step 3: Add a failing test for invalid repository/import source**

```java
@Test
void importFromSourceShouldRejectUnsupportedUrl() {
    assertThatThrownBy(() -> pluginService.importFromSource("https://example.com/list.json"))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("不支持");
}
```

- [ ] **Step 4: Run the targeted backend test and verify it fails for missing import behavior**

Run: `mvn -q -Dtest=PluginServiceTest test`
Expected: FAIL with missing `importFromSource` / `ImportResult` or assertion failures around unresolved repository import.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/cn/har01d/alist_tvbox/service/PluginServiceTest.java
git commit -m "test: cover plugin repository import"
```

### Task 2: Implement Backend Import Service and API

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/PluginService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/web/PluginController.java`

- [ ] **Step 1: Add the import result types and URL normalization helpers in `PluginService`**

```java
public record ImportRequest(String url) {
}

public record ImportResult(
        String sourceUrl,
        int createdCount,
        int skippedCount,
        int failedCount,
        List<String> created,
        List<String> skipped,
        List<String> failed
) {
}

String normalizeImportSource(String url) {
    URI uri = URI.create(StringUtils.trimToEmpty(url));
    if (!"github.com".equalsIgnoreCase(uri.getHost())) {
        if (uri.getPath() != null && uri.getPath().endsWith("/spiders.json")) {
            return uri.toString();
        }
        throw new BadRequestException("不支持的仓库地址");
    }
    String[] segments = StringUtils.strip(uri.getPath(), "/").split("/");
    if (segments.length < 2) {
        throw new BadRequestException("GitHub 仓库地址不正确");
    }
    if (segments.length >= 5 && "raw".equals(segments[2]) && "refs".equals(segments[3]) && uri.getPath().endsWith("/spiders.json")) {
        return uri.toString();
    }
    if (segments.length >= 4 && "blob".equals(segments[2]) && uri.getPath().endsWith("/spiders.json")) {
        return "https://github.com/" + segments[0] + "/" + segments[1] + "/raw/refs/heads/" + segments[3] + "/spiders.json";
    }
    if (segments.length >= 4 && "tree".equals(segments[2])) {
        return "https://github.com/" + segments[0] + "/" + segments[1] + "/raw/refs/heads/" + segments[3] + "/spiders.json";
    }
    return "https://github.com/" + segments[0] + "/" + segments[1] + "/raw/refs/heads/master/spiders.json";
}
```

- [ ] **Step 2: Implement batch import using the existing create logic**

```java
@Transactional
public ImportResult importFromSource(String url) {
    String sourceUrl = normalizeImportSource(url);
    String payload = downloadText(sourceUrl, "spiders.json 不可访问");
    List<String> pluginUrls = readPluginUrls(payload);
    List<String> created = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    List<String> failed = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();

    for (String pluginUrl : pluginUrls) {
        if (StringUtils.isBlank(pluginUrl)) {
            continue;
        }
        String normalizedPluginUrl = StringUtils.trim(pluginUrl);
        if (!seen.add(normalizedPluginUrl)) {
            skipped.add(normalizedPluginUrl);
            continue;
        }
        if (pluginRepository.findByUrl(normalizedPluginUrl).isPresent()) {
            skipped.add(normalizedPluginUrl);
            continue;
        }
        try {
            Plugin plugin = new Plugin();
            plugin.setUrl(normalizedPluginUrl);
            Plugin saved = create(plugin);
            created.add(saved.getName());
        } catch (RuntimeException e) {
            failed.add(normalizedPluginUrl + ": " + e.getMessage());
        }
    }

    return new ImportResult(sourceUrl, created.size(), skipped.size(), failed.size(), created, skipped, failed);
}
```

- [ ] **Step 3: Add JSON parsing and text-download helpers**

```java
private String downloadText(String url, String message) {
    try {
        String body = restTemplate.getForObject(URI.create(url), String.class);
        if (StringUtils.isBlank(body)) {
            throw new BadRequestException(message);
        }
        return body;
    } catch (Exception e) {
        throw new BadRequestException(message, e);
    }
}

private List<String> readPluginUrls(String payload) {
    try {
        return objectMapper.readValue(payload, new TypeReference<List<String>>() {
        });
    } catch (Exception e) {
        throw new BadRequestException("spiders.json 格式不正确", e);
    }
}
```

- [ ] **Step 4: Add the controller import endpoint**

```java
public record PluginImportRequest(String url) {
}

@PostMapping("/import")
public PluginService.ImportResult importPlugins(@RequestBody PluginImportRequest request) {
    return pluginService.importFromSource(request.url());
}
```

- [ ] **Step 5: Run the targeted backend test and verify it passes**

Run: `mvn -q -Dtest=PluginServiceTest test`
Expected: PASS with repository import coverage green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/PluginService.java src/main/java/cn/har01d/alist_tvbox/web/PluginController.java src/test/java/cn/har01d/alist_tvbox/service/PluginServiceTest.java
git commit -m "feat: add plugin repository import api"
```

### Task 3: Add Subscription-Page Import UI

**Files:**
- Modify: `web-ui/src/views/SubscriptionsView.vue`

- [ ] **Step 1: Add form state for repository import**

```ts
const pluginImportForm = ref({
  url: '',
})
```

- [ ] **Step 2: Add the repository import form above the plugin table**

```vue
<el-divider content-position="left">仓库导入</el-divider>
<el-form :inline="true" :model="pluginImportForm">
  <el-form-item label="仓库地址" required>
    <el-input
      v-model="pluginImportForm.url"
      style="width: 460px"
      placeholder="https://github.com/har01d5/tvbox 或 spiders.json 地址"
    />
  </el-form-item>
  <el-form-item>
    <el-button type="primary" @click="importPlugins">导入仓库插件</el-button>
  </el-form-item>
</el-form>
```

- [ ] **Step 3: Add the import action and summary message**

```ts
const importPlugins = () => {
  axios.post('/api/plugins/import', {
    url: pluginImportForm.value.url,
  }).then(({data}) => {
    const failed = data.failedCount ? `，失败 ${data.failedCount}` : ''
    ElMessage.success(`导入完成，新增 ${data.createdCount}，跳过 ${data.skippedCount}${failed}`)
    pluginImportForm.value.url = ''
    loadPlugins()
  })
}
```

- [ ] **Step 4: Reset both plugin forms when opening the dialog**

```ts
const showPlugins = () => {
  pluginVisible.value = true
  resetPluginForm()
  pluginImportForm.value.url = ''
  loadPlugins()
}
```

- [ ] **Step 5: Run a frontend build-oriented check if available**

Run: `npm run build --prefix web-ui`
Expected: PASS with the updated subscription page compiling.

- [ ] **Step 6: Commit**

```bash
git add web-ui/src/views/SubscriptionsView.vue
git commit -m "feat: add plugin repository import ui"
```
