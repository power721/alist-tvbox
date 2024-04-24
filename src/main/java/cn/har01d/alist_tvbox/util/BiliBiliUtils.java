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
    private static final String COOKIE = "N2Y2ODG1MTATU0VTU0RBVEE9NjRkYTJmYjclMkMxNzI5MzIzNDM0JTJDZTE1YTclMkE0MkNqQ2llcUZ2eHB6czV0R0FDVlhtREdaNUtKSXlWWVFTNTN0Q29MV2dGbVpab0xlZXhqQ0VyM29DNWdwU0hTRk85MllTVmtGd1ZrUjBkVU4zVFU5R2JIcDJPRU4wVEVaS2NITjFlRkkzTTI1bUxXUTNRVmhVVFhaTE9EWktUVk5uV2s5MFMzRjZObGhxU2kwd2Nub3dORWRXUzFFNE1YTkphVzFEYjJkTWNsRlVWVGwyWldOcFEwdG5JSUVDO2JpbGlfamN0PTg1YmFiZjZjOWRmZjUwY2E4NzY0ZTNlY2UzNmEzNGU2O0RlZGVVc2VySUQ9MTc4NDgwNTc3MDtEZWRlVXNlcklEX19ja01kNT1hOGU1ZDhhODQ4ZjJkODhlO3NpZD1wc25wNDZuMg==";

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
