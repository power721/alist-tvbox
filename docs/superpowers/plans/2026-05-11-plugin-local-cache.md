# Plugin Local Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Change plugin add/refresh/update/delete so remote plugin files are cached under `/www/plugins/<plugin-id>.txt`, and generated subscription plugin sites use the local public URL instead of the remote source URL.

**Architecture:** Extend the existing plugin persistence model with `localPath`, move remote fetch plus local file lifecycle into `PluginService`, and keep `SubscriptionService` focused on building site maps from stored plugin metadata. Follow the existing `/www/...` path resolution style used by `ConfigFileService.writeFileContent`: store `/www/plugins/<id>.txt` in the entity, resolve that public path to `Utils.getWebPath(...)` for real file I/O, and keep a test-only directory override so unit tests do not write into `/opt/atv/www`.

**Tech Stack:** Spring Boot, Spring Data JPA, Flyway SQL migrations, JUnit 5, Mockito, Java NIO, Vue 3 TypeScript.

---

### Task 1: Add Local Cache Persistence and Service Tests

**Files:**
- Create: `src/main/resources/db/migration/V3__Add_plugin_local_path.sql`
- Modify: `src/main/java/cn/har01d/alist_tvbox/entity/Plugin.java`
- Modify: `src/test/java/cn/har01d/alist_tvbox/service/PluginServiceTest.java`

- [ ] **Step 1: Replace the current plugin service tests with local-cache red tests**

```java
package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.PluginRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PluginServiceTest {
    @Mock
    private PluginRepository pluginRepository;
    @Mock
    private RestTemplate restTemplate;
    @Mock
    private RestTemplateBuilder builder;

    @TempDir
    Path tempDir;

    private PluginService pluginService;

    @BeforeEach
    void setUp() {
        when(builder.build()).thenReturn(restTemplate);
        pluginService = new PluginService(pluginRepository, builder, tempDir.resolve("plugins"));
    }

    @Test
    void createShouldCacheRemotePluginLocallyAndDefaultNameFromEncodedFilename() throws Exception {
        Plugin plugin = new Plugin();
        plugin.setUrl("https://github.com/har01d5/tvbox/raw/refs/heads/master/py/4K%E6%8C%87%E5%8D%97.txt");

        when(pluginRepository.findByUrl(plugin.getUrl())).thenReturn(Optional.empty());
        when(restTemplate.getForObject(URI.create(plugin.getUrl()), String.class)).thenReturn("plugin-body");
        when(pluginRepository.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of());
        when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> {
            Plugin saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(12);
            }
            return saved;
        });

        Plugin saved = pluginService.create(plugin);

        assertThat(saved.getName()).isEqualTo("4K指南");
        assertThat(saved.getSourceName()).isEqualTo("4K指南");
        assertThat(saved.getLocalPath()).isEqualTo("/www/plugins/12.txt");
        assertThat(Files.readString(tempDir.resolve("plugins/12.txt"))).isEqualTo("plugin-body");
    }

    @Test
    void createShouldRejectUnreachableUrl() {
        Plugin plugin = new Plugin();
        plugin.setUrl("https://example.com/missing.txt");

        when(pluginRepository.findByUrl(plugin.getUrl())).thenReturn(Optional.empty());
        when(restTemplate.getForObject(URI.create(plugin.getUrl()), String.class)).thenThrow(new RuntimeException("404"));

        assertThatThrownBy(() -> pluginService.create(plugin))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("插件地址不可访问");
    }

    @Test
    void refreshShouldOverwriteLocalFileAndKeepCustomName() throws Exception {
        Plugin plugin = new Plugin();
        plugin.setId(9);
        plugin.setName("我的4K源");
        plugin.setSourceName("4K指南");
        plugin.setUrl("https://example.com/4K%E6%8C%87%E5%8D%97.txt");
        plugin.setLocalPath("/www/plugins/9.txt");
        Files.createDirectories(tempDir.resolve("plugins"));
        Files.writeString(tempDir.resolve("plugins/9.txt"), "old-body");

        when(pluginRepository.findById(9)).thenReturn(Optional.of(plugin));
        when(restTemplate.getForObject(URI.create(plugin.getUrl()), String.class)).thenReturn("new-body");
        when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Plugin refreshed = pluginService.refresh(9);

        assertThat(refreshed.getName()).isEqualTo("我的4K源");
        assertThat(refreshed.getSourceName()).isEqualTo("4K指南");
        assertThat(refreshed.getLastError()).isBlank();
        assertThat(Files.readString(tempDir.resolve("plugins/9.txt"))).isEqualTo("new-body");
    }

    @Test
    void refreshShouldKeepExistingLocalFileWhenDownloadFails() throws Exception {
        Plugin plugin = new Plugin();
        plugin.setId(9);
        plugin.setName("4K指南");
        plugin.setSourceName("4K指南");
        plugin.setUrl("https://example.com/4k.txt");
        plugin.setLocalPath("/www/plugins/9.txt");
        Files.createDirectories(tempDir.resolve("plugins"));
        Files.writeString(tempDir.resolve("plugins/9.txt"), "stable-body");

        when(pluginRepository.findById(9)).thenReturn(Optional.of(plugin));
        when(restTemplate.getForObject(URI.create(plugin.getUrl()), String.class)).thenThrow(new RuntimeException("404"));
        when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Plugin refreshed = pluginService.refresh(9);

        assertThat(refreshed.getLastError()).contains("插件地址不可访问");
        assertThat(Files.readString(tempDir.resolve("plugins/9.txt"))).isEqualTo("stable-body");
    }

    @Test
    void updateShouldRedownloadWhenUrlChangesAndReuseSameLocalFile() throws Exception {
        Plugin plugin = new Plugin();
        plugin.setId(15);
        plugin.setName("旧名字");
        plugin.setSourceName("old");
        plugin.setUrl("https://example.com/old.txt");
        plugin.setLocalPath("/www/plugins/15.txt");
        Files.createDirectories(tempDir.resolve("plugins"));
        Files.writeString(tempDir.resolve("plugins/15.txt"), "old-body");

        Plugin input = new Plugin();
        input.setName("");
        input.setUrl("https://example.com/new.txt");
        input.setEnabled(true);
        input.setExtend("token=1");

        when(pluginRepository.findById(15)).thenReturn(Optional.of(plugin));
        when(pluginRepository.findByUrl(input.getUrl())).thenReturn(Optional.empty());
        when(restTemplate.getForObject(URI.create(input.getUrl()), String.class)).thenReturn("new-body");
        when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Plugin updated = pluginService.update(15, input);

        assertThat(updated.getUrl()).isEqualTo("https://example.com/new.txt");
        assertThat(updated.getSourceName()).isEqualTo("new");
        assertThat(updated.getName()).isEqualTo("new");
        assertThat(updated.getLocalPath()).isEqualTo("/www/plugins/15.txt");
        assertThat(Files.readString(tempDir.resolve("plugins/15.txt"))).isEqualTo("new-body");
    }

    @Test
    void deleteShouldRemoveCachedFile() throws Exception {
        Plugin plugin = new Plugin();
        plugin.setId(21);
        plugin.setLocalPath("/www/plugins/21.txt");
        Files.createDirectories(tempDir.resolve("plugins"));
        Files.writeString(tempDir.resolve("plugins/21.txt"), "plugin-body");

        when(pluginRepository.findById(21)).thenReturn(Optional.of(plugin));

        pluginService.delete(21);

        assertThat(tempDir.resolve("plugins/21.txt")).doesNotExist();
    }

    @Test
    void reorderShouldRewriteSortOrderUsingIncomingIds() {
        Plugin first = new Plugin();
        first.setId(1);
        first.setSortOrder(1);
        Plugin second = new Plugin();
        second.setId(2);
        second.setSortOrder(2);

        when(pluginRepository.findById(2)).thenReturn(Optional.of(second));
        when(pluginRepository.findById(1)).thenReturn(Optional.of(first));
        when(pluginRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        pluginService.reorder(List.of(2, 1));

        assertThat(second.getSortOrder()).isEqualTo(1);
        assertThat(first.getSortOrder()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run: `mvn -q -Dtest=PluginServiceTest test`
Expected: FAIL because `Plugin` has no `localPath`, `PluginService` has no path-injection constructor, and the local cache workflow does not exist yet.

- [ ] **Step 3: Add the schema column and entity field needed by the tests**

```sql
ALTER TABLE PUBLIC.PLUGIN
    ADD COLUMN IF NOT EXISTS LOCAL_PATH CHARACTER VARYING(255);

UPDATE PUBLIC.PLUGIN
SET LOCAL_PATH = CONCAT('/www/plugins/', ID, '.txt')
WHERE LOCAL_PATH IS NULL;

ALTER TABLE PUBLIC.PLUGIN
    ALTER COLUMN LOCAL_PATH SET NOT NULL;
```

```java
@Column(name = "local_path")
private String localPath;
```

- [ ] **Step 4: Run the test again and confirm it is still red for service behavior only**

Run: `mvn -q -Dtest=PluginServiceTest test`
Expected: FAIL in `PluginService` behavior, not in missing field or missing migration compilation.

- [ ] **Step 5: Commit the schema/test scaffolding**

```bash
git add src/main/resources/db/migration/V3__Add_plugin_local_path.sql src/main/java/cn/har01d/alist_tvbox/entity/Plugin.java src/test/java/cn/har01d/alist_tvbox/service/PluginServiceTest.java
git commit -m "test: cover plugin local cache workflow"
```

### Task 2: Implement PluginService Download, Local File Lifecycle, and Delete Semantics

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/PluginService.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/service/PluginServiceTest.java`

- [ ] **Step 1: Implement the service against the red tests**

```java
package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.PluginRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import cn.har01d.alist_tvbox.util.Utils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class PluginService {
    private final PluginRepository pluginRepository;
    private final RestTemplate restTemplate;
    private final Path testPluginsDir;

    public PluginService(PluginRepository pluginRepository, RestTemplateBuilder builder) {
        this(pluginRepository, builder, null);
    }

    PluginService(PluginRepository pluginRepository, RestTemplateBuilder builder, Path testPluginsDir) {
        this.pluginRepository = pluginRepository;
        this.restTemplate = builder.build();
        this.testPluginsDir = testPluginsDir;
    }

    @Transactional
    public Plugin create(Plugin plugin) {
        validateUrlUniqueness(plugin.getUrl(), null);
        String body = downloadPlugin(plugin.getUrl());
        String sourceName = deriveSourceName(plugin.getUrl());
        plugin.setSourceName(sourceName);
        plugin.setName(StringUtils.defaultIfBlank(plugin.getName(), sourceName));
        plugin.setEnabled(true);
        plugin.setSortOrder(pluginRepository.findAllByOrderBySortOrderAscIdAsc().size() + 1);
        plugin.setLastCheckedAt(OffsetDateTime.now());
        plugin.setLastError("");

        Plugin saved = pluginRepository.save(plugin);
        saved.setLocalPath("/www/plugins/" + saved.getId() + ".txt");
        writePluginFile(saved, body);
        return pluginRepository.save(saved);
    }

    @Transactional
    public Plugin update(Integer id, Plugin input) {
        Plugin plugin = pluginRepository.findById(id).orElseThrow(NotFoundException::new);
        boolean urlChanged = !StringUtils.equals(plugin.getUrl(), input.getUrl());
        if (urlChanged) {
            validateUrlUniqueness(input.getUrl(), id);
            String body = downloadPlugin(input.getUrl());
            String previousSourceName = plugin.getSourceName();
            String newSourceName = deriveSourceName(input.getUrl());
            if (StringUtils.isBlank(plugin.getName()) || StringUtils.equals(plugin.getName(), previousSourceName)) {
                plugin.setName(newSourceName);
            }
            plugin.setUrl(input.getUrl());
            plugin.setSourceName(newSourceName);
            plugin.setLastCheckedAt(OffsetDateTime.now());
            plugin.setLastError("");
            ensureLocalPath(plugin);
            writePluginFile(plugin, body);
        }
        plugin.setName(StringUtils.defaultIfBlank(input.getName(), plugin.getSourceName()));
        plugin.setEnabled(input.isEnabled());
        plugin.setExtend(input.getExtend());
        return pluginRepository.save(plugin);
    }

    @Transactional
    public Plugin refresh(Integer id) {
        Plugin plugin = pluginRepository.findById(id).orElseThrow(NotFoundException::new);
        String previousSourceName = plugin.getSourceName();
        try {
            String body = downloadPlugin(plugin.getUrl());
            String refreshedSourceName = deriveSourceName(plugin.getUrl());
            if (StringUtils.isBlank(plugin.getName()) || StringUtils.equals(plugin.getName(), previousSourceName)) {
                plugin.setName(refreshedSourceName);
            }
            plugin.setSourceName(refreshedSourceName);
            ensureLocalPath(plugin);
            writePluginFile(plugin, body);
            plugin.setLastError("");
        } catch (RuntimeException e) {
            plugin.setLastError(e.getMessage());
        }
        plugin.setLastCheckedAt(OffsetDateTime.now());
        return pluginRepository.save(plugin);
    }

    @Transactional
    public void delete(Integer id) {
        Plugin plugin = pluginRepository.findById(id).orElseThrow(NotFoundException::new);
        deletePluginFile(plugin);
        pluginRepository.delete(plugin);
    }

    private String downloadPlugin(String url) {
        try {
            String body = restTemplate.getForObject(URI.create(url), String.class);
            if (StringUtils.isBlank(body)) {
                throw new BadRequestException("插件地址不可访问");
            }
            return body;
        } catch (Exception e) {
            throw new BadRequestException("插件地址不可访问", e);
        }
    }

    private void ensureLocalPath(Plugin plugin) {
        if (StringUtils.isBlank(plugin.getLocalPath())) {
            plugin.setLocalPath("/www/plugins/" + plugin.getId() + ".txt");
        }
    }

    private void writePluginFile(Plugin plugin, String body) {
        ensureLocalPath(plugin);
        try {
            Path path = resolvePluginFile(plugin);
            Files.createDirectories(path.getParent());
            Files.writeString(path, body);
        } catch (IOException e) {
            throw new BadRequestException("插件文件保存失败", e);
        }
    }

    private void deletePluginFile(Plugin plugin) {
        try {
            Files.deleteIfExists(resolvePluginFile(plugin));
        } catch (IOException e) {
            throw new BadRequestException("删除插件文件失败", e);
        }
    }

    private Path resolvePluginFile(Plugin plugin) {
        if (testPluginsDir != null) {
            return testPluginsDir.resolve(plugin.getId() + ".txt");
        }
        return Utils.getWebPath(plugin.getLocalPath().replace("/www/", ""));
    }
}
```

- [ ] **Step 2: Run the focused service tests**

Run: `mvn -q -Dtest=PluginServiceTest test`
Expected: PASS

- [ ] **Step 3: Run one more focused check for regression in current reorder behavior**

Run: `mvn -q -Dtest=PluginServiceTest#reorderShouldRewriteSortOrderUsingIncomingIds test`
Expected: PASS

- [ ] **Step 4: Commit the service implementation**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/PluginService.java src/test/java/cn/har01d/alist_tvbox/service/PluginServiceTest.java
git commit -m "feat: cache plugin files locally"
```

### Task 3: Switch Subscription Plugin Sites to Local Public URLs

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java`
- Modify: `src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceTest.java`

- [ ] **Step 1: Update the subscription tests to describe the new ext contract**

```java
@Test
void buildPluginSiteShouldAppendExtendSuffixToLocalPluginUrl() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/subscriptions");
    request.setScheme("http");
    request.setServerName("127.0.0.1");
    request.setServerPort(4567);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    when(appProperties.isEnableHttps()).thenReturn(false);

    Plugin plugin = new Plugin();
    plugin.setName("4K指南");
    plugin.setLocalPath("/www/plugins/12.txt");
    plugin.setExtend("foo=bar");

    Map<String, Object> site = ReflectionTestUtils.invokeMethod(subscriptionService, "buildPluginSite", plugin);

    assertThat(site).containsEntry("name", "4K指南");
    assertThat(site).containsEntry("key", "4K指南");
    assertThat(site).containsEntry("api", "http://127.0.0.1:4567/Atvp.py");
    assertThat(site).containsEntry("ext", "http://127.0.0.1:4567/plugins/12.txt@@foo=bar");
}

@Test
void addPluginSitesShouldInsertBeforeExistingSites() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/subscriptions");
    request.setScheme("http");
    request.setServerName("127.0.0.1");
    request.setServerPort(4567);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    when(appProperties.isEnableHttps()).thenReturn(false);

    Plugin plugin = new Plugin();
    plugin.setName("插件A");
    plugin.setLocalPath("/www/plugins/1.txt");
    plugin.setSortOrder(1);
    plugin.setEnabled(true);

    when(pluginRepository.findByEnabledTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(plugin));

    Map<String, Object> builtIn = new HashMap<>();
    builtIn.put("name", "AList");
    Map<String, Object> config = new HashMap<>();
    config.put("sites", new ArrayList<>(List.of(builtIn)));

    int nextIndex = ReflectionTestUtils.invokeMethod(subscriptionService, "addPluginSites", config, 0);

    List<Map<String, Object>> sites = (List<Map<String, Object>>) config.get("sites");
    assertThat(nextIndex).isEqualTo(1);
    assertThat(sites).extracting(site -> site.get("name")).containsExactly("插件A", "AList");
    assertThat(sites).extracting(site -> site.get("ext")).containsExactly("http://127.0.0.1:4567/plugins/1.txt", null);
}
```

- [ ] **Step 2: Run the focused subscription test to verify it fails**

Run: `mvn -q -Dtest=SubscriptionServiceTest test`
Expected: FAIL because `buildPluginSite` still uses `plugin.getUrl()`.

- [ ] **Step 3: Change `buildPluginSite` to derive ext from `localPath`**

```java
private Map<String, Object> buildPluginSite(Plugin plugin) {
    Map<String, Object> site = new HashMap<>();
    site.put("filterable", 1);
    site.put("quickSearch", 1);
    site.put("name", plugin.getName());
    site.put("changeable", 0);
    site.put("api", readHostAddress("") + "/Atvp.py");
    site.put("type", 3);
    site.put("key", plugin.getName());
    site.put("searchable", 1);
    String ext = readHostAddress("") + plugin.getLocalPath().substring(4);
    if (StringUtils.isNotBlank(plugin.getExtend())) {
        ext += "@@" + plugin.getExtend();
    }
    site.put("ext", ext);
    return site;
}
```

- [ ] **Step 4: Run the focused subscription tests**

Run: `mvn -q -Dtest=SubscriptionServiceTest test`
Expected: PASS

- [ ] **Step 5: Commit the subscription change**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceTest.java
git commit -m "feat: use local plugin urls in subscriptions"
```

### Task 4: Keep the Frontend Typed and Run End-to-End Verification

**Files:**
- Modify: `web-ui/src/views/SubscriptionsView.vue`

- [ ] **Step 1: Extend the local frontend type to include the persisted local path**

```ts
interface Plugin {
  id: number
  name: string
  url: string
  enabled: boolean
  sortOrder: number
  extend: string
  sourceName: string
  localPath: string
  lastCheckedAt: string
  lastError: string
}
```

- [ ] **Step 2: Initialize the new field in the add form reset object**

```ts
const pluginForm = ref<Plugin>({
  id: 0,
  name: '',
  url: '',
  enabled: true,
  sortOrder: 0,
  extend: '',
  sourceName: '',
  localPath: '',
  lastCheckedAt: '',
  lastError: ''
})
```

```ts
const resetPluginForm = () => {
  pluginForm.value = {
    id: 0,
    name: '',
    url: '',
    enabled: true,
    sortOrder: 0,
    extend: '',
    sourceName: '',
    localPath: '',
    lastCheckedAt: '',
    lastError: ''
  }
}
```

- [ ] **Step 3: Run backend verification**

Run: `mvn -q -Dtest=PluginServiceTest,SubscriptionServiceTest test`
Expected: PASS

- [ ] **Step 4: Run frontend verification**

Run: `npm run type-check --prefix web-ui`
Expected: PASS

- [ ] **Step 5: Commit the final verification-safe UI typing update**

```bash
git add web-ui/src/views/SubscriptionsView.vue
git commit -m "chore: align plugin ui type with local cache fields"
```
