package cn.har01d.alist_tvbox.util;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public final class BiliCookieRefreshUtils {
    private static final String PUBLIC_KEY = """
            -----BEGIN PUBLIC KEY-----
            MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDLgd2OAkcGVtoE3ThUREbio0Eg
            Uc/prcajMKXvkCKFCWhJYJcLkcM2DKKcSeFpD/j6Boy538YXnR6VhcuUJOhH2x71
            nzPjfdTcqMz7djHum0qSZA0AyCBDABUqCrfNgCiJ00Ra7GmRj+YCK1NJEuewlb40
            JNrRuoEUXpabUzGB8QIDAQAB
            -----END PUBLIC KEY-----
            """;
    private static final PublicKey RSA_PUBLIC_KEY = loadPublicKey();

    private BiliCookieRefreshUtils() {
    }

    public static String getCorrespondPath(long timestamp) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        OAEPParameterSpec oaepParams = new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
        cipher.init(Cipher.ENCRYPT_MODE, RSA_PUBLIC_KEY, oaepParams);
        byte[] encrypted = cipher.doFinal(("refresh_" + timestamp).getBytes(StandardCharsets.UTF_8));
        return toHex(encrypted);
    }

    public static String extractRefreshCsrf(String html) {
        if (StringUtils.isBlank(html)) {
            return null;
        }
        Document document = Jsoup.parse(html);
        Element element = document.getElementById("1-name");
        if (element != null) {
            String text = element.text().trim();
            if (StringUtils.isNotBlank(text)) {
                return text;
            }
        }
        return null;
    }

    public static String decodeHtml(byte[] body, String contentEncoding) throws IOException {
        if (body == null || body.length == 0) {
            return "";
        }
        InputStream inputStream = new ByteArrayInputStream(body);
        if (StringUtils.containsIgnoreCase(contentEncoding, "gzip")) {
            inputStream = new GZIPInputStream(inputStream);
        } else if (StringUtils.containsIgnoreCase(contentEncoding, "deflate")) {
            inputStream = new InflaterInputStream(inputStream);
        }
        try (InputStream stream = inputStream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static String ensureBuvid3(String cookieHeader) {
        if (StringUtils.isBlank(cookieHeader)) {
            return cookieHeader;
        }
        if (StringUtils.isNotBlank(getCookieValue(cookieHeader, "buvid3"))) {
            return cookieHeader;
        }
        return cookieHeader + "; buvid3=" + UUID.randomUUID() + ThreadLocalRandom.current().nextInt(10000, 99999) + "infoc";
    }

    public static String mergeCookieHeader(String cookieHeader, List<String> setCookies) {
        LinkedHashMap<String, String> cookies = parseCookieHeader(cookieHeader);
        if (setCookies != null) {
            for (String setCookie : setCookies) {
                if (StringUtils.isBlank(setCookie)) {
                    continue;
                }
                String pair = setCookie.split(";", 2)[0].trim();
                int separator = pair.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                cookies.put(pair.substring(0, separator).trim(), pair.substring(separator + 1).trim());
            }
        }
        return cookies.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
    }

    public static String getCookieValue(String cookieHeader, String name) {
        return parseCookieHeader(cookieHeader).get(name);
    }

    private static LinkedHashMap<String, String> parseCookieHeader(String cookieHeader) {
        LinkedHashMap<String, String> cookies = new LinkedHashMap<>();
        if (StringUtils.isBlank(cookieHeader)) {
            return cookies;
        }
        for (String item : cookieHeader.split(";")) {
            String part = item.trim();
            if (part.isEmpty()) {
                continue;
            }
            int separator = part.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            cookies.put(part.substring(0, separator).trim(), part.substring(separator + 1).trim());
        }
        return cookies;
    }

    private static PublicKey loadPublicKey() {
        try {
            String key = PUBLIC_KEY
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replace("\n", "")
                    .trim();
            byte[] publicBytes = Base64.getDecoder().decode(key);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(publicBytes);
            return KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load BiliBili cookie refresh public key", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }
}
