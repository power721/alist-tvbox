# Restore /open /node /cat + random basic-auth — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use `- [ ]` checkboxes.

**Goal:** Restore `/open`, `/open/{token}`, `/node/{token}/{file}` endpoints + `/cat/**` static handler (removed in `8b6c748b`) and the two SubscriptionsView link rows (removed in `18f300e2`), with basic auth changed from hardcoded `alist:alist` to a once-generated random username/password.

**Architecture:** TokenFilter re-gates `/open` `/node` `/cat` with constant-time Basic-auth comparison against a randomly-init pair. SettingService owns the pair lifecycle (generate-once-and-persist, mirroring `api_key`), exposes ADMIN/CLIENT read + regenerate. TvBoxController re-adds the 3 cat endpoints + 2 credential endpoints. Frontend fetches the pair and embeds in the URL.

**Tech Stack:** Spring Boot 3 / Java 21, Mockito + MockMvc, Vue 3 + TS.

## Global Constraints
- Spec: `docs/superpowers/specs/2026-06-28-restore-open-node-basic-auth-design.md`
- No new DTO/entity packages → no `reflect-config.json` / `Main.java` scan / native-image changes. Endpoints return `Map`/`String`.
- Constant-time compare preserved (`MessageDigest.isEqual`).
- CLIENT treated as ADMIN → no `WebSecurityConfiguration` edit (existing `/api/**`→ADMIN+CLIENT covers new endpoints).
- Dead `// TODO:` cat.zip line in `syncCat` stays removed.
- Build cmd: `mvn -q test`; lint: `cd web-ui && npm run lint`.

## File Structure
- `util/Constants.java` — add `BASIC_AUTH_USERNAME`/`BASIC_AUTH_PASSWORD`.
- `auth/TokenFilter.java` — basic-auth gate + setter + ctor read.
- `service/SettingService.java` — credential lifecycle (init/get/regenerate).
- `service/SubscriptionService.java` — restore `open()` / `node()`.
- `web/TvBoxController.java` — 3 cat endpoints + 2 cred endpoints + inject SettingService.
- `config/MvcConfig.java` — `/cat/**` static handler.
- `web-ui/src/views/SubscriptionsView.vue` — fetch creds + 2 link rows.
- Tests: extend `auth/TokenFilterTest.java`; new `service/SettingServiceBasicAuthTest.java`; fix `web/TvBoxControllerTest.java` ctor.

---

### Task 1: TokenFilter basic-auth gate + Constants

**Files:** Modify `util/Constants.java`, `auth/TokenFilter.java`, `test/.../auth/TokenFilterTest.java`.

**Interfaces:**
- Produces: `TokenFilter.setBasicAuthCredentials(String header)`, `TokenFilter.encodeBasic(user,pass)` (package-private), constants `Constants.BASIC_AUTH_USERNAME`/`BASIC_AUTH_PASSWORD`.

- [ ] **Step 1: Add constants**

`Constants.java` — after line 44 (`ALIST_LOGIN`):
```java
    public static final String BASIC_AUTH_USERNAME = "basic_auth_username";
    public static final String BASIC_AUTH_PASSWORD = "basic_auth_password";
```

- [ ] **Step 2: Write failing tests** — append to `TokenFilterTest`. Add to the `settings` map in `setUp()` (after the existing `settings.put(...)` lines):
```java
        settings.put("basic_auth_username", new AtomicReference<>("catuser"));
        settings.put("basic_auth_password", new AtomicReference<>("catpass"));
```
Append test methods (reuse existing `basic()` helper):
```java
    @Test
    void openEndpointShouldRequireBasicAuth() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/open");
        MockHttpServletResponse response = new MockHttpServletResponse();
        var chain = mock(jakarta.servlet.FilterChain.class);

        tokenFilter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertEquals("Basic realm=\"alist\"", response.getHeader("Www-Authenticate"));
        org.mockito.Mockito.verifyNoInteractions(chain);
    }

    @Test
    void openEndpointShouldPassWithValidBasicAuth() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/open");
        request.addHeader("Authorization", basic("catuser", "catpass"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        var chain = mock(jakarta.servlet.FilterChain.class);

        tokenFilter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        verify(chain).doFilter(request, response);
    }

    @Test
    void catEndpointShouldRejectWrongBasicAuth() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/cat/index.config.js");
        request.addHeader("Authorization", basic("catuser", "wrong"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        var chain = mock(jakarta.servlet.FilterChain.class);

        tokenFilter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        org.mockito.Mockito.verifyNoInteractions(chain);
    }
```

- [ ] **Step 3: Run, verify fail**

`mvn -q -Dtest=TokenFilterTest test` → FAIL (creds null → no 401; `setBasicAuthCredentials` absent).

- [ ] **Step 4: Implement TokenFilter**

Imports — add with the existing `java.*` imports:
```java
import cn.har01d.alist_tvbox.util.Constants;
import java.util.Base64;
```
Field (after `private volatile String apiKey;`):
```java
    private volatile String basicAuthCredentials;
```
Constructor + setters + helper — replace the existing constructor/`setApiKey` block:
```java
    public TokenFilter(TokenService tokenService, SettingRepository settingRepository) {
        this.tokenService = tokenService;
        apiKey = settingRepository.findById("api_key").map(Setting::getValue).orElse("");
        basicAuthCredentials = loadBasicAuthCredentials(settingRepository);
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setBasicAuthCredentials(String basicAuthCredentials) {
        this.basicAuthCredentials = basicAuthCredentials;
    }

    private static String loadBasicAuthCredentials(SettingRepository settingRepository) {
        String username = settingRepository.findById(Constants.BASIC_AUTH_USERNAME).map(Setting::getValue).orElse("");
        String password = settingRepository.findById(Constants.BASIC_AUTH_PASSWORD).map(Setting::getValue).orElse("");
        if (username.isEmpty() || password.isEmpty()) {
            return null;
        }
        return encodeBasic(username, password);
    }

    static String encodeBasic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
```
Gate — in `doFilterInternal`, insert **after** the `apiKey` block (after its closing `}`) and **before** `String token = getToken(request);`:
```java
            String uri = request.getRequestURI();
            if (uri.startsWith("/open") || uri.startsWith("/node") || uri.startsWith("/cat")) {
                String auth = request.getHeader("Authorization");
                boolean ok = basicAuthCredentials != null && auth != null
                        && MessageDigest.isEqual(basicAuthCredentials.getBytes(StandardCharsets.UTF_8), auth.getBytes(StandardCharsets.UTF_8));
                if (!ok) {
                    response.setHeader("Www-Authenticate", "Basic realm=\"alist\"");
                    response.sendError(401);
                    return;
                }
                filterChain.doFilter(request, response);
                return;
            }
```

- [ ] **Step 5: Run, verify pass**

`mvn -q -Dtest=TokenFilterTest test` → PASS (all 4 tests).

- [ ] **Step 6: Commit**

`git add src/main/java/cn/har01d/alist_tvbox/util/Constants.java src/main/java/cn/har01d/alist_tvbox/auth/TokenFilter.java src/test/java/cn/har01d/alist_tvbox/auth/TokenFilterTest.java && git commit -m "feat: restore basic-auth gate for /open /node /cat with constant-time compare"`

---

### Task 2: SettingService credential lifecycle

**Files:** Modify `service/SettingService.java`; create `test/.../service/SettingServiceBasicAuthTest.java`.

**Interfaces:**
- Consumes: `Constants.BASIC_AUTH_USERNAME/PASSWORD`, `TokenFilter.setBasicAuthCredentials` (Task 1).
- Produces: `SettingService.initBasicAuthCredentials()`, `getBasicAuthCredentials() → Map<String,String>`, `regenerateBasicAuthCredentials() → Map<String,String>`.

- [ ] **Step 1: Write failing test** — `SettingServiceBasicAuthTest.java`:
```java
package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.auth.TokenFilter;
import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.util.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettingServiceBasicAuthTest {
    @Mock JdbcTemplate jdbcTemplate;
    @Mock Environment environment;
    @Mock AppProperties appProperties;
    @Mock TmdbService tmdbService;
    @Mock AListLocalService aListLocalService;
    @Mock TokenFilter tokenFilter;
    @Mock SettingRepository settingRepository;
    @Mock DriverAccountRepository driverAccountRepository;
    @Mock ObjectMapper objectMapper;
    @Mock GitHubProxyService gitHubProxyService;
    @Mock DatabaseBackupService databaseBackupService;

    private SettingService service;
    private final Map<String, String> store = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = new SettingService(jdbcTemplate, environment, appProperties, tmdbService,
                aListLocalService, tokenFilter, settingRepository, driverAccountRepository,
                objectMapper, gitHubProxyService, databaseBackupService);
        store.clear();
        lenient().when(settingRepository.findById(org.mockito.ArgumentMatchers.anyString())).thenAnswer(inv -> {
            String k = inv.getArgument(0);
            return store.containsKey(k) ? Optional.of(new Setting(k, store.get(k))) : Optional.empty();
        });
        lenient().when(settingRepository.save(any(Setting.class))).thenAnswer(inv -> {
            Setting s = inv.getArgument(0);
            store.put(s.getName(), s.getValue());
            return s;
        });
    }

    @Test
    void shouldGenerateAndPersistWhenMissing() {
        service.initBasicAuthCredentials();
        assertEquals(8, store.get(Constants.BASIC_AUTH_USERNAME).length());
        assertEquals(16, store.get(Constants.BASIC_AUTH_PASSWORD).length());
        verify(tokenFilter).setBasicAuthCredentials(contains("Basic "));
    }

    @Test
    void shouldBeIdempotentWhenPresent() {
        store.put(Constants.BASIC_AUTH_USERNAME, "existinguser");
        store.put(Constants.BASIC_AUTH_PASSWORD, "existingpass");
        service.initBasicAuthCredentials();
        assertEquals("existinguser", store.get(Constants.BASIC_AUTH_USERNAME));
        verify(settingRepository, never()).save(any(Setting.class));
        verify(tokenFilter).setBasicAuthCredentials("Basic ZXhpc3Rpbmd1c2VyOmV4aXN0aW5ncGFzcw==");
    }

    @Test
    void regenerateShouldProduceNewPair() {
        store.put(Constants.BASIC_AUTH_USERNAME, "olduser");
        store.put(Constants.BASIC_AUTH_PASSWORD, "oldpass");
        Map<String, String> result = service.regenerateBasicAuthCredentials();
        assertNotEquals("olduser", result.get("username"));
        assertEquals(result.get("username"), store.get(Constants.BASIC_AUTH_USERNAME));
        verify(tokenFilter).setBasicAuthCredentials(contains("Basic "));
    }

    @Test
    void getShouldReturnPersistedPair() {
        store.put(Constants.BASIC_AUTH_USERNAME, "u1");
        store.put(Constants.BASIC_AUTH_PASSWORD, "p1");
        Map<String, String> r = service.getBasicAuthCredentials();
        assertEquals("u1", r.get("username"));
        assertEquals("p1", r.get("password"));
    }
}
```
(`ZXhpc3Rpbmd1c2VyOmV4aXN0aW5ncGFzcw==` = base64 of `existinguser:existingpass`.)

- [ ] **Step 2: Run, verify fail**

`mvn -q -Dtest=SettingServiceBasicAuthTest test` → FAIL (methods absent).

- [ ] **Step 3: Implement** — `SettingService.java`. Imports: add `import cn.har01d.alist_tvbox.util.IdUtils;`, `import java.nio.charset.StandardCharsets;`, `import java.util.Base64;`. In `setup()`, after the `if (!settingRepository.existsById("api_key")) { generateApiKey(); }` block, add:
```java
        initBasicAuthCredentials();
```
Add methods (e.g. right after `generateApiKey()`):
```java
    public void initBasicAuthCredentials() {
        String username = settingRepository.findById(Constants.BASIC_AUTH_USERNAME).map(Setting::getValue).orElse("");
        String password = settingRepository.findById(Constants.BASIC_AUTH_PASSWORD).map(Setting::getValue).orElse("");
        if (username.isEmpty() || password.isEmpty()) {
            username = IdUtils.generate(8);
            password = IdUtils.generate(16);
            settingRepository.save(new Setting(Constants.BASIC_AUTH_USERNAME, username));
            settingRepository.save(new Setting(Constants.BASIC_AUTH_PASSWORD, password));
            log.info("generated basic auth credentials (username={})", username);
        }
        tokenFilter.setBasicAuthCredentials(encodeBasicAuthHeader(username, password));
    }

    public Map<String, String> getBasicAuthCredentials() {
        String username = settingRepository.findById(Constants.BASIC_AUTH_USERNAME).map(Setting::getValue).orElse("");
        String password = settingRepository.findById(Constants.BASIC_AUTH_PASSWORD).map(Setting::getValue).orElse("");
        return Map.of("username", username, "password", password);
    }

    public Map<String, String> regenerateBasicAuthCredentials() {
        String username = IdUtils.generate(8);
        String password = IdUtils.generate(16);
        settingRepository.save(new Setting(Constants.BASIC_AUTH_USERNAME, username));
        settingRepository.save(new Setting(Constants.BASIC_AUTH_PASSWORD, password));
        tokenFilter.setBasicAuthCredentials(encodeBasicAuthHeader(username, password));
        log.info("regenerated basic auth credentials (username={})", username);
        return Map.of("username", username, "password", password);
    }

    private static String encodeBasicAuthHeader(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
```

- [ ] **Step 4: Run, verify pass**

`mvn -q -Dtest=SettingServiceBasicAuthTest test` → PASS.

- [ ] **Step 5: Commit**

`git add src/main/java/cn/har01d/alist_tvbox/service/SettingService.java src/test/java/cn/har01d/alist_tvbox/service/SettingServiceBasicAuthTest.java && git commit -m "feat: random one-time basic-auth credentials lifecycle in SettingService"`

---

### Task 3: Restore /open /node /cat endpoints + cred endpoints + MvcConfig

**Files:** Modify `service/SubscriptionService.java`, `web/TvBoxController.java`, `config/MvcConfig.java`, `test/.../web/TvBoxControllerTest.java`.

**Interfaces:**
- Consumes: `SubscriptionService.checkToken/open/node`, `SettingService.getBasicAuthCredentials/regenerateBasicAuthCredentials`.
- Produces: `GET /open`, `GET /open/{token}`, `GET /node/{token}/{file}`, `GET /api/basic-auth-credentials`, `POST /api/basic-auth-credentials/regenerate`, `/cat/**` static.

- [ ] **Step 1: Restore service methods** — `SubscriptionService.java`, insert after `findAll()` (after line 421, before `syncCat()`):
```java
    public Map<String, Object> open() throws IOException {
        Path path = Utils.getWebPath("cat", "config_open.json");
        String json = Files.readString(path).replace("﻿", "");

        Map<String, Object> config = objectMapper.readValue(json, Map.class);

        path = Utils.getWebPath("cat", "my.json");
        if (Files.exists(path)) {
            try {
                log.info("read {}", path);
                String ext = Files.readString(path);
                Map<String, Object> source = objectMapper.readValue(ext, Map.class);
                mergeOpen(config, source);
            } catch (Exception e) {
                log.warn("", e);
            }
        }

        addCatSites(config);

        json = objectMapper.writeValueAsString(config);
        json = replaceOpen(json);

        return objectMapper.readValue(json, Map.class);
    }

    public String node(String file) throws IOException {
        log.debug("load file {}", file);
        if (file.contains("index.config.js")) {
            Path config = Utils.getWebPath("cat", "index.config.js");
            String json = Files.readString(config);
            String secret = appProperties.isEnabledToken() ? ("/" + tokens.split(",")[0]) : "";
            json = json.replace("VOD_URL", readHostAddress("/vod" + secret));
            json = json.replace("VOD1_URL", readHostAddress("/vod1" + secret));
            json = json.replace("BILIBILI_URL", readHostAddress("/bilibili" + secret));
            json = json.replace("YOUTUBE_URL", readHostAddress("/youtube" + secret));
            json = json.replace("EMBY_URL", readHostAddress("/emby" + secret));
            String ali = accountRepository.getFirstByMasterTrue().map(Account::getRefreshToken).orElse("");
            json = json.replace("ALI_TOKEN", ali);
            ali = accountRepository.getFirstByMasterTrue().map(Account::getOpenToken).orElse("");
            json = json.replace("ALI_OPEN_TOKEN", ali);

            String quarkCookie = panAccountRepository.findByTypeAndMasterTrue(DriverType.QUARK).map(DriverAccount::getCookie).orElse("");
            json = json.replace("QUARK_COOKIE", quarkCookie);

            String address = readHostAddress();
            json = json.replace("DOCKER_ADDRESS", address);
            json = json.replace("ATV_ADDRESS", address);

            if ("index.config.js".equals(file)) {
                return json;
            } else if ("index.config.js.md5".equals(file)) {
                return Utils.md5(json);
            }
        }
        return Files.readString(Utils.getWebPath("cat", file));
    }
```

- [ ] **Step 2: Wire controller** — `TvBoxController.java`. Add import:
```java
import cn.har01d.alist_tvbox.service.SettingService;
```
Add field + constructor param (after `objectMapper`):
```java
    private final SettingService settingService;
```
and in the constructor signature add `, SettingService settingService` (after `ObjectMapper objectMapper`) and in the body:
```java
        this.settingService = settingService;
```
Add endpoints after the `/sub/{token}/{id}` method (~line 213) and before `syncCat()`:
```java
    @GetMapping("/open")
    public Map<String, Object> open() throws IOException {
        return subscriptionService.open();
    }

    @GetMapping("/open/{token}")
    public Map<String, Object> open(@PathVariable String token) throws IOException {
        subscriptionService.checkToken(token);
        return subscriptionService.open();
    }

    @GetMapping("/node/{token}/{file}")
    public String node(@PathVariable String token, @PathVariable String file) throws IOException {
        subscriptionService.checkToken(token);
        return subscriptionService.node(file);
    }

    @GetMapping("/api/basic-auth-credentials")
    public Map<String, String> getBasicAuthCredentials() {
        return settingService.getBasicAuthCredentials();
    }

    @PostMapping("/api/basic-auth-credentials/regenerate")
    public Map<String, String> regenerateBasicAuthCredentials() {
        return settingService.regenerateBasicAuthCredentials();
    }
```

- [ ] **Step 3: Restore /cat static handler** — `MvcConfig.java`, add as the first line inside `addResourceHandlers`:
```java
        registry.addResourceHandler("/cat/**").addResourceLocations("file:" + Utils.getWebPath("cat") + "/");
```

- [ ] **Step 4: Fix + extend controller test** — `TvBoxControllerTest.java`. Add import `import cn.har01d.alist_tvbox.service.SettingService;`, `import java.util.Map;`. Add mock:
```java
    @Mock
    private SettingService settingService;
```
Update `setUp()` constructor call:
```java
        TvBoxController controller = new TvBoxController(
                tvBoxService,
                subscriptionService,
                historyService,
                deviceRepository,
                new ObjectMapper(),
                settingService
        );
```
Add test:
```java
    @Test
    void openShouldReturnConfig() throws Exception {
        when(subscriptionService.open()).thenReturn(Map.of("video", "x"));

        mockMvc.perform(get("/open"))
                .andExpect(status().isOk());
    }
```

- [ ] **Step 5: Run tests, verify pass**

`mvn -q -Dtest=TokenFilterTest,SettingServiceBasicAuthTest,TvBoxControllerTest test` → PASS. Then full compile: `mvn -q -DskipTests compile` → OK.

- [ ] **Step 6: Commit**

`git add src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java src/main/java/cn/har01d/alist_tvbox/web/TvBoxController.java src/main/java/cn/har01d/alist_tvbox/config/MvcConfig.java src/test/java/cn/har01d/alist_tvbox/web/TvBoxControllerTest.java && git commit -m "feat: restore /open /node /cat endpoints + basic-auth-credentials API"`

---

### Task 4: Frontend — SubscriptionsView link rows

**Files:** Modify `web-ui/src/views/SubscriptionsView.vue`.

- [ ] **Step 1: Add state + computed** — after `const token = ref('')` (~line 977):
```js
const basicAuthUser = ref('')
const basicAuthPass = ref('')
function withBasicAuth(base) {
  if (!basicAuthUser.value && !basicAuthPass.value) return base
  const prefix = basicAuthUser.value + ':' + basicAuthPass.value + '@'
  return base.replace('http://', 'http://' + prefix).replace('https://', 'https://' + prefix)
}
const openUrl = computed(() => withBasicAuth(currentUrl) + '/open' + token.value)
const nodeUrl = computed(() => withBasicAuth(currentUrl) + '/node' + (token.value ? token.value : '/-') + '/index.config.js')
```
(`computed` already imported line 859; `currentUrl` defined line 954.)

- [ ] **Step 2: Fetch creds on mount** — in `onMounted` (~line 2721), after `loadDevices()`:
```js
  axios.get('/api/basic-auth-credentials').then(({data}) => {
    basicAuthUser.value = data.username
    basicAuthPass.value = data.password
  }).catch(() => {})
```

- [ ] **Step 3: Restore template rows** — insert before the `PG包本地` `<el-row>` (~line 55):
```html
    <el-row>
      猫影视配置接口：
      <a :href="openUrl" target="_blank">{{ openUrl }}</a>
    </el-row>
    <el-row>
      猫影视node配置接口：
      <a :href="nodeUrl" target="_blank">{{ nodeUrl }}</a>
    </el-row>
```

- [ ] **Step 4: Lint**

`cd web-ui && npm run lint` → no errors.

- [ ] **Step 5: Commit**

`git add web-ui/src/views/SubscriptionsView.vue && git commit -m "feat: restore cat /open /node config link rows with random basic-auth"`

---

## Manual verification (after all tasks)
1. `mvn -q -DskipTests package` (or `mvn spring-boot:run`).
2. Start; confirm log line `generated basic auth credentials (username=...)`.
3. `curl -i http://localhost:<port>/open` → 401 + `Www-Authenticate: Basic realm="alist"`.
4. `curl -u <username>:<password> -i http://localhost:<port>/open` → 200 JSON.
5. `curl -u <u>:<p> http://localhost:<port>/node/-/index.config.js` → served config (or md5 variant).
6. `curl -u <u>:<p> http://localhost:<port>/cat/index.config.js` → served.
7. SubscriptionsView shows 猫影视配置接口 / node配置接口 links with embedded `user:pass@`.

## Self-Review notes
- Spec coverage: §1 open()/node() → Task 3 Step 1. §2 endpoints → Task 3 Step 2. §3 /cat static → Task 3 Step 3. §4 TokenFilter gate → Task 1. §5 SettingService lifecycle → Task 2. §6 admin API → Task 3 Step 2. §7 frontend → Task 4. All covered.
- Type consistency: `setBasicAuthCredentials(String)`, `Constants.BASIC_AUTH_USERNAME/PASSWORD`, `getBasicAuthCredentials`/`regenerateBasicAuthCredentials` → `Map<String,String>` match across Tasks 1–3 and frontend `data.username/password`.
- No placeholders; no new DTO packages; no native-image edits.
