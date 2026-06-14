# Plugin Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a global plugin management feature with a dedicated `plugin` table, subscription-page management UI, URL validation/refresh, drag sorting, and plugin site injection at the end of generated TvBox subscription configs.

**Architecture:** Add a standalone plugin backend slice (`Plugin` entity/repository/service/controller`) and keep plugin-specific validation/name derivation inside the service layer. Extend `SubscriptionService` with a small helper that converts enabled plugins into TvBox site maps using `readHostAddress("") + "/Atvp.py"`, then add a modal dialog in `SubscriptionsView.vue` that manages the global list and persists drag order.

**Tech Stack:** Spring Boot, Spring MVC, Spring Data JPA, Flyway SQL migrations, Vue 3, Element Plus, Axios, SortableJS.

---

### Task 1: Create Plugin Persistence Model

**Files:**
- Create: `src/main/resources/db/migration/V2__Add_plugin_table.sql`
- Create: `src/main/java/cn/har01d/alist_tvbox/entity/Plugin.java`
- Create: `src/main/java/cn/har01d/alist_tvbox/entity/PluginRepository.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/service/PluginServiceTest.java`

- [ ] **Step 1: Write the failing persistence/service skeleton test**

```java
package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.PluginRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class PluginServiceTest {
    @Mock
    private PluginRepository pluginRepository;
    @Mock
    private RestTemplate restTemplate;
    @Spy
    private RestTemplateBuilder builder = new RestTemplateBuilder();
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private PluginService pluginService;

    @Test
    void createShouldDefaultNameFromEncodedFilename() {
        // compile-time red test: PluginService/Plugin do not exist yet
        assertThatThrownBy(() -> pluginService.create(new Plugin()))
                .isInstanceOf(NullPointerException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=PluginServiceTest test`
Expected: FAIL because `PluginService`, `Plugin`, and `PluginRepository` do not exist yet.

- [ ] **Step 3: Add migration, entity, and repository**

```sql
CREATE TABLE IF NOT EXISTS PUBLIC.PLUGIN
(
    ID              INTEGER auto_increment primary key,
    NAME            CHARACTER VARYING(255) not null,
    URL             CHARACTER VARYING not null,
    ENABLED         BOOLEAN default TRUE not null,
    SORT_ORDER      INTEGER not null,
    EXTEND          CHARACTER VARYING,
    SOURCE_NAME     CHARACTER VARYING(255) not null,
    LAST_CHECKED_AT TIMESTAMP WITH TIME ZONE,
    LAST_ERROR      CHARACTER VARYING,
    constraint UK_PLUGIN_URL unique (URL)
);

CREATE INDEX IF NOT EXISTS IDX_PLUGIN_SORT_ORDER ON PUBLIC.PLUGIN (SORT_ORDER);
```

```java
package cn.har01d.alist_tvbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
@Entity
public class Plugin {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String name;
    @Column(columnDefinition = "TEXT", nullable = false, unique = true)
    private String url;
    private boolean enabled = true;
    @Column(name = "sort_order")
    private int sortOrder;
    @Column(name = "`extend`", columnDefinition = "TEXT")
    private String extend;
    @Column(name = "source_name")
    private String sourceName;
    @Column(name = "last_checked_at")
    private OffsetDateTime lastCheckedAt;
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;
}
```

```java
package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PluginRepository extends JpaRepository<Plugin, Integer> {
    Optional<Plugin> findByUrl(String url);
    List<Plugin> findAllByOrderBySortOrderAscIdAsc();
    List<Plugin> findByEnabledTrueOrderBySortOrderAscIdAsc();
}
```

- [ ] **Step 4: Run test to verify progress but still red**

Run: `mvn -q -Dtest=PluginServiceTest test`
Expected: FAIL because `PluginService#create` behavior is not implemented yet, but the new model compiles.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V2__Add_plugin_table.sql src/main/java/cn/har01d/alist_tvbox/entity/Plugin.java src/main/java/cn/har01d/alist_tvbox/entity/PluginRepository.java src/test/java/cn/har01d/alist_tvbox/service/PluginServiceTest.java
git commit -m "feat: add plugin persistence model"
```

### Task 2: Implement Plugin Service Validation, Naming, Refresh, and Reorder

**Files:**
- Create: `src/main/java/cn/har01d/alist_tvbox/service/PluginService.java`
- Modify: `src/test/java/cn/har01d/alist_tvbox/service/PluginServiceTest.java`

- [ ] **Step 1: Replace the placeholder test with concrete failing service tests**

```java
@Test
void createShouldDefaultNameFromEncodedFilename() {
    Plugin plugin = new Plugin();
    plugin.setUrl("https://github.com/har01d5/tvbox/raw/refs/heads/master/py/4K%E6%8C%87%E5%8D%97.txt");

    when(pluginRepository.findByUrl(plugin.getUrl())).thenReturn(Optional.empty());
    when(restTemplate.getForObject(plugin.getUrl(), String.class)).thenReturn("#EXTM3U");
    when(pluginRepository.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of());
    when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));

    Plugin saved = pluginService.create(plugin);

    assertThat(saved.getName()).isEqualTo("4K指南");
    assertThat(saved.getSourceName()).isEqualTo("4K指南");
    assertThat(saved.isEnabled()).isTrue();
    assertThat(saved.getSortOrder()).isEqualTo(1);
    assertThat(saved.getLastError()).isBlank();
    assertThat(saved.getLastCheckedAt()).isNotNull();
}

@Test
void createShouldRejectUnreachableUrl() {
    Plugin plugin = new Plugin();
    plugin.setUrl("https://example.com/missing.txt");

    when(pluginRepository.findByUrl(plugin.getUrl())).thenReturn(Optional.empty());
    when(restTemplate.getForObject(plugin.getUrl(), String.class)).thenThrow(new RuntimeException("404"));

    assertThatThrownBy(() -> pluginService.create(plugin))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("插件地址不可访问");
}

@Test
void refreshShouldPreserveCustomNameAndUpdateSourceName() {
    Plugin plugin = new Plugin();
    plugin.setId(9);
    plugin.setName("我的4K源");
    plugin.setSourceName("4K指南");
    plugin.setUrl("https://example.com/4K%E6%8C%87%E5%8D%97.txt");

    when(pluginRepository.findById(9)).thenReturn(Optional.of(plugin));
    when(restTemplate.getForObject(plugin.getUrl(), String.class)).thenReturn("ok");
    when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));

    Plugin refreshed = pluginService.refresh(9);

    assertThat(refreshed.getName()).isEqualTo("我的4K源");
    assertThat(refreshed.getSourceName()).isEqualTo("4K指南");
    assertThat(refreshed.getLastError()).isBlank();
    assertThat(refreshed.getLastCheckedAt()).isNotNull();
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
```

- [ ] **Step 2: Run test to verify it fails correctly**

Run: `mvn -q -Dtest=PluginServiceTest test`
Expected: FAIL because `PluginService` has no implementation for create/refresh/reorder, not because of syntax errors.

- [ ] **Step 3: Implement the plugin service**

```java
package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.PluginRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class PluginService {
    private final PluginRepository pluginRepository;
    private final RestTemplate restTemplate;

    public PluginService(PluginRepository pluginRepository, RestTemplateBuilder builder) {
        this.pluginRepository = pluginRepository;
        this.restTemplate = builder.build();
    }

    public List<Plugin> findAll() {
        return pluginRepository.findAllByOrderBySortOrderAscIdAsc();
    }

    public List<Plugin> findEnabled() {
        return pluginRepository.findByEnabledTrueOrderBySortOrderAscIdAsc();
    }

    @Transactional
    public Plugin create(Plugin plugin) {
        validateUrlUniqueness(plugin.getUrl(), null);
        checkUrlReachable(plugin.getUrl());
        String sourceName = deriveSourceName(plugin.getUrl());
        plugin.setSourceName(sourceName);
        plugin.setName(StringUtils.defaultIfBlank(plugin.getName(), sourceName));
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
            checkUrlReachable(input.getUrl());
            String previousSourceName = plugin.getSourceName();
            String newSourceName = deriveSourceName(input.getUrl());
            if (StringUtils.isBlank(plugin.getName()) || StringUtils.equals(plugin.getName(), previousSourceName)) {
                plugin.setName(newSourceName);
            }
            plugin.setUrl(input.getUrl());
            plugin.setSourceName(newSourceName);
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
            checkUrlReachable(plugin.getUrl());
            String refreshedSourceName = deriveSourceName(plugin.getUrl());
            if (StringUtils.isBlank(plugin.getName()) || StringUtils.equals(plugin.getName(), previousSourceName)) {
                plugin.setName(refreshedSourceName);
            }
            plugin.setSourceName(refreshedSourceName);
            plugin.setLastError("");
        } catch (RuntimeException e) {
            plugin.setLastError(e.getMessage());
        }
        plugin.setLastCheckedAt(OffsetDateTime.now());
        return pluginRepository.save(plugin);
    }

    @Transactional
    public void reorder(List<Integer> ids) {
        List<Plugin> plugins = new ArrayList<>();
        int order = 1;
        for (Integer id : ids) {
            Plugin plugin = pluginRepository.findById(id).orElseThrow(NotFoundException::new);
            plugin.setSortOrder(order++);
            plugins.add(plugin);
        }
        pluginRepository.saveAll(plugins);
    }

    public void delete(Integer id) {
        pluginRepository.deleteById(id);
    }

    private void validateUrlUniqueness(String url, Integer currentId) {
        pluginRepository.findByUrl(url).ifPresent(other -> {
            if (currentId == null || !other.getId().equals(currentId)) {
                throw new BadRequestException("插件地址重复");
            }
        });
    }

    private void checkUrlReachable(String url) {
        try {
            restTemplate.getForObject(url, String.class);
        } catch (Exception e) {
            throw new BadRequestException("插件地址不可访问", e);
        }
    }

    String deriveSourceName(String url) {
        String raw = url.substring(url.lastIndexOf('/') + 1);
        String decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
        int dot = decoded.lastIndexOf('.');
        return dot > 0 ? decoded.substring(0, dot) : decoded;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=PluginServiceTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/PluginService.java src/test/java/cn/har01d/alist_tvbox/service/PluginServiceTest.java
git commit -m "feat: add plugin service workflow"
```

### Task 3: Expose Plugin CRUD, Refresh, and Reorder APIs

**Files:**
- Create: `src/main/java/cn/har01d/alist_tvbox/web/PluginController.java`
- Create: `src/test/java/cn/har01d/alist_tvbox/web/PluginControllerTest.java`

- [ ] **Step 1: Write failing controller tests**

```java
package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.config.RestErrorHandler;
import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.service.PluginService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PluginControllerTest {
    @Mock
    private PluginService pluginService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PluginController(pluginService))
                .setControllerAdvice(new RestErrorHandler())
                .build();
    }

    @Test
    void findAllShouldReturnOrderedPlugins() throws Exception {
        Plugin plugin = new Plugin();
        plugin.setId(1);
        plugin.setName("4K指南");
        when(pluginService.findAll()).thenReturn(List.of(plugin));

        mockMvc.perform(get("/api/plugins"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("4K指南"));
    }

    @Test
    void reorderShouldForwardIdsToService() throws Exception {
        mockMvc.perform(post("/api/plugins/reorder")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(2, 1))))
                .andExpect(status().isOk());

        verify(pluginService).reorder(List.of(2, 1));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=PluginControllerTest test`
Expected: FAIL because `PluginController` does not exist yet.

- [ ] **Step 3: Implement the controller**

```java
package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.service.PluginService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/plugins")
public class PluginController {
    private final PluginService pluginService;

    public PluginController(PluginService pluginService) {
        this.pluginService = pluginService;
    }

    @GetMapping
    public List<Plugin> findAll() {
        return pluginService.findAll();
    }

    @PostMapping
    public Plugin create(@RequestBody Plugin plugin) {
        return pluginService.create(plugin);
    }

    @PutMapping("/{id}")
    public Plugin update(@PathVariable Integer id, @RequestBody Plugin plugin) {
        return pluginService.update(id, plugin);
    }

    @PostMapping("/{id}/refresh")
    public Plugin refresh(@PathVariable Integer id) {
        return pluginService.refresh(id);
    }

    @PostMapping("/reorder")
    public void reorder(@RequestBody List<Integer> ids) {
        pluginService.reorder(ids);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Integer id) {
        pluginService.delete(id);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=PluginControllerTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/web/PluginController.java src/test/java/cn/har01d/alist_tvbox/web/PluginControllerTest.java
git commit -m "feat: add plugin management APIs"
```

### Task 4: Append Enabled Plugin Sites in SubscriptionService

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java`
- Modify: `src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceTest.java`

- [ ] **Step 1: Add failing subscription tests for plugin site generation**

```java
@Mock
private PluginRepository pluginRepository;

@Test
void buildPluginSiteShouldAppendExtendSuffix() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/subscriptions");
    request.setScheme("http");
    request.setServerName("127.0.0.1");
    request.setServerPort(4567);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    when(appProperties.isEnableHttps()).thenReturn(false);

    Plugin plugin = new Plugin();
    plugin.setName("4K指南");
    plugin.setUrl("https://example.com/4k.txt");
    plugin.setExtend("foo=bar");

    Map<String, Object> site = ReflectionTestUtils.invokeMethod(subscriptionService, "buildPluginSite", plugin);

    assertThat(site).containsEntry("name", "4K指南");
    assertThat(site).containsEntry("key", "4K指南");
    assertThat(site).containsEntry("api", "http://127.0.0.1:4567/Atvp.py");
    assertThat(site).containsEntry("ext", "https://example.com/4k.txt@@foo=bar");
}

@Test
void addPluginSitesShouldUseEnabledPluginsInSortOrder() {
    Plugin first = new Plugin();
    first.setName("插件A");
    first.setUrl("https://example.com/a.txt");
    first.setSortOrder(1);
    first.setEnabled(true);
    Plugin second = new Plugin();
    second.setName("插件B");
    second.setUrl("https://example.com/b.txt");
    second.setSortOrder(2);
    second.setEnabled(true);

    when(pluginRepository.findByEnabledTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(first, second));

    Map<String, Object> config = new HashMap<>();
    config.put("sites", new ArrayList<Map<String, Object>>());

    ReflectionTestUtils.invokeMethod(subscriptionService, "addPluginSites", config);

    List<Map<String, Object>> sites = (List<Map<String, Object>>) config.get("sites");
    assertThat(sites).extracting(site -> site.get("name")).containsExactly("插件A", "插件B");
}
```

- [ ] **Step 2: Run test to verify it fails correctly**

Run: `mvn -q -Dtest=SubscriptionServiceTest test`
Expected: FAIL because `SubscriptionService` has no plugin repository dependency or plugin helper methods yet.

- [ ] **Step 3: Implement plugin site generation and append call**

```java
private final PluginRepository pluginRepository;

public SubscriptionService(...,
                           JellyfinRepository jellyfinRepository,
                           PluginRepository pluginRepository,
                           AListLocalService aListLocalService,
                           ConfigFileService configFileService,
                           TenantService tenantService,
                           UserService userService,
                           FileDownloader fileDownloader) {
    ...
    this.pluginRepository = pluginRepository;
    ...
}

private void addSite(String token, Map<String, Object> config) {
    int id = 0;
    List<Map<String, Object>> sites = (List<Map<String, Object>>) config.get("sites");
    ...
    try {
        Map<String, Object> site = buildSite(token, uid, "csp_Live", "网络直播");
        sites.add(id++, site);
        log.debug("add Live site: {}", site);
    } catch (Exception e) {
        log.warn("", e);
    }

    addPluginSites(config);
}

private void addPluginSites(Map<String, Object> config) {
    List<Map<String, Object>> sites = (List<Map<String, Object>>) config.get("sites");
    for (Plugin plugin : pluginRepository.findByEnabledTrueOrderBySortOrderAscIdAsc()) {
        sites.add(buildPluginSite(plugin));
    }
}

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
    String ext = plugin.getUrl();
    if (StringUtils.isNotBlank(plugin.getExtend())) {
        ext += "@@" + plugin.getExtend();
    }
    site.put("ext", ext);
    return site;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=SubscriptionServiceTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceTest.java
git commit -m "feat: append plugin sites to subscriptions"
```

### Task 5: Build the Subscription Page Plugin Management Dialog

**Files:**
- Modify: `web-ui/src/views/SubscriptionsView.vue`

- [ ] **Step 1: Add failing frontend state and compile target**

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

const pluginVisible = ref(false)
const plugins = ref<Plugin[]>([])
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
```

- [ ] **Step 2: Run type-check to verify the page is still red**

Run: `npm --prefix web-ui run type-check`
Expected: FAIL because the template still references no plugin dialog/button logic.

- [ ] **Step 3: Implement the plugin dialog, API calls, and drag sorting**

```vue
<el-button @click="showPlugins">插件管理</el-button>

<el-dialog v-model="pluginVisible" title="插件管理" width="80%">
  <el-form :inline="true" :model="pluginForm">
    <el-form-item label="地址" required>
      <el-input v-model="pluginForm.url" style="width: 460px" />
    </el-form-item>
    <el-form-item label="名称">
      <el-input v-model="pluginForm.name" style="width: 180px" placeholder="留空用文件名" />
    </el-form-item>
    <el-form-item>
      <el-button type="primary" @click="addPlugin">添加插件</el-button>
    </el-form-item>
  </el-form>

  <el-table :data="plugins" row-key="id" id="plugins-table">
    <el-table-column label="顺序" width="80">
      <template #default="scope">
        <span class="pointer">{{ scope.row.sortOrder }}</span>
      </template>
    </el-table-column>
    <el-table-column prop="name" label="名称" width="180">
      <template #default="scope">
        <el-input v-model="scope.row.name" @change="updatePlugin(scope.row)" />
      </template>
    </el-table-column>
    <el-table-column prop="url" label="地址" />
    <el-table-column prop="enabled" label="启用" width="90">
      <template #default="scope">
        <el-switch v-model="scope.row.enabled" @change="updatePlugin(scope.row)" />
      </template>
    </el-table-column>
    <el-table-column prop="extend" label="扩展配置">
      <template #default="scope">
        <el-input v-model="scope.row.extend" @change="updatePlugin(scope.row)" />
      </template>
    </el-table-column>
    <el-table-column prop="lastError" label="状态" width="220">
      <template #default="scope">
        <span>{{ scope.row.lastError || '正常' }}</span>
      </template>
    </el-table-column>
    <el-table-column label="操作" width="180">
      <template #default="scope">
        <el-button link type="primary" @click="refreshPlugin(scope.row.id)">刷新</el-button>
        <el-button link type="danger" @click="deletePlugin(scope.row.id)">删除</el-button>
      </template>
    </el-table-column>
  </el-table>
</el-dialog>
```

```ts
const loadPlugins = () => {
  axios.get('/api/plugins').then(({ data }) => {
    plugins.value = data
    setTimeout(enablePluginRowDrop, 100)
  })
}

const showPlugins = () => {
  pluginVisible.value = true
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
  loadPlugins()
}

const addPlugin = () => {
  axios.post('/api/plugins', {
    url: pluginForm.value.url,
    name: pluginForm.value.name
  }).then(() => {
    ElMessage.success('添加成功')
    pluginForm.value.url = ''
    pluginForm.value.name = ''
    loadPlugins()
  })
}

const updatePlugin = (plugin: Plugin) => {
  axios.put('/api/plugins/' + plugin.id, plugin).then(({ data }) => {
    Object.assign(plugin, data)
  })
}

const refreshPlugin = (id: number) => {
  axios.post('/api/plugins/' + id + '/refresh').then(({ data }) => {
    const index = plugins.value.findIndex(item => item.id === id)
    if (index >= 0) {
      plugins.value[index] = data
    }
  })
}

const deletePlugin = (id: number) => {
  axios.delete('/api/plugins/' + id).then(() => {
    ElMessage.success('删除成功')
    loadPlugins()
  })
}

const enablePluginRowDrop = () => {
  const tbody = document.querySelector('#plugins-table .el-table__body-wrapper tbody') as HTMLElement
  if (!tbody) {
    return
  }
  Sortable.create(tbody, {
    animation: 300,
    draggable: '.el-table__row',
    onEnd: ({ oldIndex, newIndex }) => {
      if (oldIndex == null || newIndex == null || oldIndex === newIndex) {
        return
      }
      const row = plugins.value.splice(oldIndex, 1)[0]
      plugins.value.splice(newIndex, 0, row)
      plugins.value.forEach((item, index) => {
        item.sortOrder = index + 1
      })
      axios.post('/api/plugins/reorder', plugins.value.map(item => item.id))
    }
  })
}
```

- [ ] **Step 4: Run type-check to verify it passes**

Run: `npm --prefix web-ui run type-check`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add web-ui/src/views/SubscriptionsView.vue
git commit -m "feat: add plugin management dialog"
```

### Task 6: Full Verification Pass

**Files:**
- Modify: none expected

- [ ] **Step 1: Run focused backend tests**

Run: `mvn -q -Dtest=PluginServiceTest,PluginControllerTest,SubscriptionServiceTest test`
Expected: PASS

- [ ] **Step 2: Run frontend type-check**

Run: `npm --prefix web-ui run type-check`
Expected: PASS

- [ ] **Step 3: Run the broader Maven test target if time permits**

Run: `mvn -q test`
Expected: PASS, or document any unrelated existing failures without changing unrelated code.

- [ ] **Step 4: Inspect git diff for scope**

Run: `git status --short`
Expected: only plugin feature files plus any pre-existing unrelated user changes.

- [ ] **Step 5: Commit verification or final integration commit**

```bash
git add src/main/resources/db/migration/V2__Add_plugin_table.sql src/main/java/cn/har01d/alist_tvbox/entity/Plugin.java src/main/java/cn/har01d/alist_tvbox/entity/PluginRepository.java src/main/java/cn/har01d/alist_tvbox/service/PluginService.java src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java src/main/java/cn/har01d/alist_tvbox/web/PluginController.java src/test/java/cn/har01d/alist_tvbox/service/PluginServiceTest.java src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceTest.java src/test/java/cn/har01d/alist_tvbox/web/PluginControllerTest.java web-ui/src/views/SubscriptionsView.vue
git commit -m "feat: add subscription plugin management"
```

## Self-Review

- Spec coverage:
  - Dedicated global table: covered by Task 1 migration/entity/repository.
  - Add/edit/delete/enable/disable/extend/refresh: covered by Tasks 2, 3, and 5.
  - Drag sorting: covered by Tasks 2, 3, and 5.
  - `api = readHostAddress("") + "/Atvp.py"`: covered by Task 4.
  - `ext` `<url>` or `<url>@@<extend>`: covered by Task 4 tests and helper.
  - Reject save when URL unreachable: covered by Task 2.
- Placeholder scan:
  - No `TODO`/`TBD` placeholders remain.
  - Commands, file paths, and helper names are explicit.
- Type consistency:
  - `sortOrder`, `sourceName`, `lastCheckedAt`, `lastError`, and `extend` use one naming scheme across backend and frontend.
  - Reorder API is consistently `POST /api/plugins/reorder` with `List<Integer>` IDs.
