package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Feiniu;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.util.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class FeiniuApiClient {
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final FeiniuRequestSigner signer;

    public FeiniuApiClient(RestTemplateBuilder builder, ObjectMapper objectMapper, FeiniuRequestSigner signer) {
        this.restTemplate = builder.build();
        this.objectMapper = objectMapper;
        this.signer = signer;
    }

    public JsonNode getUserInfo(Feiniu feiniu, String token) {
        return get(feiniu, token, "/v/api/v1/user/info");
    }

    public String login(Feiniu feiniu) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("app_name", "trimemedia-web");
        body.put("username", feiniu.getUsername());
        body.put("password", feiniu.getPassword());
        JsonNode data = post(feiniu, "", "/v/api/v1/login", body);
        String token = data.path("token").asText("");
        if (StringUtils.isBlank(token)) {
            throw new BadRequestException("飞牛影视登录失败");
        }
        return token;
    }

    public JsonNode getMediaDbList(Feiniu feiniu, String token) {
        return get(feiniu, token, "/v/api/v1/mediadb/list");
    }

    public JsonNode getPlayList(Feiniu feiniu, String token) {
        return get(feiniu, token, "/v/api/v1/play/list");
    }

    public JsonNode getItemList(Feiniu feiniu, String token, Map<String, Object> body) {
        return post(feiniu, token, "/v/api/v1/item/list", body);
    }

    public JsonNode search(Feiniu feiniu, String token, String keyword) {
        String path = UriComponentsBuilder.fromPath("/v/api/v1/search/list")
                .queryParam("q", keyword)
                .build()
                .toUriString();
        return get(feiniu, token, path);
    }

    public JsonNode getItem(Feiniu feiniu, String token, String guid) {
        return get(feiniu, token, "/v/api/v1/item/" + guid);
    }

    public JsonNode getSeasonList(Feiniu feiniu, String token, String guid) {
        return get(feiniu, token, "/v/api/v1/season/list/" + guid);
    }

    public JsonNode getEpisodeList(Feiniu feiniu, String token, String guid) {
        return get(feiniu, token, "/v/api/v1/episode/list/" + guid);
    }

    public JsonNode getPlayInfo(Feiniu feiniu, String token, String guid) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("item_guid", guid);
        return post(feiniu, token, "/v/api/v1/play/info", body);
    }

    public JsonNode getStreamList(Feiniu feiniu, String token, String guid) {
        return get(feiniu, token, "/v/api/v1/stream/list/" + guid);
    }

    public void recordPlay(Feiniu feiniu, String token, Map<String, Object> body) {
        post(feiniu, token, "/v/api/v1/play/record", body);
    }

    public String getMediaRangeUrl(Feiniu feiniu, String mediaGuid) {
        return feiniu.getUrl() + "/v/api/v1/media/range/" + mediaGuid;
    }

    private JsonNode get(Feiniu feiniu, String token, String path) {
        HttpEntity<Void> entity = new HttpEntity<>(headers(feiniu, token, path, null));
        JsonNode response = restTemplate.exchange(feiniu.getUrl() + path, HttpMethod.GET, entity, JsonNode.class).getBody();
        return extractData(response);
    }

    private JsonNode post(Feiniu feiniu, String token, String path, Map<String, Object> body) {
        try {
            String json = objectMapper.writeValueAsString(new LinkedHashMap<>(body));
            HttpEntity<String> entity = new HttpEntity<>(json, headers(feiniu, token, path, json));
            JsonNode response = restTemplate.exchange(feiniu.getUrl() + path, HttpMethod.POST, entity, JsonNode.class).getBody();
            return extractData(response);
        } catch (Exception e) {
            throw new BadRequestException("飞牛影视请求失败", e);
        }
    }

    private HttpHeaders headers(Feiniu feiniu, String token, String path, String bodyJson) {
        String nonce = randomNonce();
        long timestamp = System.currentTimeMillis();
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(MediaType.parseMediaTypes(Constants.ACCEPT));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, token);
        headers.set(HttpHeaders.COOKIE, "mode=relay; Trim-MC-token=" + token);
        headers.set("authx", signer.build(path, bodyJson, nonce, timestamp));
        headers.set(HttpHeaders.USER_AGENT, StringUtils.defaultIfBlank(feiniu.getUserAgent(), Constants.USER_AGENT));
        return headers;
    }

    private JsonNode extractData(JsonNode response) {
        if (response == null) {
            throw new BadRequestException("飞牛影视响应为空");
        }
        if (response.path("code").asInt(-1) != 0) {
            throw new BadRequestException(StringUtils.defaultIfBlank(response.path("msg").asText(), "飞牛影视请求失败"));
        }
        return response.path("data");
    }

    private String randomNonce() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(100000, 1000000));
    }
}
