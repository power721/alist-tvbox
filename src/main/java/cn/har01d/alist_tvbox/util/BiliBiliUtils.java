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
    private static final String COOKIE = "MZGWMZIYYZQTYnV2aWQzPURGOTlENUI5LTgzMTAtNjlGQy1ERTE0LTY0QkQzRTM1N0U5ODA3MjM5aW5mb2M7IGJ1dmlkND1DMDBCNjFBRi0xRDFELUNFMzMtNzY5Ri1ENDNERjZDQUJBRkYwODUyMy0wMjIwODA3MjAtdmRCakFuYUJadjVxM3B3cXBNM05QQSUzRCUzRDsgQ1VSUkVOVF9GTlZBTD00MDQ4OyBidXZpZF9mcD1iNTdlMTI5MjgzMDMwYmZlZWYwYWFhMzJhMThlNTFiZDsgaS13YW5uYS1nby1iYWNrPS0xOyBiX251dD0xMDA7IEZFRURfTElWRV9WRVJTSU9OPVY4OyBoZWFkZXJfdGhlbWVfdmVyc2lvbj1DTE9TRTsgQ1VSUkVOVF9RVUFMSVRZPTgwOyBycGRpZD18KEp8WUprfnVrbW0wSid1WSltSn4pa20pOyBpbm5lcnNpZ249MDsgYl91dD01OyBoaXQtbmV3LXN0eWxlLWR5bj0xOyBoaXQtZHluLXYyPTE7IF91dWlkPUZGM0E1QkJDLUQxNzItMTA1NTgtQ0ZCMS0xMDhFQTNDRDMyODRFNDkxNjdpbmZvYzsgaWZsb2dpbl93aGVuX3dlYl9wdXNoPTE7IGhvbWVfZmVlZF9jb2x1bW49NTsgUFZJRD0xOyBEZWRlVXNlcklEPTE3ODQ4MDU3NzA7IERlZGVVc2VySURfX2NrTWQ1PWE4ZTVkOGE4NDhmMmQ4OGU7IGJfbHNpZD05MTBCRDkxMDhGXzE4REJGNzc3MDI0OyBlbmFibGVfd2ViX3B1c2g9RU5BQkxFOyBTRVNTREFUQT00ODZhMDFmZiUyQzE3MjM4NjYzMjYlMkMwNzhhNCUyQTIyQ2pEU2F2TFN0dzctSDZJNU92WnE4WmYzQzFEMjItdTRmWlJEVGhCTE8xZklMc1p0ZFYwZGdwUWpLN0p3QlNBbXc4VVNWbVJ6VEhCVGEzQkhWV2haUjFoM2NtSlJNVTlOYTNCRE9XMTVaSG80VUhWNVFUVlJiSHBxUTFSSllteHBPQzFRT1doaWFDMVBZMDVYVkdwTFJubHNXRlJoVERoM1psRmxVemhMU3pkb01HWkpTbXN0ZFV4UklJRUM7IGJpbGlfamN0PWUxMjhkODU2NTU3MjllZmQyZjYyMjBjOGQzYWY1MjkwOyBzaWQ9NnpyMnIzY3g7IGJyb3dzZXJfcmVzb2x1dGlvbj0xNjUwLTEzMjY=";

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
