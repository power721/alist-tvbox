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
    private static final String COOKIE = "NJVJOGU0YJKTYnV2aWQzPURGOTlENUI5LTgzMTAtNjlGQy1ERTE0LTY0QkQzRTM1N0U5ODA3MjM5aW5mb2M7IGJ1dmlkND1DMDBCNjFBRi0xRDFELUNFMzMtNzY5Ri1ENDNERjZDQUJBRkYwODUyMy0wMjIwODA3MjAtdmRCakFuYUJadjVxM3B3cXBNM05QQSUzRCUzRDsgQ1VSUkVOVF9GTlZBTD00MDQ4OyBidXZpZF9mcD1iNTdlMTI5MjgzMDMwYmZlZWYwYWFhMzJhMThlNTFiZDsgaS13YW5uYS1nby1iYWNrPS0xOyBiX251dD0xMDA7IGhlYWRlcl90aGVtZV92ZXJzaW9uPUNMT1NFOyBDVVJSRU5UX1FVQUxJVFk9ODA7IHJwZGlkPXwoSnxZSmt+dWttbTBKJ3VZKW1KfilrbSk7IGlubmVyc2lnbj0wOyBiX3V0PTU7IGhpdC1uZXctc3R5bGUtZHluPTE7IGhpdC1keW4tdjI9MTsgX3V1aWQ9RkYzQTVCQkMtRDE3Mi0xMDU1OC1DRkIxLTEwOEVBM0NEMzI4NEU0OTE2N2luZm9jOyBpZmxvZ2luX3doZW5fd2ViX3B1c2g9MTsgaG9tZV9mZWVkX2NvbHVtbj01OyBQVklEPTE7IERlZGVVc2VySUQ9MTc4NDgwNTc3MDsgRGVkZVVzZXJJRF9fY2tNZDU9YThlNWQ4YTg0OGYyZDg4ZTsgZW5hYmxlX3dlYl9wdXNoPUVOQUJMRTsgRkVFRF9MSVZFX1ZFUlNJT049Vl9XQVRDSExBVEVSX1BJUF9XSU5ET1czOyBiX2xzaWQ9NDQ5NkREQzEwXzE4RkYyQTM0MjUwOyBTRVNTREFUQT02YWI4OTZkMiUyQzE3MzMzMTQ3NjclMkM1ODkwOCUyQTYyQ2pDR1Z4VVFGVE1TRnN1MVBFekYtMDlKbGFtMUh4NjBkSmg4NnlTYjVtWXZ5OGcwa0ttYzNITWdGTEY0QnNPREFiUVNWbEV4VlRGQ01ITlVjMHBvY0d0blpHVnVkVk5RUjJOWWRsZEllWFZTYUZwMExVSlVTMU5OZW1SWWJuTTVWVk5vTFcxYWFtczFlRVpmVVcxbmJtSXRPRVJaVFZGaGQyaDVWVlF0UTNWbGExRkZlV2hMU0VWQklJRUM7IGJpbGlfamN0PTRlMjIwZDU3M2I3N2ViYzUyNDc1ZjE0ZGI3MmU1Mjk5OyBzaWQ9NHJ4NXc0bWY7IGJyb3dzZXJfcmVzb2x1dGlvbj0xNDcwLTEzMjYK";

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
