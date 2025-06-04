package cn.har01d.alist_tvbox.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.annotation.JsonProperty;

import cn.har01d.alist_tvbox.exception.BadRequestException;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class QuarkUCTV {
    public static final String USER_AGENT = "Mozilla/5.0 (Linux; U; Android 13; zh-cn; M2004J7AC Build/UKQ1.231108.001) AppleWebKit/533.1 (KHTML, like Gecko) Mobile Safari/533.1";
    public static final String DEVICE_BRAND = "Xiaomi";
    public static final String PLATFORM = "tv";
    public static final String DEVICE_NAME = "M2004J7AC";
    public static final String DEVICE_MODEL = "M2004J7AC";
    public static final String BUILD_DEVICE = "M2004J7AC";
    public static final String BUILD_PRODUCT = "M2004J7AC";
    public static final String DEVICE_GPU = "Adreno (TM) 550";
    public static final String ACTIVITY_RECT = "{}";

    private final RestTemplate restTemplate;
    private final Conf conf;

    public QuarkUCTV(RestTemplate restTemplate, Conf conf) {
        this.restTemplate = restTemplate;
        this.conf = conf;
    }

    public LoginResponse getLoginCode() {
        String pathname = "/oauth/authorize";

        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(conf.api + pathname)
                .queryParam("auth_type", "code")
                .queryParam("client_id", conf.clientID)
                .queryParam("scope", "netdisk")
                .queryParam("qrcode", "1")
                .queryParam("qr_width", "460")
                .queryParam("qr_height", "460");

        String[] reqSign = generateReqSign("GET", pathname, conf.signKey);
        query(reqSign[2]).forEach(uriBuilder::queryParam);
        String url = uriBuilder.toUriString();
        log.debug("get qr data: {}", url);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "application/json, text/plain, */*");
        headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
        headers.set("x-pan-tm", reqSign[0]);
        headers.set("x-pan-token", reqSign[1]);
        headers.set("x-pan-client-id", conf.clientID);
        HttpEntity<?> requestEntity = new HttpEntity<>(headers);

        ResponseEntity<LoginResponse> responseEntity;
        try {
            responseEntity = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    requestEntity,
                    LoginResponse.class
            );
        } catch (RestClientResponseException e) {
            String error = Objects.toString(e.getResponseBodyAs(Map.class).get("error_info"), e.getMessage());
            throw new BadRequestException(error, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get login QR code", e);
        }

        if (!responseEntity.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Request failed with status: " + responseEntity.getStatusCode());
        }

        LoginResponse resp = responseEntity.getBody();
        if (resp == null) {
            throw new RuntimeException("Response body is null");
        }

        if (resp.getStatus() != 0) {
            throw new RuntimeException("Request failed with status: " + resp.getStatus());
        }

        return resp;
    }

    public String getCode(String queryToken) {
        String pathname = "/oauth/code";

        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(conf.api + pathname)
                .queryParam("client_id", conf.clientID)
                .queryParam("scope", "netdisk")
                .queryParam("query_token", queryToken);

        String[] reqSign = generateReqSign("GET", pathname, conf.signKey);
        query(reqSign[2]).forEach(uriBuilder::queryParam);
        String url = uriBuilder.toUriString();
        log.debug("get code: {}", url);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "application/json, text/plain, */*");
        headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
        headers.set("x-pan-tm", reqSign[0]);
        headers.set("x-pan-token", reqSign[1]);
        headers.set("x-pan-client-id", conf.clientID);
        HttpEntity<?> requestEntity = new HttpEntity<>(headers);

        ResponseEntity<Map> responseEntity;
        try {
            responseEntity = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    requestEntity,
                    Map.class
            );
        } catch (RestClientResponseException e) {
            String error = Objects.toString(e.getResponseBodyAs(Map.class).get("error_info"), e.getMessage());
            throw new BadRequestException(error, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get code", e);
        }

        if (!responseEntity.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Request failed with status: " + responseEntity.getStatusCode());
        }

        return responseEntity.getBody().get("code").toString();
    }

    public String getRefreshToken(String code) {
        String pathname = "/token";

        String[] reqSign = generateReqSign("POST", pathname, conf.signKey);
        String reqID = reqSign[2];

        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(conf.codeApi + pathname);

        query(reqSign[2]).forEach(uriBuilder::queryParam);
        String url = uriBuilder.toUriString();
        log.debug("get refresh token: {}", url);

        Map<String, String> body = new HashMap<>();
        body.put("req_id", reqID);
        body.put("app_ver", conf.appVer);
        body.put("device_id", conf.deviceId);
        body.put("device_brand", DEVICE_BRAND);
        body.put("platform", PLATFORM);
        body.put("device_name", DEVICE_NAME);
        body.put("device_model", DEVICE_MODEL);
        body.put("build_device", BUILD_DEVICE);
        body.put("build_product", BUILD_PRODUCT);
        body.put("device_gpu", DEVICE_GPU);
        body.put("activity_rect", ACTIVITY_RECT);
        body.put("channel", conf.channel);
        body.put("code", code);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
        headers.set("x-pan-tm", reqSign[0]);
        headers.set("x-pan-token", reqSign[1]);
        headers.set("x-pan-client-id", conf.clientID);
        HttpEntity<Map<String, String>> requestEntity = new HttpEntity<>(body, headers);

        ResponseEntity<RefreshTokenAuthResp> responseEntity;
        try {
            responseEntity = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    requestEntity,
                    RefreshTokenAuthResp.class
            );
        } catch (RestClientResponseException e) {
            String error = Objects.toString(e.getResponseBodyAs(Map.class).get("error_info"), e.getMessage());
            throw new BadRequestException(error, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get refresh token", e);
        }

        return responseEntity.getBody().data.refreshToken;
    }

    private Map<String, String> query(String reqID) {
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("req_id", reqID);
        queryParams.put("app_ver", conf.appVer);
        queryParams.put("device_id", conf.deviceId);
        queryParams.put("device_brand", DEVICE_BRAND);
        queryParams.put("platform", PLATFORM);
        queryParams.put("device_name", DEVICE_NAME);
        queryParams.put("device_model", DEVICE_MODEL);
        queryParams.put("build_device", BUILD_DEVICE);
        queryParams.put("build_product", BUILD_PRODUCT);
        queryParams.put("device_gpu", DEVICE_GPU);
        queryParams.put("activity_rect", ACTIVITY_RECT);
        queryParams.put("channel", conf.channel);
        return queryParams;
    }

    private String[] generateReqSign(String method, String pathname, String key) {
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String reqID = getMD5Hex(conf.deviceId + timestamp);
        String tokenData = method + "&" + pathname + "&" + timestamp + "&" + key;
        String xPanTokenHex = getSHA256Hex(tokenData);
        return new String[]{timestamp, xPanTokenHex, reqID};
    }

    public static String generateDeviceId() {
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        return getMD5EncodeStr(timestamp);
    }

    private static String getMD5EncodeStr(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not found", e);
        }
    }

    private static String getMD5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not found", e);
        }
    }

    private static String getSHA256Hex(String input) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

    public record Conf(String api, String clientID, String signKey, String appVer, String channel, String codeApi,
                       String deviceId) {
    }

    @Data
    public static class LoginResponse {
        private int status;
        @JsonProperty("req_id")
        private String reqID;
        @JsonProperty("qr_data")
        private String qrData;
        @JsonProperty("query_token")
        private String queryToken;
    }

    @Data
    public static class RefreshTokenAuthResp {
        private int code;
        private String message;
        private TokenData data;
    }

    @Data
    public static class TokenData {
        @JsonProperty("refresh_token")
        private String refreshToken;
        @JsonProperty("access_token")
        private String accessToken;
    }
}
