# Raw Python Subscription Plugin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow subscription source plugins with `.py` URLs to be cached by the backend and loaded through `csp_PyProxy`, without changing encrypted `.txt` plugin behavior.

**Architecture:** Classify plugins from the URL path suffix, serve cached Python content from a token-protected `.py` endpoint, and generate a `loader` entry in the plugin ext payload for raw Python. Keep the existing entity and download lifecycle; extract only the ext payload selection into package-visible pure helpers so the generated protocol can be tested without constructing the large `SubscriptionService` dependency graph.

**Tech Stack:** Java 21, Spring Boot 4 MVC, Jackson, JUnit 5, Mockito, AssertJ, Vue 3, TypeScript, Node test runner.

---

## File Map

- Modify `src/main/java/cn/har01d/alist_tvbox/service/PluginService.java`: classify `.py` URLs using the parsed URI path.
- Modify `src/test/java/cn/har01d/alist_tvbox/service/PluginServiceTest.java`: prove raw Python download, refresh, naming, and suffix classification behavior.
- Modify `src/main/java/cn/har01d/alist_tvbox/web/PluginContentController.java`: expose cached content through a token-protected `.py` route.
- Create `src/test/java/cn/har01d/alist_tvbox/web/PluginContentControllerTest.java`: verify Python response content type, body, authentication order, and rejection behavior.
- Modify `src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java`: select `loader` versus `source` and preserve `.txt` run-mode behavior.
- Modify `src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceTest.java`: verify raw Python and encrypted text runtime payloads.
- Modify `web-ui/src/views/SubscriptionsView.vue`: show that the plugin input accepts `.txt` and `.py`.
- Modify `web-ui/src/views/SubscriptionsView.test.mjs`: protect the input contract.

### Task 1: Classify and cache raw Python plugins

**Files:**
- Modify: `src/test/java/cn/har01d/alist_tvbox/service/PluginServiceTest.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/PluginService.java`

- [ ] **Step 1: Write failing service tests**

Add tests which exercise the existing create/refresh lifecycle and call the not-yet-implemented classifier:

```java
@Test
void createShouldStoreRawPythonContentAndClassifyUrlPathWithQuery() {
    String url = "https://example.com/plugins/Demo.PY?raw=1";
    String content = "class Spider:\n    pass\n";
    Plugin plugin = new Plugin();
    plugin.setUrl(url);

    when(pluginRepository.findByUrl(url)).thenReturn(Optional.empty());
    when(restTemplate.getForObject(URI.create(url), String.class)).thenReturn(content);
    when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));

    Plugin saved = pluginService.create(plugin);

    assertThat(PluginService.isPythonPluginUrl(url)).isTrue();
    assertThat(saved.getName()).isEqualTo("Demo");
    assertThat(saved.getContent()).isEqualTo(content);
}

@Test
void refreshShouldReplaceRawPythonContent() {
    Plugin plugin = new Plugin();
    plugin.setId(21);
    plugin.setName("Demo");
    plugin.setSourceName("Demo");
    plugin.setUrl("https://example.com/plugins/demo.py");
    plugin.setContent("old");

    when(pluginRepository.findById(21)).thenReturn(Optional.of(plugin));
    when(restTemplate.getForObject(URI.create(plugin.getUrl()), String.class)).thenReturn("new");
    when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));

    Plugin refreshed = pluginService.refresh(21);

    assertThat(PluginService.isPythonPluginUrl(plugin.getUrl())).isTrue();
    assertThat(refreshed.getContent()).isEqualTo("new");
}

@Test
void pythonPluginClassifierShouldRejectTxtAndInvalidUrls() {
    assertThat(PluginService.isPythonPluginUrl("https://example.com/demo.txt")).isFalse();
    assertThat(PluginService.isPythonPluginUrl("not a url")).isFalse();
    assertThat(PluginService.isPythonPluginUrl(null)).isFalse();
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
mvn -q -Dtest=PluginServiceTest test
```

Expected: compilation fails because `PluginService.isPythonPluginUrl` does not exist.

- [ ] **Step 3: Implement URI-path classification**

Add this package-visible helper near the other URL helpers in `PluginService`:

```java
static boolean isPythonPluginUrl(String url) {
    try {
        String path = URI.create(StringUtils.trimToEmpty(url)).getPath();
        return StringUtils.endsWithIgnoreCase(path, ".py");
    } catch (Exception e) {
        return false;
    }
}
```

This intentionally inspects only the parsed path, so `.PY?raw=1` is Python while `.txt?file=demo.py` is not.

- [ ] **Step 4: Run tests and verify GREEN**

Run:

```bash
mvn -q -Dtest=PluginServiceTest test
```

Expected: all `PluginServiceTest` tests pass.

- [ ] **Step 5: Commit classifier and lifecycle coverage**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/PluginService.java src/test/java/cn/har01d/alist_tvbox/service/PluginServiceTest.java
git commit -m "feat: classify raw Python plugins"
```

### Task 2: Serve cached Python content with Token authentication

**Files:**
- Create: `src/test/java/cn/har01d/alist_tvbox/web/PluginContentControllerTest.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/web/PluginContentController.java`

- [ ] **Step 1: Write the failing controller tests**

Create the test with the same standalone MockMvc pattern used by other controllers:

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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
        PluginContentController controller = new PluginContentController(subscriptionService, pluginService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RestErrorHandler())
                .build();
    }

    @Test
    void pythonContentShouldAuthenticateAndReturnCachedSource() throws Exception {
        when(pluginService.readContent(7)).thenReturn("class Spider:\n    pass\n");

        mockMvc.perform(get("/plugins/test-token/7.py"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/x-python;charset=UTF-8"))
                .andExpect(content().string("class Spider:\n    pass\n"));

        verify(subscriptionService).checkToken("test-token");
        verify(pluginService).readContent(7);
    }

    @Test
    void pythonContentShouldNotReadPluginWhenTokenIsRejected() throws Exception {
        doThrow(new BadRequestException("Token不正确"))
                .when(subscriptionService).checkToken("bad-token");

        mockMvc.perform(get("/plugins/bad-token/7.py"))
                .andExpect(status().isBadRequest());

        verify(pluginService, never()).readContent(7);
    }
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
mvn -q -Dtest=PluginContentControllerTest test
```

Expected: the success test receives `404` because no `.py` mapping exists.

- [ ] **Step 3: Add the Python content route**

Keep the `.txt` method unchanged and add:

```java
@GetMapping(value = "/plugins/{token}/{id}.py", produces = "text/x-python;charset=UTF-8")
public String pythonContent(@PathVariable String token, @PathVariable Integer id) {
    subscriptionService.checkToken(token);
    return pluginService.readContent(id);
}
```

- [ ] **Step 4: Run tests and verify GREEN**

Run:

```bash
mvn -q -Dtest=PluginContentControllerTest test
```

Expected: both controller tests pass.

- [ ] **Step 5: Commit the authenticated content endpoint**

```bash
git add src/main/java/cn/har01d/alist_tvbox/web/PluginContentController.java src/test/java/cn/har01d/alist_tvbox/web/PluginContentControllerTest.java
git commit -m "feat: serve cached Python plugin content"
```

### Task 3: Generate PyProxy loader payloads for raw Python

**Files:**
- Modify: `src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceTest.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java`

- [ ] **Step 1: Write failing payload tests**

Add focused tests for the package-visible pure helpers:

```java
@Test
void rawPythonPluginShouldUsePyProxyLoaderAndLocalProxyInEveryRunMode() {
    Plugin plugin = new Plugin();
    plugin.setId(7);
    plugin.setUrl("https://example.com/demo.py?raw=1");
    plugin.setExtend("{\"site\":\"demo\"}");
    Map<String, Object> localProxyConfig = new HashMap<>();
    localProxyConfig.put("ALI", Map.of("enabled", true));

    Map<String, Object> payload = SubscriptionService.buildPluginExtPayload(
            plugin, "http://atv", "web", "vod-token", "secret", true, localProxyConfig);

    assertThat(SubscriptionService.selectPluginApi(plugin, true, "http://atv"))
            .isEqualTo("csp_PyProxy");
    assertThat(payload)
            .containsEntry("loader", "http://atv/plugins/web/7.py")
            .containsEntry("api", "http://atv")
            .containsEntry("token", "vod-token")
            .containsEntry("secret", "secret")
            .containsEntry("data", "{\"site\":\"demo\"}")
            .containsEntry("local_proxy_config", localProxyConfig)
            .doesNotContainKey("source");
}

@Test
void encryptedTxtPluginShouldKeepSourceAndNativePythonModeBehavior() {
    Plugin plugin = new Plugin();
    plugin.setId(8);
    plugin.setUrl("https://example.com/demo.txt");
    Map<String, Object> localProxyConfig = new HashMap<>();
    localProxyConfig.put("ALI", Map.of("enabled", true));

    Map<String, Object> payload = SubscriptionService.buildPluginExtPayload(
            plugin, "http://atv", "web", "", "secret", true, localProxyConfig);

    assertThat(SubscriptionService.selectPluginApi(plugin, true, "http://atv"))
            .isEqualTo("http://atv/Atvp.py");
    assertThat(payload)
            .containsEntry("source", "http://atv/plugins/web/8.txt")
            .containsEntry("token", "-")
            .doesNotContainKey("loader");
    assertThat(payload.get("local_proxy_config"))
            .isEqualTo(new HashMap<>());
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
mvn -q -Dtest=SubscriptionServiceTest test
```

Expected: compilation fails because `buildPluginExtPayload` and `selectPluginApi` do not exist.

- [ ] **Step 3: Add pure protocol helpers**

Add these package-visible static helpers immediately before `buildPluginSite`:

```java
static String selectPluginApi(Plugin plugin, boolean nativePython, String baseUrl) {
    if (PluginService.isPythonPluginUrl(plugin.getUrl())) {
        return "csp_PyProxy";
    }
    return nativePython ? baseUrl + "/Atvp.py" : "csp_PyProxy";
}

static Map<String, Object> buildPluginExtPayload(Plugin plugin,
                                                 String baseUrl,
                                                 String contentToken,
                                                 String token,
                                                 String secret,
                                                 boolean nativePython,
                                                 Map<String, Object> localProxyConfig) {
    boolean rawPython = PluginService.isPythonPluginUrl(plugin.getUrl());
    Map<String, Object> map = new HashMap<>();
    map.put("api", baseUrl);
    String extension = rawPython ? ".py" : ".txt";
    String contentUrl = baseUrl + "/plugins/" + contentToken + "/" + plugin.getId() + extension;
    map.put(rawPython ? "loader" : "source", contentUrl);
    map.put("token", token.isBlank() ? "-" : token);
    map.put("secret", secret);
    map.put("local_proxy_config", rawPython || !nativePython ? localProxyConfig : new HashMap<>());
    if (StringUtils.isNotBlank(plugin.getExtend())) {
        map.put("data", plugin.getExtend());
    }
    return map;
}
```

- [ ] **Step 4: Wire helpers into `buildPluginSite`**

Replace the current API choice and hand-built ext map with:

```java
String url = readHostAddress("");
boolean nativePython = isNativePythonPluginRunMode();
site.put("api", selectPluginApi(plugin, nativePython, url));
```

Then create the payload through the helper:

```java
Map<String, Object> map = buildPluginExtPayload(
        plugin,
        url,
        getCurrentOrFirstToken(),
        token,
        secret,
        nativePython,
        readLocalProxyConfig()
);
```

Leave special plugin flags, filter insertion, JSON serialization, Base64 encoding, jar, and site metadata unchanged.

- [ ] **Step 5: Run service tests and verify GREEN**

Run:

```bash
mvn -q -Dtest=SubscriptionServiceTest,PluginServiceTest test
```

Expected: both test classes pass, including the `.txt` regression.

- [ ] **Step 6: Commit subscription generation**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceTest.java
git commit -m "feat: load raw Python plugins through PyProxy"
```

### Task 4: Expose `.py` support in the subscription source form

**Files:**
- Modify: `web-ui/src/views/SubscriptionsView.test.mjs`
- Modify: `web-ui/src/views/SubscriptionsView.vue`

- [ ] **Step 1: Write the failing frontend test**

Add:

```javascript
test('accepts encrypted txt and raw Python plugin addresses', () => {
  assert.equal(viewSource.includes('placeholder="https://example.com/plugin.txt 或 plugin.py"'), true)
})
```

- [ ] **Step 2: Run the frontend test and verify RED**

Run:

```bash
npm test
```

from `web-ui/`.

Expected: the new placeholder assertion fails because the view mentions only `plugin.txt`.

- [ ] **Step 3: Update the input placeholder**

Change the plugin URL input to:

```vue
<el-input v-model="pluginForm.url" style="width: 460px" placeholder="https://example.com/plugin.txt 或 plugin.py"/>
```

- [ ] **Step 4: Run the frontend tests and verify GREEN**

Run:

```bash
npm test
```

from `web-ui/`.

Expected: all Node tests pass.

- [ ] **Step 5: Commit the UI contract**

```bash
git add web-ui/src/views/SubscriptionsView.vue web-ui/src/views/SubscriptionsView.test.mjs
git commit -m "feat: show raw Python plugin support"
```

### Task 5: Verify the complete change

**Files:**
- Verify all files changed in Tasks 1-4.

- [ ] **Step 1: Run focused backend coverage**

```bash
mvn -q -Dtest=PluginServiceTest,PluginContentControllerTest,SubscriptionServiceTest test
```

Expected: all focused tests pass.

- [ ] **Step 2: Run the complete backend suite**

```bash
mvn test -q
```

Expected: exit code `0` with no test failures.

- [ ] **Step 3: Run frontend tests and production build**

From `web-ui/`:

```bash
npm test
npm run build
```

Expected: all tests pass and Vite completes the production build.

- [ ] **Step 4: Check formatting and scope**

```bash
git diff --check master...HEAD
git status --short
git diff --stat master...HEAD
```

Expected: no whitespace errors, no untracked generated files, and only the planned backend, test, frontend, spec, and plan files are present.

- [ ] **Step 5: Review generated protocol manually**

Confirm from the passing assertions that:

```text
.py -> api=csp_PyProxy, ext.loader=/plugins/{token}/{id}.py
.txt + java mode -> api=csp_PyProxy, ext.source=/plugins/{token}/{id}.txt
.txt + python mode -> api={base}/Atvp.py, ext.source=/plugins/{token}/{id}.txt
```

No additional code commit is needed unless verification uncovers a defect; any fix must start with a failing regression test.

