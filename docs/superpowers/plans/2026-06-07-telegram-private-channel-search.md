# Telegram Private Channel Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Separate public Telegram/PanSou search from private Telegram channel search, add private channel selection, move Telegram login into play config, and expose `/tgsc`.

**Architecture:** Keep `tg-provider` as a provider-shaped API and do format adaptation inside `alist-tvbox`. Add a focused private-channel service that owns provider channel selection, provider search/latest aggregation, and TvBox result mapping. Keep existing public channel management and public search behavior intact, except `/api/telegram/search` no longer tries private provider search first.

**Tech Stack:** Java 21, Spring Boot MVC, Jackson records, JPA `SettingRepository`, Vue 3, TypeScript, Element Plus, Axios, Node source tests.

---

## File Structure

- Create `src/main/java/cn/har01d/alist_tvbox/dto/tg/TgProviderChannel.java`: provider channel DTO for `/api/channels`.
- Create `src/main/java/cn/har01d/alist_tvbox/dto/tg/TgPrivateChannel.java`: browser-facing channel DTO with local `enabled`.
- Create `src/main/java/cn/har01d/alist_tvbox/dto/tg/TgPrivateChannelSelectionRequest.java`: request body for saving and syncing selected channels.
- Create `src/main/java/cn/har01d/alist_tvbox/dto/tg/TgProviderSyncResult.java`: provider per-channel sync result DTO.
- Create `src/main/java/cn/har01d/alist_tvbox/dto/tg/TgProviderSyncResponse.java`: provider batch sync response DTO.
- Modify `src/main/java/cn/har01d/alist_tvbox/service/TgProviderClient.java`: add channels, latest, sync, and channel-filtered search calls.
- Create `src/main/java/cn/har01d/alist_tvbox/service/TgPrivateChannelService.java`: selected-channel persistence, private search/latest aggregation, and TvBox mapping.
- Modify `src/main/java/cn/har01d/alist_tvbox/web/TelegramController.java`: split public/private search endpoints and add `/tgsc`.
- Modify `src/main/resources/META-INF/native-image/reflect-config.json`: add new top-level DTOs.
- Modify backend tests under `src/test/java/cn/har01d/alist_tvbox/service`, `src/test/java/cn/har01d/alist_tvbox/web`, and `src/test/java/cn/har01d/alist_tvbox/nativeimage`.
- Modify `web-ui/src/views/SearchView.vue`: add search source tabs and private `/tgsc` search endpoint.
- Modify `web-ui/src/views/VodView.vue`: keep playback-page public Telegram search only.
- Modify `web-ui/src/components/PlayConfig.vue`: rename public channel tab, add private channels tab, add Telegram management tab.
- Modify `web-ui/src/views/SubscriptionsView.vue`: remove Telegram login UI and state.
- Modify frontend source tests `web-ui/src/views/SearchView.test.mjs`, `web-ui/src/views/VodView.test.mjs`, `web-ui/src/components/PlayConfig.test.mjs`, and `web-ui/src/views/SubscriptionsView.test.mjs`.

---

### Task 1: Extend `TgProviderClient` For Channels, Latest, And Sync

**Files:**
- Create: `src/main/java/cn/har01d/alist_tvbox/dto/tg/TgProviderChannel.java`
- Create: `src/main/java/cn/har01d/alist_tvbox/dto/tg/TgProviderSyncResult.java`
- Create: `src/main/java/cn/har01d/alist_tvbox/dto/tg/TgProviderSyncResponse.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/TgProviderClient.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/service/TgProviderClientTest.java`

- [ ] **Step 1: Add failing provider-client tests**

Append these tests to `TgProviderClientTest`:

```java
@Test
void shouldParseChannelsFromItemsEnvelope() {
    server.expect(once(), requestTo("http://127.0.0.1:6000/api/channels"))
            .andExpect(method(GET))
            .andRespond(withSuccess("""
                    {"items":[{"id":7,"account_id":1,"telegram_channel_id":1001,"access_hash":2002,"title":"VIP","username":"vip_share","type":"channel","last_message_id":88,"last_sync_time":"2026-06-07T12:00:00Z","created_at":"2026-06-07T06:00:00Z","updated_at":"2026-06-07T12:00:00Z"}]}
                    """, MediaType.APPLICATION_JSON));

    List<TgProviderChannel> channels = client.channels();

    assertThat(channels).hasSize(1);
    assertThat(channels.getFirst().id()).isEqualTo(7);
    assertThat(channels.getFirst().accountId()).isEqualTo(1);
    assertThat(channels.getFirst().title()).isEqualTo("VIP");
    assertThat(channels.getFirst().username()).isEqualTo("vip_share");
    server.verify();
}

@Test
void shouldSearchMessagesByChannelId() {
    server.expect(once(), requestTo("http://127.0.0.1:6000/api/search?q=%E7%9F%AD%E5%89%A7&limit=30&channel_id=7"))
            .andExpect(method(GET))
            .andRespond(withSuccess("""
                    {"items":[{"id":1,"telegram_message_id":123,"text":"名称：私密短剧","date":"2026-06-07T12:00:00Z","channel_title":"VIP","channel_username":"vip_share","links":[{"type":"quark","url":"https://pan.quark.cn/s/private","password":""}]}]}
                    """, MediaType.APPLICATION_JSON));

    List<Message> messages = client.searchMessages("短剧", 30, 7L);

    assertThat(messages).hasSize(1);
    assertThat(messages.getFirst().getChannel()).isEqualTo("vip_share");
    assertThat(messages.getFirst().getLink()).isEqualTo("https://pan.quark.cn/s/private");
    server.verify();
}

@Test
void shouldLoadLatestMessagesByChannelId() {
    server.expect(once(), requestTo("http://127.0.0.1:6000/api/messages/latest?limit=20&channel_id=7"))
            .andExpect(method(GET))
            .andRespond(withSuccess("""
                    {"items":[{"id":2,"telegram_message_id":124,"text":"名称：最新资源","date":"2026-06-07T13:00:00Z","channel_title":"VIP","channel_username":"vip_share","links":[{"type":"baidu","url":"https://pan.baidu.com/s/1abc?pwd=ruub","password":"ruub"}]}]}
                    """, MediaType.APPLICATION_JSON));

    List<Message> messages = client.latestMessages(20, 7L);

    assertThat(messages).hasSize(1);
    assertThat(messages.getFirst().getType()).isEqualTo("10");
    server.verify();
}

@Test
void shouldSyncChannelsWithProviderRequestBody() {
    server.expect(once(), requestTo("http://127.0.0.1:6000/api/channels/sync"))
            .andExpect(method(POST))
            .andExpect(content().json("{\"channel_ids\":[7,9]}"))
            .andRespond(withSuccess("""
                    {"queued":2,"skipped":0,"results":{"7":{"messages":3,"links":2},"9":{"messages":1,"links":1}},"failures":{}}
                    """, MediaType.APPLICATION_JSON));

    TgProviderSyncResponse response = client.syncChannels(List.of(7L, 9L));

    assertThat(response.queued()).isEqualTo(2);
    assertThat(response.results().get(7L).links()).isEqualTo(2);
    server.verify();
}
```

Add imports:

```java
import cn.har01d.alist_tvbox.dto.tg.TgProviderChannel;
import cn.har01d.alist_tvbox.dto.tg.TgProviderSyncResponse;
```

- [ ] **Step 2: Run the new tests and confirm they fail**

Run:

```bash
mvn -Dtest=TgProviderClientTest test
```

Expected: compilation fails because `TgProviderChannel`, `TgProviderSyncResponse`, `channels()`, `searchMessages(String,int,Long)`, `latestMessages(int,Long)`, and `syncChannels(List<Long>)` do not exist.

- [ ] **Step 3: Add provider DTOs**

Create `TgProviderChannel.java`:

```java
package cn.har01d.alist_tvbox.dto.tg;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TgProviderChannel(long id,
                                @JsonProperty("account_id") long accountId,
                                @JsonProperty("telegram_channel_id") long telegramChannelId,
                                @JsonProperty("access_hash") long accessHash,
                                String title,
                                String username,
                                String type,
                                @JsonProperty("last_message_id") long lastMessageId,
                                @JsonProperty("last_sync_time") String lastSyncTime,
                                @JsonProperty("created_at") String createdAt,
                                @JsonProperty("updated_at") String updatedAt) {
}
```

Create `TgProviderSyncResult.java`:

```java
package cn.har01d.alist_tvbox.dto.tg;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TgProviderSyncResult(int messages, int links) {
}
```

Create `TgProviderSyncResponse.java`:

```java
package cn.har01d.alist_tvbox.dto.tg;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TgProviderSyncResponse(int queued,
                                     int skipped,
                                     Map<Long, TgProviderSyncResult> results,
                                     Map<Long, String> failures) {
    public static TgProviderSyncResponse empty() {
        return new TgProviderSyncResponse(0, 0, Map.of(), Map.of());
    }
}
```

- [ ] **Step 4: Implement provider-client methods**

In `TgProviderClient.java`, add imports:

```java
import cn.har01d.alist_tvbox.dto.tg.TgProviderChannel;
import cn.har01d.alist_tvbox.dto.tg.TgProviderSyncResponse;
import java.util.Collection;
```

Replace `accounts()` with:

```java
public List<TgProviderAccount> accounts() {
    JsonNode response = get("/api/accounts", JsonNode.class);
    return parseItems(response, new TypeReference<>() {
    }, "accounts");
}
```

Add these public methods:

```java
public List<TgProviderChannel> channels() {
    return channels(null);
}

public List<TgProviderChannel> channels(Long accountId) {
    UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/channels");
    if (accountId != null && accountId > 0) {
        builder.queryParam("account_id", accountId);
    }
    JsonNode response = get(builder.build().encode().toUri(), JsonNode.class, "/api/channels");
    return parseItems(response, new TypeReference<>() {
    }, "channels");
}

public TgProviderSyncResponse syncChannels(Collection<Long> channelIds) {
    if (channelIds == null || channelIds.isEmpty()) {
        return TgProviderSyncResponse.empty();
    }
    return post("/api/channels/sync", Map.of("channel_ids", channelIds), TgProviderSyncResponse.class);
}

public List<Message> searchMessages(String keyword, int limit, Long channelId) {
    TgProviderSearchResponse response = search(keyword, limit, channelId);
    return toMessages(response);
}

public List<Message> latestMessages(int limit, Long channelId) {
    TgProviderSearchResponse response = latest(limit, channelId);
    return toMessages(response);
}

public TgProviderSearchResponse search(String keyword, int limit, Long channelId) {
    UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/search")
            .queryParam("q", keyword)
            .queryParam("limit", limit);
    if (channelId != null && channelId > 0) {
        builder.queryParam("channel_id", channelId);
    }
    URI url = builder.build().encode().toUri();
    try {
        return restTemplate.getForObject(url, TgProviderSearchResponse.class);
    } catch (RestClientException e) {
        throw new TgProviderException("tg-provider request failed: /api/search", e);
    }
}

public TgProviderSearchResponse latest(int limit, Long channelId) {
    UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/messages/latest")
            .queryParam("limit", limit);
    if (channelId != null && channelId > 0) {
        builder.queryParam("channel_id", channelId);
    }
    URI url = builder.build().encode().toUri();
    try {
        return restTemplate.getForObject(url, TgProviderSearchResponse.class);
    } catch (RestClientException e) {
        throw new TgProviderException("tg-provider request failed: /api/messages/latest", e);
    }
}
```

Change existing methods to delegate:

```java
public List<Message> searchMessages(String keyword, int limit) {
    return searchMessages(keyword, limit, null);
}

public TgProviderSearchResponse search(String keyword, int limit) {
    return search(keyword, limit, null);
}
```

Extract message mapping from the existing `searchMessages` body:

```java
private List<Message> toMessages(TgProviderSearchResponse response) {
    if (response == null || response.items() == null) {
        return List.of();
    }

    List<Message> messages = new ArrayList<>();
    for (TgProviderSearchItem item : response.items()) {
        String channel = StringUtils.defaultIfBlank(item.channelUsername(), item.channelTitle());
        if (item.links() == null) {
            continue;
        }
        for (TgProviderLink link : item.links()) {
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
```

Add URI-aware `get` overload and generic envelope parser:

```java
private <T> T get(URI uri, Class<T> type, String path) {
    try {
        return restTemplate.getForObject(uri, type);
    } catch (RestClientException e) {
        throw new TgProviderException("tg-provider request failed: " + path, e);
    }
}

private <T> List<T> parseItems(JsonNode response, TypeReference<List<T>> typeReference, String name) {
    if (response == null || response.isNull()) {
        return List.of();
    }

    JsonNode items = response.isArray() ? response : response.get("items");
    if (items == null || !items.isArray()) {
        throw new TgProviderException("tg-provider " + name + " response format invalid");
    }

    try {
        return objectMapper.convertValue(items, typeReference);
    } catch (IllegalArgumentException e) {
        throw new TgProviderException("parse tg-provider " + name + " response failed", e);
    }
}
```

Remove the old `parseAccounts` method after `accounts()` is migrated.

- [ ] **Step 5: Run provider-client tests**

Run:

```bash
mvn -Dtest=TgProviderClientTest test
```

Expected: PASS.

- [ ] **Step 6: Commit provider-client changes**

```bash
git add src/main/java/cn/har01d/alist_tvbox/dto/tg/TgProviderChannel.java \
        src/main/java/cn/har01d/alist_tvbox/dto/tg/TgProviderSyncResult.java \
        src/main/java/cn/har01d/alist_tvbox/dto/tg/TgProviderSyncResponse.java \
        src/main/java/cn/har01d/alist_tvbox/service/TgProviderClient.java \
        src/test/java/cn/har01d/alist_tvbox/service/TgProviderClientTest.java
git commit -m "feat: extend tg provider client for private channels"
```

---

### Task 2: Add Private Channel Service

**Files:**
- Create: `src/main/java/cn/har01d/alist_tvbox/dto/tg/TgPrivateChannel.java`
- Create: `src/main/java/cn/har01d/alist_tvbox/dto/tg/TgPrivateChannelSelectionRequest.java`
- Create: `src/main/java/cn/har01d/alist_tvbox/service/TgPrivateChannelService.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/service/TgPrivateChannelServiceTest.java`

- [ ] **Step 1: Write service tests**

Create `TgPrivateChannelServiceTest.java`:

```java
package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.dto.tg.TgPrivateChannel;
import cn.har01d.alist_tvbox.dto.tg.TgPrivateChannelSelectionRequest;
import cn.har01d.alist_tvbox.dto.tg.TgProviderChannel;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TgPrivateChannelServiceTest {
    @Mock
    private TgProviderClient tgProviderClient;
    @Mock
    private SettingRepository settingRepository;

    private TgPrivateChannelService service;

    @BeforeEach
    void setUp() {
        AppProperties appProperties = new AppProperties();
        appProperties.setTgDrivers(List.of("5", "10"));
        service = new TgPrivateChannelService(tgProviderClient, settingRepository, appProperties);
    }

    @Test
    void shouldMergeProviderChannelsWithEnabledSetting() {
        when(settingRepository.findById("tg_private_channel_ids")).thenReturn(Optional.of(new Setting("tg_private_channel_ids", "7,9")));
        when(tgProviderClient.channels()).thenReturn(List.of(
                channel(7, "VIP 1"),
                channel(8, "VIP 2")));

        List<TgPrivateChannel> channels = service.channels();

        assertThat(channels).extracting(TgPrivateChannel::id).containsExactly(7L, 8L);
        assertThat(channels.getFirst().enabled()).isTrue();
        assertThat(channels.get(1).enabled()).isFalse();
    }

    @Test
    void shouldSaveNormalizedEnabledChannelIds() {
        when(tgProviderClient.channels()).thenReturn(List.of(channel(7, "VIP 1"), channel(9, "VIP 2")));
        when(settingRepository.findById("tg_private_channel_ids")).thenReturn(Optional.of(new Setting("tg_private_channel_ids", "7,9")));

        service.saveChannels(new TgPrivateChannelSelectionRequest(List.of(9L, 7L, 9L, 0L)));

        ArgumentCaptor<Setting> captor = ArgumentCaptor.forClass(Setting.class);
        verify(settingRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("tg_private_channel_ids");
        assertThat(captor.getValue().getValue()).isEqualTo("7,9");
    }

    @Test
    void shouldReturnEmptySearchWhenNoPrivateChannelsAreSelected() {
        when(settingRepository.findById("tg_private_channel_ids")).thenReturn(Optional.empty());

        assertThat(service.search("短剧", 20)).isEmpty();
    }

    @Test
    void shouldSearchEverySelectedPrivateChannelAndMergeResults() {
        when(settingRepository.findById("tg_private_channel_ids")).thenReturn(Optional.of(new Setting("tg_private_channel_ids", "7,9")));
        Message one = Message.fromProvider(1, "vip1", "名称：A", "https://pan.quark.cn/s/a", "2026-06-07T12:00:00Z");
        Message two = Message.fromProvider(2, "vip2", "名称：B", "https://pan.baidu.com/s/1b", "2026-06-07T13:00:00Z");
        when(tgProviderClient.searchMessages("短剧", 20, 7L)).thenReturn(List.of(one));
        when(tgProviderClient.searchMessages("短剧", 20, 9L)).thenReturn(List.of(two));

        List<Message> messages = service.search("短剧", 20);

        assertThat(messages).extracting(Message::getName).containsExactly("B", "A");
    }

    private TgProviderChannel channel(long id, String title) {
        return new TgProviderChannel(id, 1, 1000 + id, 2000 + id, title, "vip" + id, "channel", 0, null, null, null);
    }
}
```

- [ ] **Step 2: Run the new service test and confirm it fails**

Run:

```bash
mvn -Dtest=TgPrivateChannelServiceTest test
```

Expected: compilation fails because the private DTOs and service do not exist.

- [ ] **Step 3: Add private-channel DTOs**

Create `TgPrivateChannel.java`:

```java
package cn.har01d.alist_tvbox.dto.tg;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TgPrivateChannel(long id,
                               @JsonProperty("account_id") long accountId,
                               @JsonProperty("telegram_channel_id") long telegramChannelId,
                               String title,
                               String username,
                               String type,
                               @JsonProperty("last_message_id") long lastMessageId,
                               @JsonProperty("last_sync_time") String lastSyncTime,
                               boolean enabled) {
    public static TgPrivateChannel from(TgProviderChannel channel, boolean enabled) {
        return new TgPrivateChannel(
                channel.id(),
                channel.accountId(),
                channel.telegramChannelId(),
                channel.title(),
                channel.username(),
                channel.type(),
                channel.lastMessageId(),
                channel.lastSyncTime(),
                enabled);
    }
}
```

Create `TgPrivateChannelSelectionRequest.java`:

```java
package cn.har01d.alist_tvbox.dto.tg;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record TgPrivateChannelSelectionRequest(@JsonProperty("channel_ids") List<Long> channelIds) {
}
```

- [ ] **Step 4: Implement `TgPrivateChannelService`**

Create `TgPrivateChannelService.java` with this structure:

```java
package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.dto.tg.TgPrivateChannel;
import cn.har01d.alist_tvbox.dto.tg.TgPrivateChannelSelectionRequest;
import cn.har01d.alist_tvbox.dto.tg.TgProviderSyncResponse;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.util.Utils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TgPrivateChannelService {
    public static final String CHANNEL_IDS_KEY = "tg_private_channel_ids";

    private final TgProviderClient tgProviderClient;
    private final SettingRepository settingRepository;
    private final AppProperties appProperties;

    public TgPrivateChannelService(TgProviderClient tgProviderClient,
                                   SettingRepository settingRepository,
                                   AppProperties appProperties) {
        this.tgProviderClient = tgProviderClient;
        this.settingRepository = settingRepository;
        this.appProperties = appProperties;
    }

    public List<TgPrivateChannel> channels() {
        Set<Long> enabled = enabledChannelIds();
        return tgProviderClient.channels().stream()
                .map(channel -> TgPrivateChannel.from(channel, enabled.contains(channel.id())))
                .toList();
    }

    public List<TgPrivateChannel> saveChannels(TgPrivateChannelSelectionRequest request) {
        settingRepository.save(new Setting(CHANNEL_IDS_KEY, joinIds(normalize(request == null ? null : request.channelIds()))));
        return channels();
    }

    public TgProviderSyncResponse syncChannels(TgPrivateChannelSelectionRequest request) {
        List<Long> ids = normalize(request == null || request.channelIds() == null || request.channelIds().isEmpty()
                ? new ArrayList<>(enabledChannelIds())
                : request.channelIds());
        if (ids.isEmpty()) {
            return TgProviderSyncResponse.empty();
        }
        return tgProviderClient.syncChannels(ids);
    }

    public List<Message> search(String keyword, int limit) {
        if (StringUtils.isBlank(keyword)) {
            return List.of();
        }
        return collectMessages(enabledChannelIds(), limit, channelId -> tgProviderClient.searchMessages(keyword, limit, channelId));
    }

    public MovieList searchMovies(String keyword, int limit) {
        return toMovieList(search(keyword, limit));
    }

    public MovieList list(String type, int page) {
        if ("0".equals(type)) {
            return latestMovies(null, 5);
        }
        if (StringUtils.startsWith(type, "type:")) {
            return latestMovies(type.substring(5), 100);
        }
        Long channelId = parseId(type);
        if (channelId == null) {
            return new MovieList();
        }
        return latestMoviesByChannel(channelId, 100);
    }

    public MovieList latestMovies(String driverType, int limit) {
        List<Message> messages = collectMessages(enabledChannelIds(), limit, channelId -> tgProviderClient.latestMessages(limit, channelId));
        if (StringUtils.isNotBlank(driverType)) {
            messages = messages.stream().filter(message -> driverType.equals(message.getType())).toList();
        }
        return toMovieList(messages);
    }

    public MovieList latestMoviesByChannel(long channelId, int limit) {
        if (!enabledChannelIds().contains(channelId)) {
            return new MovieList();
        }
        return toMovieList(collectMessages(List.of(channelId), limit, id -> tgProviderClient.latestMessages(limit, id)));
    }

    public CategoryList category() {
        CategoryList result = new CategoryList();
        List<Category> categories = new ArrayList<>();
        for (String type : appProperties.getTgDrivers()) {
            Category category = new Category();
            category.setType_id("type:" + type);
            category.setType_name(getTypeName(type));
            category.setType_flag(0);
            categories.add(category);
        }
        for (TgPrivateChannel channel : channels()) {
            if (!channel.enabled()) {
                continue;
            }
            Category category = new Category();
            category.setType_id(String.valueOf(channel.id()));
            category.setType_name(StringUtils.defaultIfBlank(channel.title(), channel.username()));
            category.setType_flag(0);
            categories.add(category);
        }
        result.setCategories(categories);
        result.setTotal(categories.size());
        result.setLimit(categories.size());
        return result;
    }

    private List<Message> collectMessages(Collection<Long> channelIds, int limit, ChannelMessageLoader loader) {
        if (channelIds == null || channelIds.isEmpty()) {
            return List.of();
        }
        return channelIds.stream()
                .flatMap(channelId -> {
                    try {
                        return loader.load(channelId).stream();
                    } catch (RuntimeException e) {
                        log.warn("tg-provider private channel request failed: {}", channelId, e);
                        return List.<Message>of().stream();
                    }
                })
                .filter(message -> StringUtils.isNotBlank(message.getType()))
                .filter(message -> appProperties.getTgDrivers().isEmpty() || appProperties.getTgDrivers().contains(message.getType()))
                .sorted(Comparator.comparing(Message::getTime).reversed())
                .distinct()
                .limit(limit)
                .toList();
    }

    private MovieList toMovieList(List<Message> messages) {
        MovieList result = new MovieList();
        List<MovieDetail> list = messages.stream().map(this::toMovieDetail).toList();
        result.setList(list);
        result.setTotal(list.size());
        result.setLimit(list.size());
        return result;
    }

    private MovieDetail toMovieDetail(Message message) {
        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(encodeUrl(message.getLink()));
        movieDetail.setVod_name(message.getName());
        movieDetail.setVod_pic(StringUtils.defaultIfBlank(message.getCover(), getPic(message.getType())));
        movieDetail.setVod_remarks(getTypeName(message.getType()));
        return movieDetail;
    }

    private Set<Long> enabledChannelIds() {
        return new LinkedHashSet<>(normalize(settingRepository.findById(CHANNEL_IDS_KEY)
                .map(Setting::getValue)
                .map(value -> List.of(value.split(",")))
                .orElse(List.of())
                .stream()
                .map(this::parseId)
                .toList()));
    }

    private List<Long> normalize(Collection<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .sorted()
                .toList();
    }

    private String joinIds(Collection<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private Long parseId(String value) {
        try {
            return StringUtils.isBlank(value) ? null : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String encodeUrl(String url) {
        return URLEncoder.encode(url, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String getTypeName(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "0" -> "阿里";
            case "1" -> "PikPak";
            case "2" -> "迅雷";
            case "3" -> "123";
            case "5" -> "夸克";
            case "6" -> "移动";
            case "7" -> "UC";
            case "8" -> "115";
            case "9" -> "天翼";
            case "10" -> "百度";
            case "12" -> "光鸭";
            case "magnet" -> "磁力";
            case "ed2k" -> "ED2K";
            default -> null;
        };
    }

    private String getPic(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "0" -> getUrl("/ali.jpg");
            case "1" -> getUrl("/pikpak.jpg");
            case "2" -> getUrl("/thunder.png");
            case "3" -> getUrl("/123.png");
            case "5" -> getUrl("/quark.png");
            case "7" -> getUrl("/uc.png");
            case "8" -> getUrl("/115.jpg");
            case "9" -> getUrl("/189.png");
            case "6" -> getUrl("/139.jpg");
            case "10" -> getUrl("/baidu.jpg");
            case "12" -> getUrl("/guangya.webp");
            default -> null;
        };
    }

    private String getUrl(String path) {
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .scheme(appProperties.isEnableHttps() && !Utils.isLocalAddress() ? "https" : "http")
                .replacePath(path)
                .replaceQuery(null)
                .build()
                .toUriString();
    }

    @FunctionalInterface
    private interface ChannelMessageLoader {
        List<Message> load(Long channelId);
    }
}
```

- [ ] **Step 5: Run service tests**

Run:

```bash
mvn -Dtest=TgPrivateChannelServiceTest test
```

Expected: PASS.

- [ ] **Step 6: Commit private service changes**

```bash
git add src/main/java/cn/har01d/alist_tvbox/dto/tg/TgPrivateChannel.java \
        src/main/java/cn/har01d/alist_tvbox/dto/tg/TgPrivateChannelSelectionRequest.java \
        src/main/java/cn/har01d/alist_tvbox/service/TgPrivateChannelService.java \
        src/test/java/cn/har01d/alist_tvbox/service/TgPrivateChannelServiceTest.java
git commit -m "feat: add telegram private channel service"
```

---

### Task 3: Split Telegram Controller Endpoints And Add `/tgsc`

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/web/TelegramController.java`
- Modify: `src/test/java/cn/har01d/alist_tvbox/web/TelegramControllerTest.java`

- [ ] **Step 1: Update controller tests**

In `TelegramControllerTest`, add a mock:

```java
@Mock
private TgPrivateChannelService tgPrivateChannelService;
```

Pass it into the controller constructor after `tgProviderClient`.

Replace the provider-first search tests with:

```java
@Test
void shouldUsePublicTelegramSearchForBrowserSearchEndpoint() throws Exception {
    Message legacyMessage = Message.fromProvider(2, "legacy", "名称：公开搜索", "https://pan.baidu.com/s/1abc", "2026-06-07T12:00:00Z");
    when(telegramService.search("短剧", 100, false, false)).thenReturn(List.of(legacyMessage));

    mockMvc.perform(get("/api/telegram/search").param("wd", "短剧"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].channel").value("legacy"));

    verify(telegramService).search(eq("短剧"), eq(100), eq(false), eq(false));
}
```

Add private endpoint and `/tgsc` tests:

```java
@Test
void shouldExposePrivateChannelsAndSaveSelection() throws Exception {
    TgPrivateChannel channel = new TgPrivateChannel(7, 1, 1001, "VIP", "vip_share", "channel", 88, "2026-06-07T12:00:00Z", true);
    when(tgPrivateChannelService.channels()).thenReturn(List.of(channel));
    when(tgPrivateChannelService.saveChannels(new TgPrivateChannelSelectionRequest(List.of(7L)))).thenReturn(List.of(channel));

    mockMvc.perform(get("/api/telegram/private/channels"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(7))
            .andExpect(jsonPath("$[0].enabled").value(true));

    mockMvc.perform(put("/api/telegram/private/channels")
                    .contentType(APPLICATION_JSON)
                    .content("{\"channel_ids\":[7]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].username").value("vip_share"));
}

@Test
void shouldSearchPrivateChannelsFromSeparateBrowserEndpoint() throws Exception {
    Message providerMessage = Message.fromProvider(1, "vip_share", "名称：私密短剧", "https://pan.quark.cn/s/private", "2026-06-07T12:00:00Z");
    when(tgPrivateChannelService.search("短剧", 100)).thenReturn(List.of(providerMessage));

    mockMvc.perform(get("/api/telegram/private/search").param("wd", "短剧"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].link").value("https://pan.quark.cn/s/private"));
}

@Test
void shouldRouteTgscLikeTgSearch() throws Exception {
    when(tgPrivateChannelService.searchMovies("短剧", 20)).thenReturn(new MovieList());
    when(tgPrivateChannelService.list("7", 2)).thenReturn(new MovieList());
    when(tgPrivateChannelService.category()).thenReturn(new CategoryList());

    mockMvc.perform(get("/tgsc").param("wd", "短剧"))
            .andExpect(status().isOk());
    mockMvc.perform(get("/tgsc").param("t", "7").param("pg", "2"))
            .andExpect(status().isOk());
    mockMvc.perform(get("/tgsc"))
            .andExpect(status().isOk());
}

@Test
void shouldCheckTokenForTgscTokenEndpoint() throws Exception {
    when(tgPrivateChannelService.searchMovies("短剧", 20)).thenReturn(new MovieList());

    mockMvc.perform(get("/tgsc/token123").param("wd", "短剧"))
            .andExpect(status().isOk());

    verify(subscriptionService).checkToken("token123");
}
```

Add imports:

```java
import cn.har01d.alist_tvbox.dto.tg.TgPrivateChannel;
import cn.har01d.alist_tvbox.dto.tg.TgPrivateChannelSelectionRequest;
import cn.har01d.alist_tvbox.service.TgPrivateChannelService;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
```

- [ ] **Step 2: Run controller tests and confirm they fail**

Run:

```bash
mvn -Dtest=TelegramControllerTest test
```

Expected: compilation fails until `TelegramController` accepts `TgPrivateChannelService` and exposes the new endpoints.

- [ ] **Step 3: Implement controller changes**

In `TelegramController`, import:

```java
import cn.har01d.alist_tvbox.dto.tg.TgPrivateChannel;
import cn.har01d.alist_tvbox.dto.tg.TgPrivateChannelSelectionRequest;
import cn.har01d.alist_tvbox.dto.tg.TgProviderSyncResponse;
import cn.har01d.alist_tvbox.service.TgPrivateChannelService;
```

Add field and constructor parameter:

```java
private final TgPrivateChannelService tgPrivateChannelService;
```

```java
TgProviderClient tgProviderClient,
TgPrivateChannelService tgPrivateChannelService) {
    this.telegramChannelRepository = telegramChannelRepository;
    this.telegramService = telegramService;
    this.subscriptionService = subscriptionService;
    this.objectMapper = objectMapper;
    this.tgProviderClient = tgProviderClient;
    this.tgPrivateChannelService = tgPrivateChannelService;
}
```

Change `/api/telegram/search` to public search only:

```java
@GetMapping("/api/telegram/search")
public List<Message> searchByKeyword(String wd) {
    return telegramService.search(wd, 100, false, false);
}
```

Add private browser endpoints:

```java
@GetMapping("/api/telegram/private/channels")
public List<TgPrivateChannel> privateChannels() {
    return tgPrivateChannelService.channels();
}

@PutMapping("/api/telegram/private/channels")
public List<TgPrivateChannel> savePrivateChannels(@RequestBody TgPrivateChannelSelectionRequest request) {
    return tgPrivateChannelService.saveChannels(request);
}

@PostMapping("/api/telegram/private/channels/sync")
public TgProviderSyncResponse syncPrivateChannels(@RequestBody(required = false) TgPrivateChannelSelectionRequest request) {
    return tgPrivateChannelService.syncChannels(request);
}

@GetMapping("/api/telegram/private/search")
public List<Message> searchPrivateChannels(String wd) {
    return tgPrivateChannelService.search(wd, 100);
}
```

Add `/tgsc` endpoints near `/tg-search`:

```java
@GetMapping("/tgsc")
public Object browsePrivate(String id, String t, String ac, String wd, String title, boolean web, @RequestParam(required = false, defaultValue = "1") int pg) throws IOException {
    return browsePrivate("", id, t, ac, wd, title, web, pg);
}

@GetMapping("/tgsc/{token}")
public Object browsePrivate(@PathVariable String token, String id, String t, String ac, String wd, String title, boolean web, @RequestParam(required = false, defaultValue = "1") int pg) throws IOException {
    subscriptionService.checkToken(token);
    if (StringUtils.isNotBlank(id)) {
        return telegramService.detail(id, ac, title);
    } else if (StringUtils.isNotBlank(t)) {
        return tgPrivateChannelService.list(t, pg);
    } else if (StringUtils.isNotBlank(wd)) {
        return tgPrivateChannelService.searchMovies(wd, 20);
    }
    return tgPrivateChannelService.category();
}
```

- [ ] **Step 4: Run controller tests**

Run:

```bash
mvn -Dtest=TelegramControllerTest test
```

Expected: PASS.

- [ ] **Step 5: Commit controller changes**

```bash
git add src/main/java/cn/har01d/alist_tvbox/web/TelegramController.java \
        src/test/java/cn/har01d/alist_tvbox/web/TelegramControllerTest.java
git commit -m "feat: split telegram public and private endpoints"
```

---

### Task 4: Update Native DTO Reflection Coverage

**Files:**
- Modify: `src/test/java/cn/har01d/alist_tvbox/nativeimage/NativeReflectConfigTest.java`
- Modify: `src/main/resources/META-INF/native-image/reflect-config.json`

- [ ] **Step 1: Add failing native reflect test expectations**

Add imports to `NativeReflectConfigTest`:

```java
import cn.har01d.alist_tvbox.dto.tg.TgPrivateChannel;
import cn.har01d.alist_tvbox.dto.tg.TgPrivateChannelSelectionRequest;
import cn.har01d.alist_tvbox.dto.tg.TgProviderChannel;
import cn.har01d.alist_tvbox.dto.tg.TgProviderSyncResponse;
import cn.har01d.alist_tvbox.dto.tg.TgProviderSyncResult;
```

Add these class names to `TG_PROVIDER_DTO_CLASSES`:

```java
TgProviderChannel.class.getName(),
TgPrivateChannel.class.getName(),
TgPrivateChannelSelectionRequest.class.getName(),
TgProviderSyncResponse.class.getName(),
TgProviderSyncResult.class.getName(),
```

- [ ] **Step 2: Run native tests and confirm reflect config fails**

Run:

```bash
mvn -Dtest=NativeReflectConfigTest,TelegramNativeDtoBoundaryTest test
```

Expected: `NativeReflectConfigTest.nativeReflectConfigShouldIncludeTelegramProviderDtos` fails because the new DTOs are missing from `reflect-config.json`.

- [ ] **Step 3: Add DTO entries to reflect config**

In `src/main/resources/META-INF/native-image/reflect-config.json`, add entries with the same shape as existing DTO entries:

```json
{"allDeclaredConstructors":true,"name":"cn.har01d.alist_tvbox.dto.tg.TgProviderChannel","allDeclaredMethods":true,"allDeclaredFields":true}
{"allDeclaredConstructors":true,"name":"cn.har01d.alist_tvbox.dto.tg.TgPrivateChannel","allDeclaredMethods":true,"allDeclaredFields":true}
{"allDeclaredConstructors":true,"name":"cn.har01d.alist_tvbox.dto.tg.TgPrivateChannelSelectionRequest","allDeclaredMethods":true,"allDeclaredFields":true}
{"allDeclaredConstructors":true,"name":"cn.har01d.alist_tvbox.dto.tg.TgProviderSyncResponse","allDeclaredMethods":true,"allDeclaredFields":true}
{"allDeclaredConstructors":true,"name":"cn.har01d.alist_tvbox.dto.tg.TgProviderSyncResult","allDeclaredMethods":true,"allDeclaredFields":true}
```

Keep the file as valid JSON array entries separated by commas.

- [ ] **Step 4: Run native tests**

Run:

```bash
mvn -Dtest=NativeReflectConfigTest,TelegramNativeDtoBoundaryTest test
```

Expected: PASS.

- [ ] **Step 5: Commit native DTO metadata**

```bash
git add src/test/java/cn/har01d/alist_tvbox/nativeimage/NativeReflectConfigTest.java \
        src/main/resources/META-INF/native-image/reflect-config.json
git commit -m "build: register telegram private dtos for native"
```

---

### Task 5: Add Search Source Tabs To `SearchView`

**Files:**
- Modify: `web-ui/src/views/SearchView.test.mjs`
- Modify: `web-ui/src/views/SearchView.vue`
- Modify: `web-ui/src/views/VodView.test.mjs`
- Modify: `web-ui/src/views/VodView.vue`

- [ ] **Step 1: Add failing frontend source tests**

Append to `SearchView.test.mjs`:

```js
test('search page exposes telegram channel search tab', () => {
  assert.equal(componentSource.includes(`const searchMode = ref(localStorage.getItem("search_mode") || 'pansou')`), true)
  assert.equal(componentSource.includes(`label="盘搜"`), true)
  assert.equal(componentSource.includes(`label="电报频道"`), true)
  assert.equal(componentSource.includes(`searchMode.value === 'telegram' ? '/tgsc' : getPath(type.value)`), true)
})
```

Replace the private-search expectation in `VodView.test.mjs` with a guard:

```js
test('vod page keeps telegram search tab out of player page', () => {
  assert.equal(viewSource.includes(`const searchMode = ref('public')`), false)
  assert.equal(viewSource.includes(`label="电报频道"`), false)
  assert.equal(viewSource.includes(`searchMode.value === 'private' ? '/api/telegram/private/search' : '/api/telegram/search'`), false)
  assert.equal(viewSource.includes(`axios.get('/api/telegram/search?wd=' + encodeURIComponent(keyword.value))`), true)
})
```

- [ ] **Step 2: Run source tests and confirm they fail**

Run:

```bash
node --test web-ui/src/views/SearchView.test.mjs web-ui/src/views/VodView.test.mjs
```

Expected: FAIL because the new search-page source selector does not exist and the
playback page still owns it.

- [ ] **Step 3: Implement search mode state in `SearchView.vue`**

Near the existing `keyword` state, add:

```ts
const searchMode = ref(localStorage.getItem("search_mode") || 'pansou')
```

Add:

```ts
const getSearchPath = () => searchMode.value === 'telegram' ? '/tgsc' : getPath(type.value)
```

In `search()`, persist `search_mode`, then choose the endpoint with the same expression
and call `endpoint + '/' + store.token + '?ac=web&wd=' + encodeURIComponent(...)`.

- [ ] **Step 4: Add the search tabs and telegram result table**

At the top of `SearchView.vue`, add:

```vue
<el-tabs v-model="searchMode" class="search-mode-tabs" @tab-change="handleSearchModeChange">
  <el-tab-pane label="盘搜" name="pansou"/>
  <el-tab-pane label="电报频道" name="telegram"/>
</el-tabs>
```

Use `getSearchPath()` in the displayed API address. Show the existing type selector and
PanSou actions only when `searchMode === 'pansou'`. Add a telegram result table for
`searchMode === 'telegram' && config?.list?.length` that opens rows with
`/#/vod?link=` plus `vod_id`.

- [ ] **Step 5: Add compact tab CSS**

In the scoped style block, add:

```css
.search-mode-tabs {
  margin-bottom: 4px;
}

::v-deep .search-mode-tabs .el-tabs__header {
  margin: 0 0 4px;
}

::v-deep .search-mode-tabs .el-tabs__nav-wrap::after {
  height: 1px;
}
```

- [ ] **Step 6: Run source test**

Run:

```bash
node --test web-ui/src/views/SearchView.test.mjs web-ui/src/views/VodView.test.mjs
```

Expected: PASS.

- [ ] **Step 7: Commit search-page changes**

```bash
git add web-ui/src/views/SearchView.vue web-ui/src/views/SearchView.test.mjs \
        web-ui/src/views/VodView.vue web-ui/src/views/VodView.test.mjs
git commit -m "feat: add telegram channel search tab"
```

---

### Task 6: Add `PlayConfig` Private Channels And Telegram Management Tabs

**Files:**
- Modify: `web-ui/src/components/PlayConfig.test.mjs`
- Modify: `web-ui/src/components/PlayConfig.vue`

- [ ] **Step 1: Add failing source tests**

Append to `PlayConfig.test.mjs`:

```js
test('play config separates public private and telegram management tabs', () => {
  assert.equal(componentSource.includes(`label="公开频道"`), true)
  assert.equal(componentSource.includes(`label="我的频道"`), true)
  assert.equal(componentSource.includes(`label="电报管理"`), true)
  assert.equal(componentSource.includes(`'/api/telegram/private/channels'`), true)
  assert.equal(componentSource.includes(`'/api/telegram/private/channels/sync'`), true)
})

test('play config owns telegram login workflow', () => {
  assert.equal(componentSource.includes(`'/api/telegram/user'`), true)
  assert.equal(componentSource.includes(`'/api/telegram/login/send-code'`), true)
  assert.equal(componentSource.includes(`'/api/telegram/login/sign-in'`), true)
  assert.equal(componentSource.includes(`'/api/telegram/login/password'`), true)
  assert.equal(componentSource.includes(`'/api/telegram/logout'`), true)
})
```

- [ ] **Step 2: Run source test and confirm it fails**

Run:

```bash
node --test web-ui/src/components/PlayConfig.test.mjs
```

Expected: FAIL because private channel and Telegram management tabs do not exist.

- [ ] **Step 3: Add private channel and login state**

In `PlayConfig.vue`, add interfaces and refs near the existing `Channel` interface and refs:

```ts
interface PrivateChannel {
  id: number
  account_id: number
  telegram_channel_id: number
  title: string
  username: string
  type: string
  last_message_id: number
  last_sync_time: string
  enabled: boolean
}

interface TelegramUser {
  id: number
  username: string
  first_name: string
  last_name: string
  phone: string
}

const privateChannels = ref<PrivateChannel[]>([])
const privateChannelsChanged = ref(false)
const privateChannelsLoading = ref(false)
const tgPhase = ref(1)
const tgPhone = ref('')
const tgCode = ref('')
const tgPassword = ref('')
const tgUser = ref<TelegramUser>({id: 0, username: '', first_name: '', last_name: '', phone: ''})
```

- [ ] **Step 4: Add private channel actions**

Add these functions before `onMounted`:

```ts
const loadPrivateChannels = () => {
  privateChannelsLoading.value = true
  return axios.get('/api/telegram/private/channels').then(({data}) => {
    privateChannels.value = data || []
    privateChannelsChanged.value = false
  }).finally(() => {
    privateChannelsLoading.value = false
  })
}

const privateChannelIds = () => {
  return privateChannels.value.filter(e => e.enabled).map(e => e.id)
}

const savePrivateChannels = () => {
  axios.put('/api/telegram/private/channels', {channel_ids: privateChannelIds()}).then(({data}) => {
    privateChannels.value = data || []
    privateChannelsChanged.value = false
    ElMessage.success('保存成功')
  })
}

const syncPrivateChannels = () => {
  axios.post('/api/telegram/private/channels/sync', {channel_ids: privateChannelIds()}).then(({data}) => {
    const queued = data?.queued || 0
    const skipped = data?.skipped || 0
    ElMessage.success(`已同步 ${queued} 个频道，跳过 ${skipped} 个`)
  })
}
```

- [ ] **Step 5: Add Telegram management actions**

Add:

```ts
const emptyTelegramUser = (): TelegramUser => ({id: 0, username: '', first_name: '', last_name: '', phone: ''})

const loadTelegramUser = () => {
  return axios.get('/api/telegram/user').then(({data}) => {
    tgUser.value = data || emptyTelegramUser()
    tgPhase.value = tgUser.value.id ? 0 : 1
  })
}

const logoutTelegram = () => {
  axios.post('/api/telegram/logout').then(() => {
    ElMessage.success('退出登录成功')
    tgUser.value = emptyTelegramUser()
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
    loadPrivateChannels()
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
    loadPrivateChannels()
  }, () => {
    ElMessage.error('密码验证失败')
  })
}
```

- [ ] **Step 6: Load private channels and Telegram user on mount**

Inside `onMounted`, after this block:

```ts
loadChannels().then(() => {
  rowDrop()
})
```

add:

```ts
loadPrivateChannels()
loadTelegramUser()
```

- [ ] **Step 7: Rename existing public channel tab**

Change:

```vue
<el-tab-pane label="频道管理" name="second">
```

to:

```vue
<el-tab-pane label="公开频道" name="public-channels">
```

- [ ] **Step 8: Add `我的频道` tab after public channel management**

Add after the public channel table tab:

```vue
<el-tab-pane label="我的频道" name="private-channels">
  <el-row justify="end">
    <el-button @click="loadPrivateChannels">刷新</el-button>
    <el-button @click="syncPrivateChannels" :disabled="!privateChannelIds().length">同步</el-button>
    <el-button type="primary" :disabled="!privateChannelsChanged" @click="savePrivateChannels">保存</el-button>
  </el-row>
  <div class="space"></div>
  <el-table :data="privateChannels" v-loading="privateChannelsLoading" row-key="id" style="width: 100%">
    <el-table-column prop="id" label="ID" width="90"/>
    <el-table-column prop="account_id" label="账号" width="90"/>
    <el-table-column prop="title" label="标题"/>
    <el-table-column prop="username" label="用户名" width="180">
      <template #default="scope">
        <a :href="'https://t.me/'+scope.row.username" target="_blank" v-if="scope.row.username">
          {{ scope.row.username }}
        </a>
      </template>
    </el-table-column>
    <el-table-column prop="type" label="类型" width="120"/>
    <el-table-column prop="last_message_id" label="最新消息" width="120"/>
    <el-table-column prop="last_sync_time" label="同步时间" width="210"/>
    <el-table-column prop="enabled" label="参与搜索" width="120">
      <template #default="scope">
        <el-switch v-model="scope.row.enabled" @change="privateChannelsChanged=true"/>
      </template>
    </el-table-column>
  </el-table>
</el-tab-pane>
```

- [ ] **Step 9: Add `电报管理` tab**

Add after `我的频道`:

```vue
<el-tab-pane label="电报管理" name="telegram">
  <el-form label-width="120">
    <template v-if="tgUser.id">
      <el-form-item label="用户ID">{{ tgUser.id }}</el-form-item>
      <el-form-item label="用户名">{{ tgUser.username }}</el-form-item>
      <el-form-item label="姓名">{{ tgUser.first_name }} {{ tgUser.last_name }}</el-form-item>
      <el-form-item label="电话">{{ tgUser.phone }}</el-form-item>
      <el-form-item>
        <el-button type="danger" @click="logoutTelegram">退出登录</el-button>
      </el-form-item>
    </template>
    <template v-else>
      <el-form-item label="电话号码" required v-if="tgPhase === 1">
        <el-input style="width: 260px" v-model="tgPhone" autocomplete="off" placeholder="+8612345678901"/>
        <el-button @click="sendTgPhone">发送验证码</el-button>
      </el-form-item>
      <el-form-item label="验证码" required v-if="tgPhase === 3">
        <el-input style="width: 160px" v-model="tgCode" autocomplete="off"/>
        <el-button @click="sendTgCode">登录</el-button>
      </el-form-item>
      <el-form-item label="密码" required v-if="tgPhase === 5">
        <el-input style="width: 260px" v-model="tgPassword" type="password" show-password autocomplete="off"/>
        <el-button @click="sendTgPassword">确认</el-button>
      </el-form-item>
    </template>
  </el-form>
</el-tab-pane>
```

- [ ] **Step 10: Run PlayConfig source test**

Run:

```bash
node --test web-ui/src/components/PlayConfig.test.mjs
```

Expected: PASS.

- [ ] **Step 11: Commit PlayConfig changes**

```bash
git add web-ui/src/components/PlayConfig.vue web-ui/src/components/PlayConfig.test.mjs
git commit -m "feat: manage telegram private channels in play config"
```

---

### Task 7: Remove Telegram Login From `SubscriptionsView`

**Files:**
- Modify: `web-ui/src/views/SubscriptionsView.test.mjs`
- Modify: `web-ui/src/views/SubscriptionsView.vue`

- [ ] **Step 1: Update source tests to expect removal**

Replace the two Telegram login tests in `SubscriptionsView.test.mjs` with:

```js
test('does not show telegram login button in subscription toolbar', () => {
  assert.equal(viewSource.includes('<el-button @click="handleLogin">登录 Telegram</el-button>'), false)
})

test('does not own telegram sms login workflow', () => {
  assert.equal(viewSource.includes('/api/telegram/login/send-code'), false)
  assert.equal(viewSource.includes('/api/telegram/login/sign-in'), false)
  assert.equal(viewSource.includes('/api/telegram/login/password'), false)
  assert.equal(viewSource.includes('/api/telegram/logout'), false)
})
```

- [ ] **Step 2: Run source test and confirm it fails**

Run:

```bash
node --test web-ui/src/views/SubscriptionsView.test.mjs
```

Expected: FAIL because the Telegram login UI still exists in `SubscriptionsView.vue`.

- [ ] **Step 3: Remove toolbar button and dialog**

Remove:

```vue
<el-button @click="handleLogin">登录 Telegram</el-button>
```

Remove the entire dialog that starts with:

```vue
<el-dialog v-model="tgVisible" title="登录 Telegram" width="520px" @close="cancelLogin">
```

and ends with its matching:

```vue
</el-dialog>
```

- [ ] **Step 4: Remove Telegram login state and functions**

Remove these refs and objects:

```ts
const tgPhase = ref(0)
const tgPhone = ref('')
const tgCode = ref('')
const tgPassword = ref('')
const tgVisible = ref(false)
const user = ref({
  id: 0,
  username: '',
  first_name: '',
  last_name: '',
  phone: ''
})
```

Remove these functions:

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

- [ ] **Step 5: Run subscription source test**

Run:

```bash
node --test web-ui/src/views/SubscriptionsView.test.mjs
```

Expected: PASS.

- [ ] **Step 6: Commit subscription cleanup**

```bash
git add web-ui/src/views/SubscriptionsView.vue web-ui/src/views/SubscriptionsView.test.mjs
git commit -m "refactor: move telegram login out of subscriptions"
```

---

### Task 8: Run Integrated Verification

**Files:**
- No source edits unless verification finds a failing assertion.

- [ ] **Step 1: Run targeted backend tests**

Run:

```bash
mvn -Dtest=TgProviderClientTest,TgPrivateChannelServiceTest,TelegramControllerTest,NativeReflectConfigTest,TelegramNativeDtoBoundaryTest test
```

Expected: PASS.

- [ ] **Step 2: Run frontend source tests**

Run:

```bash
node --test web-ui/src/views/SearchView.test.mjs web-ui/src/views/VodView.test.mjs web-ui/src/components/PlayConfig.test.mjs web-ui/src/views/SubscriptionsView.test.mjs
```

Expected: PASS.

- [ ] **Step 3: Run full backend suite**

Run:

```bash
mvn test
```

Expected: PASS.

- [ ] **Step 4: Run frontend type check**

Run:

```bash
npm --prefix web-ui run type-check
```

Expected: PASS.

- [ ] **Step 5: Inspect final diff**

Run:

```bash
git status --short
git diff --stat HEAD
```

Expected: only intended files are modified after the last task commit, or no tracked changes remain.
