package cn.har01d.alist_tvbox.util;

import cn.har01d.alist_tvbox.exception.BadRequestException;
import jakarta.xml.bind.DatatypeConverter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
public final class Utils {
    private static final int KB = 1024;
    private static final int MB = 1024 * KB;
    private static final int GB = 1024 * MB;
    private static final String StaticHashSalt = "https://github.com/alist-org/alist";
    private static final Pattern EPISODE = Pattern.compile("^(.+)[sS]\\d{1,2}[eE]?\\d?$");
    private static final Pattern EPISODE2 = Pattern.compile("^(.+EP)\\d?$");
    private static final int[] mixinKeyEncTab = new int[]{
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
            61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
            36, 20, 34, 44, 52
    };

    public static String getCommonPrefix(List<String> names) {
        return getCommonPrefix(names, true);
    }

    public static String getCommonPrefix(List<String> names, boolean pretty) {
        int n = names.size();
        if (n <= 1) return "";
        String ans = names.get(0);
        for (int i = 1; i < n; i++) {
            int j = 0;
            while (j < ans.length() && j < names.get(i).length() && ans.charAt(j) == names.get(i).charAt(j)) {
                j++;
            }
            ans = names.get(i).substring(0, j);
        }

        if (pretty) {
            Matcher matcher = EPISODE.matcher(ans);
            if (matcher.matches()) {
                return matcher.group(1);
            }
            matcher = EPISODE2.matcher(ans);
            if (matcher.matches()) {
                return matcher.group(1);
            }
        }

        if (pretty && "《".equals(ans)) {
            return "";
        }
        return ans;
    }

    public static String getCommonSuffix(List<String> names) {
        return getCommonSuffix(names, true);
    }

    public static String getCommonSuffix(List<String> names, boolean pretty) {
        int n = names.size();
        if (n <= 1) return "";
        names = names.stream().map(e -> new StringBuilder(e).reverse().toString()).collect(Collectors.toList());
        String text = new StringBuilder(getCommonPrefix(names)).reverse().toString();
        if (pretty && text.startsWith("集")) {
            return text.substring(1);
        }
        return text;
    }

    public static String removeExt(String text) {
        return text.contains(".") ? text.substring(0, text.lastIndexOf(".")) : text;
    }

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
        if (StringUtils.isBlank(value)) {
            return "";
        }

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

    public static int executeUpdate(String sql) {
        int code = 1;
        try {
            ProcessBuilder builder = new ProcessBuilder();
            builder.inheritIO();
            builder.command("sqlite3", "/opt/alist/data/data.db", sql);
            Process process = builder.start();
            code = process.waitFor();
        } catch (Exception e) {
            log.warn("", e);
        }
        log.debug("executeUpdate {} result: {}", sql, code);
        return code;
    }

    private static String secure(String text) {
        return text
                .replaceAll("\"refresh_token\":\".+?\"", "\"refresh_token\":\"******\"")
                .replaceAll("\"RefreshToken\":\".+?\"", "\"RefreshToken\":\"*********\"")
                .replaceAll("\"RefreshTokenOpen\":\".+?\"", "\"RefreshTokenOpen\":\"*********\"")
                .replaceAll("\"password\":\".+?\"", "\"password\":\"***\"")
                .replaceAll("'$.password', '.+?'", "'$.password', '***'")
                ;
    }

    public static String executeQuery(String sql) {
        log.debug("executeQuery {}", sql);
        try {
            ProcessBuilder builder = new ProcessBuilder();
            builder.command("sqlite3", "/opt/alist/data/data.db", sql);
            Process process = builder.start();
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
                sb.append(System.getProperty("line.separator"));
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("", e);
        }
        return "";
    }

    public static int execute(String command) {
        int code = 1;
        try {
            ProcessBuilder builder = new ProcessBuilder();
            builder.inheritIO();
            builder.command("bash", "-c", command);
            Process process = builder.start();
            code = process.waitFor();
        } catch (Exception e) {
            log.warn("", e);
        }
        log.debug("execute {} result: {}", command, code);
        return code;
    }

    public static String getAliasPaths(String content) {
        StringBuilder sb = new StringBuilder();
        for (String line : content.split("\\n")) {
            String[] parts = line.split(":");
            if (parts.length == 2) {
                sb.append(parts[1]).append(":").append(parts[0]).append("\\n");
            } else {
                sb.append("本地:").append(line).append("\\n");
            }
        }
        return sb.toString();
    }

    public static long durationToSeconds(String duration) {
        try {
            String[] parts = duration.split(":");
            if (parts.length == 1) {
                return Integer.parseInt(duration);
            } else if (parts.length == 2) {
                return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
            }
        } catch (Exception e) {
            log.warn("{}", e);
        }
        return 0;
    }

    public static String secondsToDuration(long seconds) {
        long hour = seconds / 3600;
        long minute = (seconds - hour * 3600) / 60;
        if (hour > 0) {
            return String.format("%d:%02d:%02d", hour, minute, seconds % 60);
        }
        return String.format("%d:%02d", minute, seconds % 60);
    }

    private static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(bytes);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String hash(String password, String slat) {
        return sha256Hex(password + "-" + slat);
    }

    public static String hashPassword(String password, String slat) {
        return hash(hash(password, StaticHashSalt), slat);
    }

    public static boolean isLocalAddress() {
        String uri = ServletUriComponentsBuilder.fromCurrentRequest().toUriString();
        return uri.startsWith("http://192.168.");
    }

    public static void zipFile(File fileToZip, String fileName, ZipOutputStream zipOut) throws IOException {
        if (fileToZip.isHidden()) {
            return;
        }

        if (fileToZip.isDirectory()) {
            if (fileName.endsWith("/")) {
                zipOut.putNextEntry(new ZipEntry(fileName));
                zipOut.closeEntry();
            } else {
                zipOut.putNextEntry(new ZipEntry(fileName + "/"));
                zipOut.closeEntry();
            }

            File[] children = fileToZip.listFiles();
            if (children == null) {
                return;
            }

            for (File childFile : children) {
                zipFile(childFile, fileName + "/" + childFile.getName(), zipOut);
            }
            return;
        }

        try (FileInputStream fis = new FileInputStream(fileToZip)) {
            ZipEntry zipEntry = new ZipEntry(fileName);
            zipOut.putNextEntry(zipEntry);
            byte[] bytes = new byte[4096];
            int length;
            while ((length = fis.read(bytes)) >= 0) {
                zipOut.write(bytes, 0, length);
            }
        }
    }

}
