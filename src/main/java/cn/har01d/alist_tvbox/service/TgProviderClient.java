package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.dto.tg.TgProviderAccount;
import cn.har01d.alist_tvbox.dto.tg.TgProviderAccountChannelSyncResponse;
import cn.har01d.alist_tvbox.dto.tg.TgProviderChannel;
import cn.har01d.alist_tvbox.dto.tg.TgProviderChannelSyncResponse;
import cn.har01d.alist_tvbox.dto.tg.TgProviderLink;
import cn.har01d.alist_tvbox.dto.tg.TgProviderLoginResponse;
import cn.har01d.alist_tvbox.dto.tg.TgProviderSearchItem;
import cn.har01d.alist_tvbox.dto.tg.TgProviderSearchResponse;
import cn.har01d.alist_tvbox.dto.tg.TgProviderStatus;
import cn.har01d.alist_tvbox.dto.tg.TgProviderSyncResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class TgProviderClient {
    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:6000";

    private final RestTemplate restTemplate;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    @Autowired
    public TgProviderClient(RestTemplateBuilder builder, AppProperties appProperties, ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        Duration timeout = Duration.ofMillis(appProperties.getTgTimeout());
        this.restTemplate = builder
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .build();
        this.objectMapper = objectMapper;
        this.baseUrl = DEFAULT_BASE_URL;
    }

    TgProviderClient(RestTemplate restTemplate, AppProperties appProperties, ObjectMapper objectMapper, String baseUrl) {
        this.restTemplate = restTemplate;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    @PostConstruct
    public void init() {
        try {
            TgProviderStatus status = status();
            if (status != null && status.accounts() > 0) {
                appProperties.setTgLogin(true);
            }
        } catch (Exception e) {
            log.warn("check status failed", e);
        }
    }

    public TgProviderStatus status() {
        return get("/api/status", TgProviderStatus.class);
    }

    public List<TgProviderAccount> accounts() {
        JsonNode response = get("/api/accounts", JsonNode.class);
        return parseItems(response, new TypeReference<>() {
        }, "accounts");
    }

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

    public void deleteAccount(long id) {
        try {
            restTemplate.exchange(baseUrl + "/api/accounts/{id}", HttpMethod.DELETE, HttpEntity.EMPTY, Void.class, id);
        } catch (RestClientException e) {
            throw new TgProviderException("tg-provider request failed: /api/accounts/" + id, e);
        }
    }

    public TgProviderLoginResponse sendCode(String phone) {
        return post("/api/login/send-code", Map.of("phone", phone), TgProviderLoginResponse.class);
    }

    public TgProviderLoginResponse signIn(String phone, String code) {
        return post("/api/login/sign-in", Map.of("phone", phone, "code", code), TgProviderLoginResponse.class);
    }

    public TgProviderAccount password(String phone, String password) {
        return post("/api/login/password", Map.of("phone", phone, "password", password), TgProviderAccount.class);
    }

    public TgProviderSyncResponse syncChannels(Collection<Long> channelIds) {
        if (channelIds == null || channelIds.isEmpty()) {
            return TgProviderSyncResponse.empty();
        }
        return post("/api/channels/sync", Map.of("channel_ids", channelIds), TgProviderSyncResponse.class);
    }

    public TgProviderChannelSyncResponse syncChannel(long channelId) {
        return post("/api/channels/" + channelId + "/sync", Map.of(), TgProviderChannelSyncResponse.class);
    }

    public TgProviderAccountChannelSyncResponse syncAccountChannels(long accountId) {
        return post("/api/accounts/" + accountId + "/channels/sync", Map.of(), TgProviderAccountChannelSyncResponse.class);
    }

    public List<Message> searchMessages(String keyword, int limit) {
        return searchMessages(keyword, limit, null);
    }

    public List<Message> searchMessages(String keyword, int limit, Long channelId) {
        TgProviderSearchResponse response = search(keyword, limit, channelId);
        return toMessages(response);
    }

    public List<Message> latestMessages(int limit, Long channelId) {
        TgProviderSearchResponse response = latest(limit, channelId);
        return toMessages(response);
    }

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

    public TgProviderSearchResponse search(String keyword, int limit) {
        return search(keyword, limit, null);
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

    private <T> T get(String path, Class<T> type) {
        try {
            return restTemplate.getForObject(baseUrl + path, type);
        } catch (RestClientException e) {
            throw new TgProviderException("tg-provider request failed: " + path, e);
        }
    }

    private <T> T get(URI uri, Class<T> type, String path) {
        try {
            return restTemplate.getForObject(uri, type);
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

    private <T> List<T> parseItems(JsonNode response, TypeReference<List<T>> typeReference, String name) {
        if (response == null || response.isNull()) {
            return List.of();
        }

        JsonNode error = response.get("error");
        if (error != null && !error.isNull()) {
            String code = error.path("code").asText("unknown");
            String message = error.path("message").asText("unknown error");
            throw new TgProviderException("tg-provider " + name + " error: " + code + " - " + message);
        }

        JsonNode items = response.isArray() ? response : response.get("items");
        if (items != null && items.isNull()) {
            return List.of();
        }
        if (items == null || !items.isArray()) {
            throw new TgProviderException("tg-provider " + name + " response format invalid");
        }

        try {
            return objectMapper.convertValue(items, typeReference);
        } catch (IllegalArgumentException e) {
            throw new TgProviderException("parse tg-provider " + name + " response failed", e);
        }
    }

    public static class TgProviderException extends RuntimeException {
        public TgProviderException(String message) {
            super(message);
        }

        public TgProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
