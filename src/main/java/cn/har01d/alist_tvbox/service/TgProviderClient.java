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

import java.net.URI;
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
        Duration timeout = Duration.ofMillis(appProperties.getTgTimeout());
        this.restTemplate = builder
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .build();
        this.objectMapper = objectMapper;
        this.baseUrl = DEFAULT_BASE_URL;
    }

    TgProviderClient(RestTemplate restTemplate, ObjectMapper objectMapper, String baseUrl) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    public Status status() {
        return get("/api/status", Status.class);
    }

    public List<Account> accounts() {
        Account[] accounts = get("/api/accounts", Account[].class);
        return accounts == null ? List.of() : List.of(accounts);
    }

    public void deleteAccount(long id) {
        try {
            restTemplate.exchange(baseUrl + "/api/accounts/{id}", HttpMethod.DELETE, HttpEntity.EMPTY, Void.class, id);
        } catch (RestClientException e) {
            throw new TgProviderException("tg-provider request failed: /api/accounts/" + id, e);
        }
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
        URI url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/search")
                .queryParam("q", keyword)
                .queryParam("limit", limit)
                .build()
                .encode()
                .toUri();
        try {
            return restTemplate.getForObject(url, SearchResponse.class);
        } catch (RestClientException e) {
            throw new TgProviderException("tg-provider request failed: /api/search", e);
        }
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
    public record Account(long id,
                          String username,
                          @JsonProperty("first_name") String firstName,
                          @JsonProperty("last_name") String lastName,
                          String phone) {
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
