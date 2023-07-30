package cn.har01d.alist_tvbox.util;

import cn.har01d.alist_tvbox.exception.BadRequestException;

import javax.xml.bind.DatatypeConverter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.StringJoiner;

public final class Utils {
    private static final int KB = 1024;
    private static final int MB = 1024 * KB;
    private static final int GB = 1024 * MB;
    private static final int[] mixinKeyEncTab = new int[]{
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
            61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
            36, 20, 34, 44, 52
    };

    public static String getMixinKey(String imgKey, String subKey) {
        String s = imgKey + subKey;
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            key.append(s.charAt(mixinKeyEncTab[i]));
        }
        return key.toString();
    }

    public static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(input.getBytes());
            byte[] digest = md.digest();
            return DatatypeConverter.printHexBinary(digest).toLowerCase();
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
    }

    public static String encodeUrl(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.toString());
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
    }

    public static String encryptWbi(Map<String, Object> params, String imgKey, String subKey) {
        String mixinKey = getMixinKey(imgKey, subKey);
        params.put("wts", System.currentTimeMillis() / 1000);
        StringJoiner param = new StringJoiner("&");
        params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> param.add(entry.getKey() + "=" + encodeUrl(entry.getValue().toString())));
        String s = param + mixinKey;
        String wbiSign = md5(s);
        return param.toString().replace("%2C", ",") + "&w_rid=" + wbiSign;
    }

    public static String byte2size(long size) {
        String result;
        String unit = "B";
        if (size >= GB) {
            result = String.format("%.2f", size / (double) GB);
            unit = "GB";
        } else if (size >= MB) {
            result = String.format("%.2f", size / (double) MB);
            unit = "MB";
        } else if (size >= KB) {
            result = String.format("%.2f", size / (double) KB);
            unit = "KB";
        } else {
            result = String.format("%d", size);
        }
        if (result.endsWith(".00")) {
            result = result.substring(0, result.length() - 3);
        }
        if (result.endsWith("0") && result.charAt(result.length() - 3) == '.') {
            result = result.substring(0, result.length() - 1);
        }
        return result + " " + unit;
    }

    public static String getPaths(String content) {
        StringBuilder sb = new StringBuilder();
        for (String line : content.split("\\n")) {
            if (line.split(":").length == 2) {
                sb.append(line).append("\\n");
            } else {
                sb.append("本地:").append(line).append("\\n");
            }
        }
        return sb.toString();
    }

    public static String getAliasPaths(String content) {
        StringBuilder sb = new StringBuilder();
        for (String line : content.split("\\n")) {
            String[] parts = line.split(":");
            line = parts[0];
            parts = line.split("\\$");
            if (parts.length == 2) {
                sb.append(parts[0] + ":" + parts[1]).append("\\n");
            } else {
                sb.append("本地:").append(line).append("\\n");
            }
        }
        return sb.toString();
    }

}
