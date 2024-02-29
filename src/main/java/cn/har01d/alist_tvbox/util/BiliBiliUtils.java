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
    private static final String COOKIE = "Y2QXYWQWOGITU0VTU0RBVEE9NTdhNTVlM2ElMkMxNzI0NzU4NzgyJTJDYTIyYjYlMkEyMkNqQjBZcnFyRVdMczVMWXVfQlFYMWNRbXd2S3BHYkVyZTZOVmdnSjhZOWE5Z3VUbFZqRGNfcWR3OThrQm1pT0hxOTRTVmsxSlVuRmxUR2xuY3pGMk9XVnpkUzFwTkhkZlRWQXpSMHd3YldScVdqWmZNVmxYVldaZk1VUnFhV05NVUhCeWMwZERSVUpDV0hjNGNVSTVjRFI1TFU1aFYyNVFOVVZTVFhKdmVtcE1SMlZJTjNKSFpWbDNJSUVDO2JpbGlfamN0PWM3ZWZhNmI5N2FhZjVmMWMwNGFkNDE0ODZhNzA4NmVmO0RlZGVVc2VySUQ9MTc4NDgwNTc3MDtEZWRlVXNlcklEX19ja01kNT1hOGU1ZDhhODQ4ZjJkODhlO3NpZD02cm03dDM1Zg";

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
