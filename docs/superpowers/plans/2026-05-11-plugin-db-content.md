# Plugin Database Content Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace plugin disk-file persistence with database-backed plugin content, expose that content through `/plugins/{vod_token}/{id}.txt`, and update subscription plugin `ext` generation to use the tokenized public endpoint.

**Architecture:** Keep plugin metadata in the existing `plugin` table and add a single `content` column for the downloaded text body. `PluginService` remains the owner of remote download and refresh behavior, while a new public controller handles token validation plus plain-text content delivery through `SubscriptionService.checkToken(...)`.

**Tech Stack:** Spring Boot, Spring MVC, Spring Data JPA, Flyway SQL migrations, JUnit 5, Mockito, Vue 3 TypeScript.

---

## File Structure

- Modify: `src/main/resources/db/migration/V4__Store_plugin_content.sql`
  Add the durable `content` column without relying on the in-progress `local_path` runtime path.
- Modify: `src/main/java/cn/har01d/alist_tvbox/entity/Plugin.java`
  Add the `content` field and hide it from management JSON responses.
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/PluginService.java`
  Stop writing plugin files, store downloaded text in `content`, and expose a read-content helper for the public controller.
- Create: `src/main/java/cn/har01d/alist_tvbox/web/PluginContentController.java`
  Public token-protected plain-text endpoint for `/plugins/{vod_token}/{id}.txt`.
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java`
  Build plugin `ext` from `getCurrentOrFirstToken()` and plugin id instead of `localPath`.
- Modify: `src/test/java/cn/har01d/alist_tvbox/service/PluginServiceTest.java`
  Replace file-based assertions with database-content assertions.
- Create: `src/test/java/cn/har01d/alist_tvbox/web/PluginContentControllerTest.java`
  Cover public content delivery and token rejection.
- Modify: `src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceTest.java`
  Update plugin `ext` expectations to `/plugins/{vod_token}/{id}.txt`.
- Modify: `web-ui/src/views/SubscriptionsView.vue`
  Remove the obsolete `localPath` UI type field.

### Task 1: Add Database Content Persistence and Red Service Tests

**Files:**
- Create: `src/main/resources/db/migration/V4__Store_plugin_content.sql`
- Modify: `src/main/java/cn/har01d/alist_tvbox/entity/Plugin.java`
- Modify: `src/test/java/cn/har01d/alist_tvbox/service/PluginServiceTest.java`

- [ ] **Step 1: Rewrite the plugin service tests to describe database-backed behavior**

```java
package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.PluginRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PluginServiceTest {
    @Mock
    private PluginRepository pluginRepository;
    @Mock
    private RestTemplate restTemplate;
    @Mock
    private RestTemplateBuilder builder;

    private PluginService pluginService;

    @BeforeEach
    void setUp() {
        when(builder.build()).thenReturn(restTemplate);
        pluginService = new PluginService(pluginRepository, builder);
    }

    @Test
    void createShouldStoreDownloadedPluginContentAndDefaultNameFromEncodedFilename() {
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
        assertThat(saved.getContent()).isEqualTo("plugin-body");
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getSortOrder()).isEqualTo(1);
        assertThat(saved.getLastError()).isBlank();
        assertThat(saved.getLastCheckedAt()).isNotNull();
    }

    @Test
    void refreshShouldOverwriteContentAndKeepCustomName() {
        Plugin plugin = new Plugin();
        plugin.setId(9);
        plugin.setName("我的4K源");
        plugin.setSourceName("4K指南");
        plugin.setUrl("https://example.com/4K%E6%8C%87%E5%8D%97.txt");
        plugin.setContent("old-body");

        when(pluginRepository.findById(9)).thenReturn(Optional.of(plugin));
        when(restTemplate.getForObject(URI.create(plugin.getUrl()), String.class)).thenReturn("new-body");
        when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Plugin refreshed = pluginService.refresh(9);

        assertThat(refreshed.getName()).isEqualTo("我的4K源");
        assertThat(refreshed.getSourceName()).isEqualTo("4K指南");
        assertThat(refreshed.getContent()).isEqualTo("new-body");
        assertThat(refreshed.getLastError()).isBlank();
    }

    @Test
    void refreshShouldKeepExistingContentWhenDownloadFails() {
        Plugin plugin = new Plugin();
        plugin.setId(9);
        plugin.setName("4K指南");
        plugin.setSourceName("4K指南");
        plugin.setUrl("https://example.com/4k.txt");
        plugin.setContent("stable-body");

        when(pluginRepository.findById(9)).thenReturn(Optional.of(plugin));
        when(restTemplate.getForObject(URI.create(plugin.getUrl()), String.class)).thenThrow(new RuntimeException("404"));
        when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Plugin refreshed = pluginService.refresh(9);

        assertThat(refreshed.getContent()).isEqualTo("stable-body");
        assertThat(refreshed.getLastError()).contains("插件地址不可访问");
    }

    @Test
    void updateShouldRedownloadWhenUrlChangesAndReplaceContent() {
        Plugin plugin = new Plugin();
        plugin.setId(15);
        plugin.setName("old");
        plugin.setSourceName("old");
        plugin.setUrl("https://example.com/old.txt");
        plugin.setContent("old-body");

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
        assertThat(updated.getContent()).isEqualTo("new-body");
    }

    @Test
    void readContentShouldReturnStoredPluginText() {
        Plugin plugin = new Plugin();
        plugin.setId(21);
        plugin.setContent("plugin-body");

        when(pluginRepository.findById(21)).thenReturn(Optional.of(plugin));

        assertThat(pluginService.readContent(21)).isEqualTo("plugin-body");
    }

    @Test
    void deleteShouldRemovePluginRowWithoutFilesystemCleanup() {
        Plugin plugin = new Plugin();
        plugin.setId(22);

        when(pluginRepository.findById(22)).thenReturn(Optional.of(plugin));

        pluginService.delete(22);

        verify(pluginRepository).delete(plugin);
    }
}
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run: `mvn -q -Dtest=PluginServiceTest test`
Expected: FAIL because `Plugin` has no `content` field, `PluginService` still writes files, and `readContent` does not exist.

- [ ] **Step 3: Add the schema field and entity field required by the red tests**

```sql
ALTER TABLE PUBLIC.PLUGIN
    ADD COLUMN IF NOT EXISTS CONTENT CHARACTER VARYING;
```

```java
import com.fasterxml.jackson.annotation.JsonIgnore;

@JsonIgnore
@Column(columnDefinition = "TEXT")
private String content;
```

- [ ] **Step 4: Run the focused test again**

Run: `mvn -q -Dtest=PluginServiceTest test`
Expected: FAIL in `PluginService` behavior only, not in missing field compilation.

- [ ] **Step 5: Commit the persistence scaffolding**

```bash
git add src/main/resources/db/migration/V4__Store_plugin_content.sql src/main/java/cn/har01d/alist_tvbox/entity/Plugin.java src/test/java/cn/har01d/alist_tvbox/service/PluginServiceTest.java
git commit -m "test: cover plugin db content persistence"
```

### Task 2: Replace File Persistence in PluginService With Database Content

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/PluginService.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/service/PluginServiceTest.java`

- [ ] **Step 1: Implement the minimal service changes to satisfy the red tests**

```java
package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.PluginRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class PluginService {
    private final PluginRepository pluginRepository;
    private final RestTemplate restTemplate;

    @Autowired
    public PluginService(PluginRepository pluginRepository, RestTemplateBuilder builder) {
        this.pluginRepository = pluginRepository;
        this.restTemplate = builder.build();
    }

    @Transactional
    public Plugin create(Plugin plugin) {
        validateUrlUniqueness(plugin.getUrl(), null);
        String body = downloadPlugin(plugin.getUrl());
        String sourceName = deriveSourceName(plugin.getUrl());
        plugin.setSourceName(sourceName);
        plugin.setName(StringUtils.defaultIfBlank(plugin.getName(), sourceName));
        plugin.setContent(body);
        plugin.setEnabled(true);
        plugin.setSortOrder(pluginRepository.findAllByOrderBySortOrderAscIdAsc().size() + 1);
        plugin.setLastCheckedAt(OffsetDateTime.now());
        plugin.setLastError("");
        return pluginRepository.save(plugin);
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
            plugin.setContent(body);
            plugin.setLastCheckedAt(OffsetDateTime.now());
            plugin.setLastError("");
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
            plugin.setContent(body);
            plugin.setLastError("");
        } catch (RuntimeException e) {
            plugin.setLastError(e.getMessage());
        }
        plugin.setLastCheckedAt(OffsetDateTime.now());
        return pluginRepository.save(plugin);
    }

    public String readContent(Integer id) {
        Plugin plugin = pluginRepository.findById(id).orElseThrow(NotFoundException::new);
        if (StringUtils.isBlank(plugin.getContent())) {
            throw new BadRequestException("插件内容为空");
        }
        return plugin.getContent();
    }

    public void delete(Integer id) {
        Plugin plugin = pluginRepository.findById(id).orElseThrow(NotFoundException::new);
        pluginRepository.delete(plugin);
    }
}
```

- [ ] **Step 2: Run the focused service tests**

Run: `mvn -q -Dtest=PluginServiceTest test`
Expected: PASS

- [ ] **Step 3: Run the reorder regression explicitly**

Run: `mvn -q -Dtest=PluginServiceTest#reorderShouldRewriteSortOrderUsingIncomingIds test`
Expected: PASS

- [ ] **Step 4: Commit the service implementation**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/PluginService.java src/test/java/cn/har01d/alist_tvbox/service/PluginServiceTest.java
git commit -m "feat: store plugin content in database"
```

### Task 3: Add the Public Tokenized Plugin Content Endpoint

**Files:**
- Create: `src/main/java/cn/har01d/alist_tvbox/web/PluginContentController.java`
- Create: `src/test/java/cn/har01d/alist_tvbox/web/PluginContentControllerTest.java`

- [ ] **Step 1: Write the public endpoint tests**

```java
package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.config.RestErrorHandler;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.PluginService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PluginContentControllerTest {
    @Mock
    private SubscriptionService subscriptionService;
    @Mock
    private PluginService pluginService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PluginContentController(subscriptionService, pluginService))
                .setControllerAdvice(new RestErrorHandler())
                .build();
    }

    @Test
    void contentShouldReturnStoredPluginText() throws Exception {
        when(pluginService.readContent(12)).thenReturn("plugin-body");

        mockMvc.perform(get("/plugins/abc123/12.txt"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("plugin-body"));

        verify(subscriptionService).checkToken("abc123");
        verify(pluginService).readContent(12);
    }

    @Test
    void contentShouldRejectInvalidToken() throws Exception {
        doThrow(new BadRequestException("bad token")).when(subscriptionService).checkToken("bad");

        mockMvc.perform(get("/plugins/bad/12.txt"))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Run the controller test to verify it fails**

Run: `mvn -q -Dtest=PluginContentControllerTest test`
Expected: FAIL because `PluginContentController` does not exist yet.

- [ ] **Step 3: Implement the public controller**

```java
package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.service.PluginService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PluginContentController {
    private final SubscriptionService subscriptionService;
    private final PluginService pluginService;

    public PluginContentController(SubscriptionService subscriptionService, PluginService pluginService) {
        this.subscriptionService = subscriptionService;
        this.pluginService = pluginService;
    }

    @GetMapping(value = "/plugins/{token}/{id}.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public String content(@PathVariable String token, @PathVariable Integer id) {
        subscriptionService.checkToken(token);
        return pluginService.readContent(id);
    }
}
```

- [ ] **Step 4: Run the focused controller test**

Run: `mvn -q -Dtest=PluginContentControllerTest test`
Expected: PASS

- [ ] **Step 5: Commit the public endpoint**

```bash
git add src/main/java/cn/har01d/alist_tvbox/web/PluginContentController.java src/test/java/cn/har01d/alist_tvbox/web/PluginContentControllerTest.java
git commit -m "feat: serve plugin content from database"
```

### Task 4: Switch Subscription ext Generation and Remove Obsolete UI Fields

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java`
- Modify: `src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceTest.java`
- Modify: `web-ui/src/views/SubscriptionsView.vue`

- [ ] **Step 1: Update the subscription tests to expect tokenized plugin URLs**

```java
@Test
void buildPluginSiteShouldAppendExtendSuffixToTokenizedPluginUrl() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/subscriptions");
    request.setScheme("http");
    request.setServerName("127.0.0.1");
    request.setServerPort(4567);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    when(appProperties.isEnableHttps()).thenReturn(false);
    when(appProperties.isEnabledToken()).thenReturn(false);
    subscriptionService.checkToken("abc123");

    Plugin plugin = new Plugin();
    plugin.setId(12);
    plugin.setName("4K指南");
    plugin.setExtend("foo=bar");

    Map<String, Object> site = ReflectionTestUtils.invokeMethod(subscriptionService, "buildPluginSite", plugin);

    assertThat(site).containsEntry("api", "http://127.0.0.1:4567/Atvp.py");
    assertThat(site).containsEntry("ext", "http://127.0.0.1:4567/plugins/abc123/12.txt@@foo=bar");
}
```

- [ ] **Step 2: Run the subscription test to verify it fails**

Run: `mvn -q -Dtest=SubscriptionServiceTest test`
Expected: FAIL because `buildPluginSite` still uses `localPath`.

- [ ] **Step 3: Change `buildPluginSite` to use the current token and plugin id**

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
    String ext = readHostAddress("") + "/plugins/" + getCurrentOrFirstToken() + "/" + plugin.getId() + ".txt";
    if (StringUtils.isNotBlank(plugin.getExtend())) {
        ext += "@@" + plugin.getExtend();
    }
    site.put("ext", ext);
    return site;
}
```

- [ ] **Step 4: Remove the obsolete frontend field**

```ts
interface Plugin {
  id: number
  name: string
  url: string
  enabled: boolean
  sortOrder: number
  extend: string
  sourceName: string
  lastCheckedAt: string
  lastError: string
}

const pluginForm = ref<Plugin>({
  id: 0,
  name: '',
  url: '',
  enabled: true,
  sortOrder: 0,
  extend: '',
  sourceName: '',
  lastCheckedAt: '',
  lastError: ''
})

const resetPluginForm = () => {
  pluginForm.value = {
    id: 0,
    name: '',
    url: '',
    enabled: true,
    sortOrder: 0,
    extend: '',
    sourceName: '',
    lastCheckedAt: '',
    lastError: ''
  }
}
```

- [ ] **Step 5: Run focused integration verification**

Run: `mvn -q -Dtest=PluginServiceTest,PluginContentControllerTest,SubscriptionServiceTest test`
Expected: PASS

Run: `npm run type-check --prefix web-ui`
Expected: PASS

- [ ] **Step 6: Commit the subscription and UI update**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceTest.java web-ui/src/views/SubscriptionsView.vue
git commit -m "feat: expose tokenized plugin content urls"
```
