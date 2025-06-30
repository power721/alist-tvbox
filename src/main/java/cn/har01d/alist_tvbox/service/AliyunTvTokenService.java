package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.util.BiliBiliUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import cn.har01d.alist_tvbox.util.Utils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class AliyunTvTokenService {
    private final String uniqueId;
    private final String wifimac;
    private final RestTemplate restTemplate;
    private String timestamp;

    public AliyunTvTokenService(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder
                .defaultHeader("token", "6733b42e28cdba32")
                .defaultHeader("User-Agent", "Mozilla/5.0 (Linux; U; Android 9; zh-cn; SM-S908E Build/TP1A.220624.014) AppleWebKit/533.1 (KHTML, like Gecko) Mobile Safari/533.1")
                .defaultHeader("Host", "api.extscreen.com")
                .build();
        this.uniqueId = UUID.randomUUID().toString().replace("-", "");
        this.wifimac = String.valueOf((long) (Math.random() * 9e11) + (long) 1e11);
    }

    public Map<String, String> getQrcodeUrl() {
        timestamp = getTimestamp();
        try {
            HttpHeaders httpHeaders = new HttpHeaders();
            getParams().forEach(httpHeaders::add);
            Map<String, Object> body = new HashMap<>();
            body.put("scopes", "user:base,file:all:read,file:all:write");
            body.put("width", 500);
            body.put("height", 500);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, httpHeaders);

            ResponseEntity<Map> response = restTemplate.exchange(
                    "http://api.extscreen.com/aliyundrive/qrcode",
                    HttpMethod.POST,
                    entity,
                    Map.class
            );

            Map data = (Map) response.getBody().get("data");
            String sid = (String) data.get("sid");
            String qrLink = "https://www.aliyundrive.com/o/oauth/authorize?sid=" + sid;

            Map<String, String> result = new HashMap<>();
            result.put("img", Utils.getQrCode(qrLink));
            result.put("sid", sid);
            return result;
        } catch (Exception e) {
            log.warn("二维码生成失败: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public Map checkQrcodeStatus(String sid) {
        RestTemplate restTemplate = new RestTemplate();
        String status;
        String url = "https://openapi.alipan.com/oauth/qrcode/" + sid + "/status";
        ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
        Map<String, Object> body = response.getBody();
        status = (String) body.get("status");
        if ("LoginSuccess".equals(status)) {
            return getToken((String) body.get("authCode"));
        } else {
            throw new BadRequestException("获取扫码结果失败: " + status);
        }
    }

    public Map getToken(String code) {
        timestamp = getTimestamp();
        try {
            HttpHeaders httpHeaders = new HttpHeaders();
            getParams().forEach(httpHeaders::add);
            Map<String, String> data = Map.of("code", code);
            HttpEntity<Map<String, String>> request = new HttpEntity<>(data, httpHeaders);

            ResponseEntity<Map> response = restTemplate.exchange(
                    "http://api.extscreen.com/aliyundrive/v3/token",
                    HttpMethod.POST,
                    request,
                    Map.class
            );
            log.debug("{} {}", response.getStatusCode(), response.getBody());
            Map resultData = (Map) response.getBody().get("data");
            String ciphertext = (String) resultData.get("ciphertext");
            String iv = (String) resultData.get("iv");

            String json = decrypt(ciphertext, iv);
            Map map = new ObjectMapper().readValue(json, Map.class);
            log.debug("get token: {}", map);
            return map;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Map refreshToken(Map<String, Object> data) {
        log.debug("refreshToken request: {}", data);
        timestamp = getTimestamp();
        try {
            HttpHeaders httpHeaders = new HttpHeaders();
            getParams().forEach(httpHeaders::add);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(data, httpHeaders);

            ResponseEntity<Map> response = restTemplate.exchange(
                    "http://api.extscreen.com/aliyundrive/v3/token",
                    HttpMethod.POST,
                    request,
                    Map.class
            );
            Map resultData = (Map) response.getBody().get("data");
            String ciphertext = (String) resultData.get("ciphertext");
            String iv = (String) resultData.get("iv");

            String json = decrypt(ciphertext, iv);
            Map map = new ObjectMapper().readValue(json, Map.class);
            log.debug("refreshToken response: {}", map);
            return map;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String getTimestamp() {
        return String.valueOf(System.currentTimeMillis() / 1000);
    }

    private String h(List<Character> chars, String modifier) {
        Set<Character> seen = new LinkedHashSet<>(chars);
        int numericModifier = Integer.parseInt(modifier.substring(7));

        StringBuilder result = new StringBuilder();
        for (char c : seen) {
            int val = Math.abs(c - (numericModifier % 127) - 1);
            val = val < 33 ? val + 33 : val;
            result.append((char) val);
        }
        return result.toString();
    }

    private Map<String, String> getParams() {
        Map<String, String> params = new HashMap<>();
        params.put("akv", "2.8.1496");
        params.put("apv", "1.3.8");
        params.put("b", "samsung");
        params.put("d", uniqueId);
        params.put("m", "SM-S908E");
        params.put("mac", "");
        params.put("n", "SM-S908E");
        params.put("t", timestamp);
        params.put("wifiMac", wifimac);
        return params;
    }

    private String generateKey() throws Exception {
        Map<String, String> params = getParams();
        List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);

        StringBuilder concat = new StringBuilder();
        for (String key : keys) {
            if (!key.equals("t")) concat.append(params.get(key));
        }

        List<Character> chars = new ArrayList<>();
        for (char c : concat.toString().toCharArray()) {
            chars.add(c);
        }

        String hashed = h(chars, timestamp);
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(hashed.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    private String decrypt(String ciphertextBase64, String ivHex) throws Exception {
        String key = generateKey();
        IvParameterSpec ivSpec = new IvParameterSpec(hexStringToBytes(ivHex));
        SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        byte[] decoded = Base64.getDecoder().decode(ciphertextBase64);
        byte[] decrypted = cipher.doFinal(decoded);

        return new String(decrypted, StandardCharsets.UTF_8);
    }

    private byte[] hexStringToBytes(String hex) {
        int len = hex.length();
        byte[] res = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            res[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return res;
    }
}
