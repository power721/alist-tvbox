package cn.har01d.alist_tvbox.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Component
public class FeiniuRequestSigner {
    static final String API_KEY = "NDzZTVxnRKP8Z0jXg1VAMonaG8akvh";
    static final String API_SECRET = "16CCEB3D-AB42-077D-36A1-F355324E4237";

    public String build(String path, String bodyJson, String nonce, long timestamp) {
        return buildAuthx(path, bodyJson, nonce, timestamp);
    }

    public static String buildAuthx(String path, String bodyJson, String nonce, long timestamp) {
        String payloadMd5 = md5(bodyJson == null ? "" : bodyJson);
        String raw = String.join("_", API_KEY, path, nonce, String.valueOf(timestamp), payloadMd5, API_SECRET);
        return "nonce=" + nonce + "&timestamp=" + timestamp + "&sign=" + md5(raw);
    }

    private static String md5(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }
}
