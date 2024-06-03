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
    private static final String COOKIE = "NMQ2Y2IYNDQTYnV2aWQzPURGOTlENUI5LTgzMTAtNjlGQy1ERTE0LTY0QkQzRTM1N0U5ODA3MjM5aW5mb2M7IGJ1dmlkND1DMDBCNjFBRi0xRDFELUNFMzMtNzY5Ri1ENDNERjZDQUJBRkYwODUyMy0wMjIwODA3MjAtdmRCakFuYUJadjVxM3B3cXBNM05QQSUzRCUzRDsgQ1VSUkVOVF9GTlZBTD00MDQ4OyBidXZpZF9mcD1iNTdlMTI5MjgzMDMwYmZlZWYwYWFhMzJhMThlNTFiZDsgaS13YW5uYS1nby1iYWNrPS0xOyBiX251dD0xMDA7IGhlYWRlcl90aGVtZV92ZXJzaW9uPUNMT1NFOyBDVVJSRU5UX1FVQUxJVFk9ODA7IHJwZGlkPXwoSnxZSmt+dWttbTBKJ3VZKW1KfilrbSk7IGlubmVyc2lnbj0wOyBiX3V0PTU7IGhpdC1uZXctc3R5bGUtZHluPTE7IGhpdC1keW4tdjI9MTsgX3V1aWQ9RkYzQTVCQkMtRDE3Mi0xMDU1OC1DRkIxLTEwOEVBM0NEMzI4NEU0OTE2N2luZm9jOyBpZmxvZ2luX3doZW5fd2ViX3B1c2g9MTsgaG9tZV9mZWVkX2NvbHVtbj01OyBQVklEPTE7IERlZGVVc2VySUQ9MTc4NDgwNTc3MDsgRGVkZVVzZXJJRF9fY2tNZDU9YThlNWQ4YTg0OGYyZDg4ZTsgZW5hYmxlX3dlYl9wdXNoPUVOQUJMRTsgRkVFRF9MSVZFX1ZFUlNJT049Vl9XQVRDSExBVEVSX1BJUF9XSU5ET1czOyBiaWxpX3RpY2tldD1leUpoYkdjaU9pSklVekkxTmlJc0ltdHBaQ0k2SW5Nd015SXNJblI1Y0NJNklrcFhWQ0o5LmV5SmxlSEFpT2pFM01UYzFOVFF6TVRBc0ltbGhkQ0k2TVRjeE56STVOVEExTUN3aWNHeDBJam90TVgwLnNXVW1EVU8xeGJQdXdYUnB4TUd4YVZHS3dNV2JfaFozVGNiOFZ0QVVnWEE7IGJpbGlfdGlja2V0X2V4cGlyZXM9MTcxNzU1NDI1MDsgYl9sc2lkPTU3MzE3NDEwNF8xOEZEREE2RTk1RDsgU0VTU0RBVEE9OGU4YTQ0NGYlMkMxNzMyOTYyNjg2JTJDNzE5MDklMkE2MkNqQm9fbG5aMzlsMnpucmxjM0pReDBLM2MwTnRRWmJ6TW9DREJRX2VibUNGaUp3SWs3a01ORkhqM2xydThvWWg0ZmNTVm5oUVEzRk9hbDlSYW1kalUwVTJXbkZ3YkdoMVdHbFpUekV6Y25sQk1EUnVjemRRVlZoa015MXJNRlJ5WkhGNllUbGZUVTV0VUU5aFNXbEhVM3BTWjNSeVMxUnpRMVpoTWtwVVluZFhNRFZDZVRkNFJGOTNJSUVDOyBiaWxpX2pjdD05ZTk5NTk0OGYzZmQ1OWJlZWE1M2M3Y2YwOWYyMGFjODsgYnJvd3Nlcl9yZXNvbHV0aW9uPTE0NzAtMTMyNjsgc2lkPWc2ZmRrajNn";

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
