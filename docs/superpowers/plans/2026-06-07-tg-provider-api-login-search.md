# tg-provider API Login and Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect AList-TVBox to the same-container `tg-provider` API for Telegram SMS login, optional 2FA password login, account logout, and provider-first Telegram search.

**Architecture:** Add a small Spring `TgProviderClient` that owns localhost provider HTTP calls and typed response DTOs. Extend `TelegramController` as the browser-facing API and keep existing search response shape by mapping provider results into `dto.tg.Message`, with the old `TelegramService` search as fallback. Reuse the existing subscription page Telegram dialog, but switch the active workflow from QR/settings polling to direct provider SMS/code/password calls.

**Tech Stack:** Java 21, Spring Boot `RestTemplateBuilder`, Jackson records/classes, JUnit 5, Mockito, standalone MockMvc, Vue 3 Composition API, Element Plus, Node `node:test` source assertions.

---

## File Structure

- Modify `src/main/java/cn/har01d/alist_tvbox/dto/tg/Message.java`: add a provider factory that reuses existing private parsing helpers for name/type/link normalization.
- Create `src/main/java/cn/har01d/alist_tvbox/service/TgProviderClient.java`: provider HTTP client, typed request/response models, provider exception, search-to-message mapping.
- Modify `src/main/java/cn/har01d/alist_tvbox/web/TelegramController.java`: inject client, add login/account/logout endpoints, and prefer provider search before legacy fallback.
- Create `src/test/java/cn/har01d/alist_tvbox/dto/tg/MessageTest.java`: verify provider factory behavior.
- Create `src/test/java/cn/har01d/alist_tvbox/service/TgProviderClientTest.java`: verify provider request paths, request bodies, response parsing, and search mapping.
- Create `src/test/java/cn/har01d/alist_tvbox/web/TelegramControllerTest.java`: verify browser-facing API behavior and fallback.
- Modify `web-ui/src/views/SubscriptionsView.vue`: add visible login button and provider SMS/code/password dialog workflow.
- Modify `web-ui/src/views/SubscriptionsView.test.mjs`: assert new Telegram login workflow source shape.

---

### Task 1: Message Provider Factory

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/dto/tg/Message.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/dto/tg/MessageTest.java`

- [ ] **Step 1: Write the failing DTO test**

Create `src/test/java/cn/har01d/alist_tvbox/dto/tg/MessageTest.java`:

```java
package cn.har01d.alist_tvbox.dto.tg;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MessageTest {
    @Test
    void shouldCreateMessageFromProviderLink() {
        Message message = Message.fromProvider(
                12,
                "Quark_Share_Channel",
                "名称：2026年6月6日 短剧更新目录12\n描述：测试",
                "https://pan.quark.cn/s/8a16ab9c06b9",
                "2026-06-07T12:00:00Z");

        assertThat(message.getId()).isEqualTo(12);
        assertThat(message.getChannel()).isEqualTo("Quark_Share_Channel");
        assertThat(message.getContent()).contains("短剧更新目录12");
        assertThat(message.getLink()).isEqualTo("https://pan.quark.cn/s/8a16ab9c06b9");
        assertThat(message.getType()).isEqualTo("5");
        assertThat(message.getName()).isEqualTo("2026年6月6日 短剧更新目录12");
        assertThat(message.getTime()).isEqualTo(Instant.parse("2026-06-07T12:00:00Z"));
    }

    @Test
    void shouldUseProviderIdWhenTelegramMessageIdDoesNotFitInt() {
        Message message = Message.fromProvider(
                7,
                4_000_000_000L,
                "channel",
                "名称：测试",
                "https://pan.baidu.com/s/1Zc_e4792cuvucfI-ZZts0Q?pwd=ruub",
                "2026-06-07T12:00:00Z");

        assertThat(message.getId()).isEqualTo(7);
        assertThat(message.getType()).isEqualTo("10");
    }
}
```

- [ ] **Step 2: Run the DTO test to verify RED**

Run:

```bash
mvn -Dtest=MessageTest test
```

Expected: FAIL because `Message.fromProvider(...)` does not exist.

- [ ] **Step 3: Add the provider factory**

In `Message.java`, add these public static methods after the existing constructors:

```java
public static Message fromProvider(int providerId,
                                   String channel,
                                   String content,
                                   String link,
                                   String time) {
    return fromProvider(providerId, null, channel, content, link, time);
}

public static Message fromProvider(int providerId,
                                   Long telegramMessageId,
                                   String channel,
                                   String content,
                                   String link,
                                   String time) {
    Message message = new Message();
    message.id = resolveProviderMessageId(providerId, telegramMessageId);
    message.parseTime(time);
    message.content = content == null ? "" : content;
    message.link = fixLink(link);
    message.type = parseType(message.link);
    message.name = message.parseName();
    message.channel = channel;
    return message;
}

private static int resolveProviderMessageId(int providerId, Long telegramMessageId) {
    if (telegramMessageId == null || telegramMessageId > Integer.MAX_VALUE || telegramMessageId < Integer.MIN_VALUE) {
        return providerId;
    }
    return telegramMessageId.intValue();
}
```

- [ ] **Step 4: Run the DTO test to verify GREEN**

Run:

```bash
mvn -Dtest=MessageTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/dto/tg/Message.java src/test/java/cn/har01d/alist_tvbox/dto/tg/MessageTest.java
git commit -m "feat: map tg provider messages"
```

---

### Task 2: Provider HTTP Client

**Files:**
- Create: `src/main/java/cn/har01d/alist_tvbox/service/TgProviderClient.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/service/TgProviderClientTest.java`

- [ ] **Step 1: Write the failing client tests**

Create `src/test/java/cn/har01d/alist_tvbox/service/TgProviderClientTest.java`:

```java
package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.tg.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

class TgProviderClientTest {
    private MockRestServiceServer server;
    private TgProviderClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        AppProperties appProperties = new AppProperties();
        appProperties.setTgTimeout(1234);
        RestTemplateBuilder builder = new RestTemplateBuilder()
                .requestFactory(() -> restTemplate.getRequestFactory());
        client = new TgProviderClient(builder, appProperties, new ObjectMapper());
    }

    @Test
    void shouldSendCodeWithPhoneBody() {
        server.expect(once(), requestTo("http://127.0.0.1:6000/api/login/send-code"))
                .andExpect(method(POST))
                .andExpect(content().json("{\"phone\":\"+8613800000000\"}"))
                .andRespond(withSuccess("{\"status\":\"LOGIN_REQUIRED\"}", MediaType.APPLICATION_JSON));

        TgProviderClient.LoginResponse response = client.sendCode("+8613800000000");

        assertThat(response.status()).isEqualTo("LOGIN_REQUIRED");
        server.verify();
    }

    @Test
    void shouldParseFirstAccount() {
        server.expect(once(), requestTo("http://127.0.0.1:6000/api/accounts"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        [{"id":3,"username":"u","first_name":"First","last_name":"Last","phone":"+86138"}]
                        """, MediaType.APPLICATION_JSON));

        List<TgProviderClient.Account> accounts = client.accounts();

        assertThat(accounts).hasSize(1);
        assertThat(accounts.getFirst().id()).isEqualTo(3);
        assertThat(accounts.getFirst().username()).isEqualTo("u");
        assertThat(accounts.getFirst().phone()).isEqualTo("+86138");
        server.verify();
    }

    @Test
    void shouldDeleteAccountById() {
        server.expect(once(), requestTo("http://127.0.0.1:6000/api/accounts/3"))
                .andExpect(method(DELETE))
                .andRespond(withSuccess());

        client.deleteAccount(3);

        server.verify();
    }

    @Test
    void shouldMapProviderSearchItemsToMessages() {
        server.expect(once(), requestTo("http://127.0.0.1:6000/api/search?q=%E7%9F%AD%E5%89%A7&limit=100"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"items":[{"id":1,"telegram_message_id":123,"text":"名称：短剧合集","date":"2026-06-07T12:00:00Z","channel_title":"热心用户","channel_username":"Quark_Share_Group","links":[{"type":"quark","url":"https://pan.quark.cn/s/abc","password":""},{"type":"baidu","url":"https://pan.baidu.com/s/1abc?pwd=ruub","password":"ruub"}]}]}
                        """, MediaType.APPLICATION_JSON));

        List<Message> messages = client.searchMessages("短剧", 100);

        assertThat(messages).hasSize(2);
        assertThat(messages.getFirst().getId()).isEqualTo(123);
        assertThat(messages.getFirst().getChannel()).isEqualTo("Quark_Share_Group");
        assertThat(messages.getFirst().getType()).isEqualTo("5");
        assertThat(messages.get(1).getType()).isEqualTo("10");
        server.verify();
    }
}
```

- [ ] **Step 2: Run the client tests to verify RED**

Run:

```bash
mvn -Dtest=TgProviderClientTest test
```

Expected: FAIL because `TgProviderClient` does not exist.

- [ ] **Step 3: Implement `TgProviderClient`**

Create `src/main/java/cn/har01d/alist_tvbox/service/TgProviderClient.java`:

```java
package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.tg.Message;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class TgProviderClient {
    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:6000";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public TgProviderClient(RestTemplateBuilder builder, AppProperties appProperties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.baseUrl = DEFAULT_BASE_URL;
        Duration timeout = Duration.ofMillis(appProperties.getTgTimeout());
        this.restTemplate = builder
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .build();
    }

    public Status status() {
        return get("/api/status", Status.class);
    }

    public List<Account> accounts() {
        Account[] accounts = get("/api/accounts", Account[].class);
        return accounts == null ? List.of() : List.of(accounts);
    }

    public void deleteAccount(long id) {
        restTemplate.exchange(baseUrl + "/api/accounts/{id}", HttpMethod.DELETE, HttpEntity.EMPTY, Void.class, id);
    }

    public LoginResponse sendCode(String phone) {
        return post("/api/login/send-code", Map.of("phone", phone), LoginResponse.class);
    }

    public LoginResponse signIn(String phone, String code) {
        return post("/api/login/sign-in", Map.of("phone", phone, "code", code), LoginResponse.class);
    }

    public Account password(String phone, String password) {
        return post("/api/login/password", Map.of("phone", phone, "password", password), Account.class);
    }

    public List<Message> searchMessages(String keyword, int limit) {
        SearchResponse response = search(keyword, limit);
        if (response == null || response.items() == null) {
            return List.of();
        }
        List<Message> messages = new ArrayList<>();
        for (SearchItem item : response.items()) {
            String channel = StringUtils.defaultIfBlank(item.channelUsername(), item.channelTitle());
            if (item.links() == null) {
                continue;
            }
            for (ProviderLink link : item.links()) {
                if (link == null || StringUtils.isBlank(link.url())) {
                    continue;
                }
                Message message = Message.fromProvider(
                        item.id(),
                        item.telegramMessageId(),
                        channel,
                        item.text(),
                        link.url(),
                        item.date());
                if (StringUtils.isNotBlank(message.getType())) {
                    messages.add(message);
                }
            }
        }
        return messages;
    }

    public SearchResponse search(String keyword, int limit) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/search")
                .queryParam("q", keyword)
                .queryParam("limit", limit)
                .encode()
                .toUriString();
        return restTemplate.getForObject(url, SearchResponse.class);
    }

    private <T> T get(String path, Class<T> type) {
        try {
            return restTemplate.getForObject(baseUrl + path, type);
        } catch (RestClientException e) {
            throw new TgProviderException("tg-provider request failed: " + path, e);
        }
    }

    private <T> T post(String path, Object body, Class<T> type) {
        try {
            return restTemplate.postForObject(baseUrl + path, body, type);
        } catch (RestClientException e) {
            throw new TgProviderException("tg-provider request failed: " + path, e);
        }
    }

    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new TgProviderException("serialize tg-provider payload failed", e);
        }
    }

    public static class TgProviderException extends RuntimeException {
        public TgProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Status(String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Account(long id, String username, @JsonProperty("first_name") String firstName,
                          @JsonProperty("last_name") String lastName, String phone) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoginResponse(String status, @JsonProperty("password_required") boolean passwordRequired) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchResponse(List<SearchItem> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchItem(int id,
                             @JsonProperty("telegram_message_id") Long telegramMessageId,
                             String text,
                             String date,
                             @JsonProperty("channel_title") String channelTitle,
                             @JsonProperty("channel_username") String channelUsername,
                             List<ProviderLink> links) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProviderLink(String type, String url, String password) {
    }
}
```

- [ ] **Step 4: Run the client tests to verify GREEN**

Run:

```bash
mvn -Dtest=TgProviderClientTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/TgProviderClient.java src/test/java/cn/har01d/alist_tvbox/service/TgProviderClientTest.java
git commit -m "feat: add tg provider client"
```

---

### Task 3: Telegram Controller Provider API

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/web/TelegramController.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/web/TelegramControllerTest.java`

- [ ] **Step 1: Write the failing controller tests**

Create `src/test/java/cn/har01d/alist_tvbox/web/TelegramControllerTest.java`:

```java
package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.config.RestErrorHandler;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.entity.TelegramChannelRepository;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.service.TelegramService;
import cn.har01d.alist_tvbox.service.TgProviderClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TelegramControllerTest {
    @Mock
    private TelegramChannelRepository telegramChannelRepository;
    @Mock
    private TelegramService telegramService;
    @Mock
    private SubscriptionService subscriptionService;
    @Mock
    private TgProviderClient tgProviderClient;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TelegramController controller = new TelegramController(
                telegramChannelRepository,
                telegramService,
                subscriptionService,
                new ObjectMapper(),
                tgProviderClient);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RestErrorHandler())
                .build();
    }

    @Test
    void shouldReturnEmptyTelegramUserWhenProviderHasNoAccounts() throws Exception {
        when(tgProviderClient.accounts()).thenReturn(List.of());

        mockMvc.perform(get("/api/telegram/user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(0))
                .andExpect(jsonPath("$.username").value(""))
                .andExpect(jsonPath("$.phone").value(""));
    }

    @Test
    void shouldReturnFirstProviderAccountAsTelegramUser() throws Exception {
        when(tgProviderClient.accounts()).thenReturn(List.of(
                new TgProviderClient.Account(9, "tester", "Test", "User", "+86138")));

        mockMvc.perform(get("/api/telegram/user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.username").value("tester"))
                .andExpect(jsonPath("$.first_name").value("Test"))
                .andExpect(jsonPath("$.last_name").value("User"))
                .andExpect(jsonPath("$.phone").value("+86138"));
    }

    @Test
    void shouldProxyLoginRequestsToProvider() throws Exception {
        when(tgProviderClient.sendCode("+86138")).thenReturn(new TgProviderClient.LoginResponse("LOGIN_REQUIRED", false));
        when(tgProviderClient.signIn("+86138", "1234")).thenReturn(new TgProviderClient.LoginResponse("LOGIN_REQUIRED", true));

        mockMvc.perform(post("/api/telegram/login/send-code")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"+86138\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOGIN_REQUIRED"));

        mockMvc.perform(post("/api/telegram/login/sign-in")
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"+86138\",\"code\":\"1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.password_required").value(true));
    }

    @Test
    void shouldDeleteFirstAccountOnLogout() throws Exception {
        when(tgProviderClient.accounts()).thenReturn(List.of(
                new TgProviderClient.Account(9, "tester", "Test", "User", "+86138")));

        mockMvc.perform(post("/api/telegram/logout"))
                .andExpect(status().isOk());

        verify(tgProviderClient).deleteAccount(9);
    }

    @Test
    void shouldUseProviderSearchBeforeLegacySearch() throws Exception {
        Message providerMessage = Message.fromProvider(1, "channel", "名称：短剧", "https://pan.quark.cn/s/abc", "2026-06-07T12:00:00Z");
        when(tgProviderClient.searchMessages("短剧", 100)).thenReturn(List.of(providerMessage));

        mockMvc.perform(get("/api/telegram/search").param("wd", "短剧"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].link").value("https://pan.quark.cn/s/abc"));

        verifyNoInteractions(telegramService);
    }

    @Test
    void shouldFallbackToLegacySearchWhenProviderReturnsEmpty() throws Exception {
        Message legacyMessage = Message.fromProvider(2, "legacy", "名称：旧搜索", "https://pan.baidu.com/s/1abc", "2026-06-07T12:00:00Z");
        when(tgProviderClient.searchMessages("短剧", 100)).thenReturn(List.of());
        when(telegramService.search("短剧", 100, false, false)).thenReturn(List.of(legacyMessage));

        mockMvc.perform(get("/api/telegram/search").param("wd", "短剧"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].channel").value("legacy"));

        verify(telegramService).search(eq("短剧"), eq(100), eq(false), eq(false));
    }
}
```

- [ ] **Step 2: Run controller tests to verify RED**

Run:

```bash
mvn -Dtest=TelegramControllerTest test
```

Expected: FAIL because the controller constructor/endpoints do not yet support `TgProviderClient`.

- [ ] **Step 3: Implement controller endpoints and fallback**

Modify `TelegramController.java`:

```java
private final TgProviderClient tgProviderClient;

public TelegramController(TelegramChannelRepository telegramChannelRepository,
                          TelegramService telegramService,
                          SubscriptionService subscriptionService,
                          ObjectMapper objectMapper,
                          TgProviderClient tgProviderClient) {
    this.telegramChannelRepository = telegramChannelRepository;
    this.telegramService = telegramService;
    this.subscriptionService = subscriptionService;
    this.objectMapper = objectMapper;
    this.tgProviderClient = tgProviderClient;
}

@GetMapping("/api/telegram/search")
public List<Message> searchByKeyword(String wd) {
    try {
        List<Message> messages = tgProviderClient.searchMessages(wd, 100);
        if (!messages.isEmpty()) {
            return messages;
        }
    } catch (RuntimeException e) {
        log.warn("tg-provider search failed, fallback to legacy telegram search", e);
    }
    return telegramService.search(wd, 100, false, false);
}

@GetMapping("/api/telegram/provider/status")
public TgProviderClient.Status providerStatus() {
    return tgProviderClient.status();
}

@GetMapping("/api/telegram/user")
public Map<String, Object> user() {
    try {
        return tgProviderClient.accounts().stream()
                .findFirst()
                .<Map<String, Object>>map(this::toTelegramUser)
                .orElseGet(this::emptyTelegramUser);
    } catch (RuntimeException e) {
        log.warn("get tg-provider account failed", e);
        return emptyTelegramUser();
    }
}

@PostMapping("/api/telegram/login/send-code")
public TgProviderClient.LoginResponse sendCode(@RequestBody TelegramLoginRequest request) {
    return tgProviderClient.sendCode(request.phone());
}

@PostMapping("/api/telegram/login/sign-in")
public TgProviderClient.LoginResponse signIn(@RequestBody TelegramLoginRequest request) {
    return tgProviderClient.signIn(request.phone(), request.code());
}

@PostMapping("/api/telegram/login/password")
public TgProviderClient.Account password(@RequestBody TelegramLoginRequest request) {
    return tgProviderClient.password(request.phone(), request.password());
}

@PostMapping("/api/telegram/logout")
public void logout() {
    try {
        tgProviderClient.accounts().stream()
                .findFirst()
                .ifPresent(account -> tgProviderClient.deleteAccount(account.id()));
    } catch (RuntimeException e) {
        log.warn("tg-provider logout failed", e);
    }
}

private Map<String, Object> toTelegramUser(TgProviderClient.Account account) {
    return Map.of(
            "id", account.id(),
            "username", StringUtils.defaultString(account.username()),
            "first_name", StringUtils.defaultString(account.firstName()),
            "last_name", StringUtils.defaultString(account.lastName()),
            "phone", StringUtils.defaultString(account.phone()));
}

private Map<String, Object> emptyTelegramUser() {
    return Map.of("id", 0, "username", "", "first_name", "", "last_name", "", "phone", "");
}

public record TelegramLoginRequest(String phone, String code, String password) {
}
```

- [ ] **Step 4: Run controller tests to verify GREEN**

Run:

```bash
mvn -Dtest=TelegramControllerTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/web/TelegramController.java src/test/java/cn/har01d/alist_tvbox/web/TelegramControllerTest.java
git commit -m "feat: expose tg provider telegram api"
```

---

### Task 4: Subscription Page Telegram Login UI

**Files:**
- Modify: `web-ui/src/views/SubscriptionsView.vue`
- Test: `web-ui/src/views/SubscriptionsView.test.mjs`

- [ ] **Step 1: Write failing frontend source tests**

Append these tests to `web-ui/src/views/SubscriptionsView.test.mjs`:

```js
test('shows telegram login button in subscription toolbar', () => {
  assert.equal(viewSource.includes('@click="handleLogin">登录 Telegram</el-button>'), true)
})

test('uses tg provider sms login endpoints instead of qr polling workflow', () => {
  assert.equal(viewSource.includes('/api/telegram/login/send-code'), true)
  assert.equal(viewSource.includes('/api/telegram/login/sign-in'), true)
  assert.equal(viewSource.includes('/api/telegram/login/password'), true)
  assert.equal(viewSource.includes('/api/telegram/login\\''), false)
  assert.equal(viewSource.includes('/api/telegram/reset'), false)
  assert.equal(viewSource.includes('tgAuthType'), false)
  assert.equal(viewSource.includes('setScanned'), false)
})
```

- [ ] **Step 2: Run frontend test to verify RED**

Run:

```bash
node --test web-ui/src/views/SubscriptionsView.test.mjs
```

Expected: FAIL because the toolbar button is missing and the old login workflow is still present.

- [ ] **Step 3: Replace the visible workflow**

Modify `web-ui/src/views/SubscriptionsView.vue`:

Add the toolbar button before the primary add button:

```vue
<el-button @click="handleLogin">登录 Telegram</el-button>
```

Replace the Telegram dialog body with SMS/code/password controls:

```vue
<el-dialog v-model="tgVisible" title="登录 Telegram" width="520px" @close="cancelLogin">
  <el-form>
    <el-form-item label="电话号码" label-width="120" required v-if="!user.id && tgPhase === 1">
      <el-input v-model="tgPhone" autocomplete="off" placeholder="+8612345678901"/>
      <el-button @click="sendTgPhone">发送验证码</el-button>
    </el-form-item>
    <el-form-item label="验证码" label-width="120" required v-if="!user.id && tgPhase === 3">
      <el-input v-model="tgCode" autocomplete="off"/>
      <el-button @click="sendTgCode">登录</el-button>
    </el-form-item>
    <el-form-item label="密码" label-width="120" required v-if="!user.id && tgPhase === 5">
      <el-input v-model="tgPassword" type="password" show-password autocomplete="off"/>
      <el-button @click="sendTgPassword">确认</el-button>
    </el-form-item>
    <div v-if="user.id">
      <div>登录成功</div>
      <div>用户ID： {{ user.id }}</div>
      <div>用户名： {{ user.username }}</div>
      <div>姓名： {{ user.first_name }} {{ user.last_name }}</div>
      <div>电话： {{ user.phone }}</div>
    </div>
  </el-form>
  <template #footer>
    <span class="dialog-footer">
      <el-button type="danger" @click="logout" v-if="user.id">退出登录</el-button>
      <el-button @click="cancelLogin">取消</el-button>
    </span>
  </template>
</el-dialog>
```

Remove these old state/functions because the provider workflow does not use QR polling or settings writes:

```ts
const tgAuthType = ref('qr')
const base64QrCode = ref('')
let timer = 0
const login = () => { ... }
const loadQrCode = () => { ... }
const reset = () => { ... }
const setAuthType = () => { ... }
const setScanned = () => { ... }
```

Replace the active methods with:

```ts
const emptyTelegramUser = () => ({
  id: 0,
  username: '',
  first_name: '',
  last_name: '',
  phone: ''
})

const loadTelegramUser = () => {
  return axios.get('/api/telegram/user').then(({data}) => {
    user.value = data || emptyTelegramUser()
    tgPhase.value = user.value.id ? 0 : 1
  })
}

const handleLogin = () => {
  tgPhone.value = ''
  tgCode.value = ''
  tgPassword.value = ''
  loadTelegramUser()
  tgVisible.value = true
}

const cancelLogin = () => {
  tgVisible.value = false
}

const logout = () => {
  axios.post('/api/telegram/logout').then(() => {
    ElMessage.success('退出登录成功')
    user.value = emptyTelegramUser()
    tgPhase.value = 1
  })
}

const sendTgPhone = () => {
  if (!tgPhone.value) {
    return
  }
  axios.post('/api/telegram/login/send-code', {phone: tgPhone.value}).then(() => {
    tgPhase.value = 3
    ElMessage.success('验证码已发送')
  }, () => {
    ElMessage.error('发送验证码失败')
  })
}

const sendTgCode = () => {
  if (!tgCode.value) {
    return
  }
  axios.post('/api/telegram/login/sign-in', {phone: tgPhone.value, code: tgCode.value}).then(({data}) => {
    if (data && data.password_required) {
      tgPhase.value = 5
      return
    }
    loadTelegramUser()
  }, () => {
    ElMessage.error('登录失败')
  })
}

const sendTgPassword = () => {
  if (!tgPassword.value) {
    return
  }
  axios.post('/api/telegram/login/password', {phone: tgPhone.value, password: tgPassword.value}).then(() => {
    loadTelegramUser()
  }, () => {
    ElMessage.error('密码验证失败')
  })
}
```

- [ ] **Step 4: Run frontend source test to verify GREEN**

Run:

```bash
node --test web-ui/src/views/SubscriptionsView.test.mjs
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add web-ui/src/views/SubscriptionsView.vue web-ui/src/views/SubscriptionsView.test.mjs
git commit -m "feat: add tg provider login ui"
```

---

### Task 5: Full Verification

**Files:**
- No source files unless verification exposes a failure.

- [ ] **Step 1: Run all backend tests**

Run:

```bash
mvn test
```

Expected: BUILD SUCCESS. If a failure appears in a touched test or touched code path, fix it in the smallest relevant file and rerun `mvn test`.

- [ ] **Step 2: Run frontend source tests**

Run:

```bash
node --test web-ui/src/**/*.test.mjs
```

Expected: all Node source tests pass. If shell glob expansion does not include nested files in this environment, run:

```bash
find web-ui/src -name '*.test.mjs' -print0 | xargs -0 node --test
```

- [ ] **Step 3: Check worktree**

Run:

```bash
git status --short --branch
```

Expected: branch `tg-provider-packaging` with no uncommitted changes.

