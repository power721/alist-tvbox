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
    private static final int MID = 534587613;
    private static final String COOKIE = "11t9VCKbJ5jxU0VTU0RBVEE9NjYzNmJmYjIlMkMxNzA2MjQ2NzUwJTJDYjcyMzIlMkE3MXZrRmtGVlBBZEJZZUNQdkhIRVlIVEFCWmFDMzJBclBISlk5RllmalhJU0w2SXo0Yk1jUnAwTWVkV3lNNEV3b1ZuRFJ2MEFBQVJnQTtiaWxpX2pjdD0yNTllOTMwMmM2ODk4NGQ0ZWMxMGI3YjdiMGY5YmRiZDtEZWRlVXNlcklEPTE3ODQ4MDU3NzA7RGVkZVVzZXJJRF9fY2tNZDU9YThlNWQ4YTg0OGYyZDg4ZTtzaWQ9N2plNG9pb2M=";

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

    public static int getMid() {
        return (MID ^ (123456789 << 3 + 1)) - 3;
    }
}
