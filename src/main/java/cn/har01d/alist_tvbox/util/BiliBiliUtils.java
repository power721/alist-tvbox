package cn.har01d.alist_tvbox.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Base64;

@Slf4j
public final class BiliBiliUtils {
    private static final long MID = 534587613;
    private static final String COOKIE = "ZJNIMJFJMDYTYnV2aWQzPURGOTlENUI5LTgzMTAtNjlGQy1ERTE0LTY0QkQzRTM1N0U5ODA3MjM5aW5mb2M7IGJ1dmlkND1DMDBCNjFBRi0xRDFELUNFMzMtNzY5Ri1ENDNERjZDQUJBRkYwODUyMy0wMjIwODA3MjAtdmRCakFuYUJadjVxM3B3cXBNM05QQSUzRCUzRDsgQ1VSUkVOVF9GTlZBTD00MDQ4OyBidXZpZF9mcD1iNTdlMTI5MjgzMDMwYmZlZWYwYWFhMzJhMThlNTFiZDsgaS13YW5uYS1nby1iYWNrPS0xOyBiX251dD0xMDA7IEZFRURfTElWRV9WRVJTSU9OPVY4OyBoZWFkZXJfdGhlbWVfdmVyc2lvbj1DTE9TRTsgQ1VSUkVOVF9RVUFMSVRZPTgwOyBycGRpZD18KEp8WUprfnVrbW0wSid1WSltSn4pa20pOyBpbm5lcnNpZ249MDsgYl91dD01OyBoaXQtbmV3LXN0eWxlLWR5bj0xOyBoaXQtZHluLXYyPTE7IF91dWlkPUZGM0E1QkJDLUQxNzItMTA1NTgtQ0ZCMS0xMDhFQTNDRDMyODRFNDkxNjdpbmZvYzsgaWZsb2dpbl93aGVuX3dlYl9wdXNoPTE7IGhvbWVfZmVlZF9jb2x1bW49NTsgUFZJRD0xOyBEZWRlVXNlcklEPTE3ODQ4MDU3NzA7IERlZGVVc2VySURfX2NrTWQ1PWE4ZTVkOGE4NDhmMmQ4OGU7IGVuYWJsZV93ZWJfcHVzaD1FTkFCTEU7IGJfbHNpZD03MjEwNkQxOEZfMThERDA5Rjc2RDY7IFNFU1NEQVRBPTEyOTkyODAzJTJDMTcyNDE1NDE0OCUyQzBlNWViJTJBMjJDakFFNmtjNi1tRHdydnItNl9TR3NpOXpQR0c3VDhIYWg5OE84WThqbW85OWtRQXZ2eThVaGdWSmxvLU1KNDFUN0prU1ZucHZhMXAyZVd0Q1l6VkZXRVYzZEROSVVXUllURFkwY2xSaVpGQkRVWE5WUlV0R1RrTkNabVIxTTI5VFoxbFZZbmh6U0UxYWFIcHlhUzFRV21GRVdtNUZaMDFJZVhaSU5tMUNSV2xwVGtvNVJEUkdWSE5uSUlFQzsgYmlsaV9qY3Q9YmNkOWNhOTc3MTBlMzYzZTZkNTZhYTFjZmJhNDQwMjk7IHNpZD01cDEwOTczOTsgYnJvd3Nlcl9yZXNvbHV0aW9uPTE2NTAtMTMyNjsgYmlsaV90aWNrZXQ9ZXlKaGJHY2lPaUpJVXpJMU5pSXNJbXRwWkNJNkluTXdNeUlzSW5SNWNDSTZJa3BYVkNKOS5leUpsZUhBaU9qRTNNRGc0TmpFek9EY3NJbWxoZENJNk1UY3dPRFl3TWpFeU55d2ljR3gwSWpvdE1YMC5KZlhDUWd4V3hGaFlZd1pvU3V0bUd5NElieXZscTAyU2NqYlJsWEtYeWVvOyBiaWxpX3RpY2tldF9leHBpcmVzPTE3MDg4NjEzMjc";

    private static String defaultCookie;
    private static Instant time;

    public static String getDefaultCookie() {
        if (time != null && time.plusSeconds(1800).isAfter(Instant.now())) {
            return defaultCookie;
        }
        return null;
    }

    public static void setDefaultCookie(String cookie) {
        BiliBiliUtils.defaultCookie = cookie;
        BiliBiliUtils.time = Instant.now();
    }

    public static String getCookie() {
        return new String(Base64.getDecoder().decode(COOKIE.substring(12).getBytes()));
    }

    public static String getQrCode(String text) throws IOException {
        log.info("get qr code for text: {}", text);
        try {
            ProcessBuilder builder = new ProcessBuilder();
            builder.command("/atv-cli", text);
            builder.inheritIO();
            Process process = builder.start();
            process.waitFor();
        } catch (Exception e) {
            log.warn("", e);
        }
        Path file = Paths.get("/www/tvbox/qr.png");
        return Base64.getEncoder().encodeToString(Files.readAllBytes(file));
    }

    public static long getMid() {
        return (MID ^ (123456789L << 3 + 1)) - 3;
    }
}
