package cn.har01d.alist_tvbox.util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConfigPayloadExtractor {

    private static final Pattern BASE64_MARKER =
            Pattern.compile("[A-Za-z0-9]{8,16}\\*\\*");

    private ConfigPayloadExtractor() {
    }

    /**
     * 从文件内容中提取JSON文本
     */
    public static String extract(String text) {

        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        // 1. 先尝试直接当文本

        String result = tryDecode(text);
        if (looksLikeJson(result)) {
            return result;
        }

        // 2. WebP
        result = tryWebp(bytes);
        if (looksLikeJson(result)) {
            return result;
        }

        // 3. PNG
        result = tryPng(bytes);
        if (looksLikeJson(result)) {
            return result;
        }

        // 4. JPG
        result = tryJpeg(bytes);
        if (looksLikeJson(result)) {
            return result;
        }

        // 5. GIF
        result = tryGif(bytes);
        if (looksLikeJson(result)) {
            return result;
        }

        return null;
    }

    private static String tryWebp(byte[] bytes) {

        if (bytes.length < 12) {
            return null;
        }

        if (bytes[0] != 'R'
                || bytes[1] != 'I'
                || bytes[2] != 'F'
                || bytes[3] != 'F') {
            return null;
        }

        if (bytes[8] != 'W'
                || bytes[9] != 'E'
                || bytes[10] != 'B'
                || bytes[11] != 'P') {
            return null;
        }

        int riffSize =
                (bytes[4] & 0xff)
                        | ((bytes[5] & 0xff) << 8)
                        | ((bytes[6] & 0xff) << 16)
                        | ((bytes[7] & 0xff) << 24);

        int end = riffSize + 8;

        if (end >= bytes.length) {
            return null;
        }

        String tail = new String(
                bytes,
                end,
                bytes.length - end,
                StandardCharsets.UTF_8
        );

        return tryDecode(tail);
    }

    private static String tryPng(byte[] bytes) {

        byte[] pngEnd = {
                0x49, 0x45, 0x4E, 0x44,
                (byte) 0xAE,
                0x42,
                0x60,
                (byte) 0x82
        };

        int pos = indexOf(bytes, pngEnd);

        if (pos < 0) {
            return null;
        }

        int end = pos + pngEnd.length;

        if (end >= bytes.length) {
            return null;
        }

        String tail = new String(
                bytes,
                end,
                bytes.length - end,
                StandardCharsets.UTF_8
        );

        return tryDecode(tail);
    }

    private static String tryJpeg(byte[] bytes) {

        byte[] jpegEnd = {
                (byte) 0xFF,
                (byte) 0xD9
        };

        int pos = lastIndexOf(bytes, jpegEnd);

        if (pos < 0) {
            return null;
        }

        int end = pos + 2;

        if (end >= bytes.length) {
            return null;
        }

        String tail = new String(
                bytes,
                end,
                bytes.length - end,
                StandardCharsets.UTF_8
        );

        return tryDecode(tail);
    }

    private static String tryGif(byte[] bytes) {

        byte[] gifEnd = {
                0x00,
                0x3B
        };

        int pos = lastIndexOf(bytes, gifEnd);

        if (pos < 0) {
            return null;
        }

        int end = pos + 2;

        if (end >= bytes.length) {
            return null;
        }

        String tail = new String(
                bytes,
                end,
                bytes.length - end,
                StandardCharsets.UTF_8
        );

        return tryDecode(tail);
    }

    private static String tryDecode(String text) {

        if (text == null) {
            return null;
        }

        String text2 = text.replace("\r", "")
                .replace("\n", "");

        Matcher matcher = BASE64_MARKER.matcher(text2);

        if (matcher.find()) {

            String base64 =
                    text2.substring(
                            matcher.end()
                    );

            base64 = base64.replaceAll("\\s+", "");

            try {

                return new String(
                        Base64.getDecoder().decode(base64),
                        StandardCharsets.UTF_8
                );

            } catch (Exception ignore) {
            }
        }

        int idx = text.indexOf("eyJ");

        if (idx >= 0) {

            String base64 = text.substring(idx)
                    .replaceAll("\\s+", "");

            try {

                return new String(
                        Base64.getDecoder().decode(base64),
                        StandardCharsets.UTF_8
                );

            } catch (Exception ignore) {
            }
        }

        return text;
    }

    private static boolean looksLikeJson(String text) {

        if (text == null) {
            return false;
        }

        text = text.trim();

        return text.startsWith("{")
                || text.startsWith("[");
    }

    private static int indexOf(byte[] source, byte[] target) {

        outer:
        for (int i = 0; i <= source.length - target.length; i++) {

            for (int j = 0; j < target.length; j++) {

                if (source[i + j] != target[j]) {
                    continue outer;
                }
            }

            return i;
        }

        return -1;
    }

    private static int lastIndexOf(byte[] source, byte[] target) {

        outer:
        for (int i = source.length - target.length; i >= 0; i--) {

            for (int j = 0; j < target.length; j++) {

                if (source[i + j] != target[j]) {
                    continue outer;
                }
            }

            return i;
        }

        return -1;
    }
}
