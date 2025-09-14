package cn.har01d.alist_tvbox.util;

import cn.har01d.alist_tvbox.exception.BadRequestException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.xml.bind.DatatypeConverter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
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
    private static final Pattern EPISODE = Pattern.compile("^(.+)[sS]\\d{1,2}[eE]?\\d?$");
    private static final Pattern EPISODE2 = Pattern.compile("^(.+EP)\\d?$");
    private static final Pattern NUMBERS = Pattern.compile("\\d+");
    private static final List<String> userAgents = new ArrayList<>();
    private static final SecureRandom secureRandom = new SecureRandom();
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final int[] mixinKeyEncTab = new int[]{
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
            61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
            36, 20, 34, 44, 52
    };

    public static boolean inDocker;

    static {
        readUserAgents();
        inDocker = System.getenv("INSTALL") != null && Files.exists(Path.of("/entrypoint.sh"));
    }

    private static void readUserAgents() {
        try {
            var resource = new ClassPathResource("ua.txt");
            String lines = resource.getContentAsString(StandardCharsets.UTF_8);
            userAgents.addAll(Arrays.asList(lines.split("\n")));
            log.info("Read {} user agents", userAgents.size());
        } catch (IOException e) {
            log.warn("read user agents failed: ", e);
        }
    }

    public static String getUserAgent() {
        if (userAgents.isEmpty()) {
            return Constants.USER_AGENT;
        }
        return userAgents.get(secureRandom.nextInt(userAgents.size()));
    }

    public static String getCommonPrefix(List<String> names) {
        return getCommonPrefix(names, true);
    }

    public static String getCommonPrefix(List<String> names, boolean pretty) {
        if (names.size() > 1 && names.get(0).isEmpty()) {
            names = names.subList(1, names.size());
        }
        int n = names.size();
        if (n < 5) return "";
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
        if (NUMBERS.matcher(ans).matches()) {
            return "";
        }
        return ans;
    }

    public static String getCommonSuffix(List<String> names) {
        return getCommonSuffix(names, true);
    }

    public static String getCommonSuffix(List<String> names, boolean pretty) {
        int n = names.size();
        if (n < 5) return "";
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
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
        if (size > 999 * MB) {
            result = String.format("%.2f", size / (double) GB);
            unit = "GB";
        } else if (size > 999 * KB) {
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
        if (result.endsWith("0") && result.length() > 3 && result.charAt(result.length() - 3) == '.') {
            result = result.substring(0, result.length() - 1);
        }
        return result + " " + unit;
    }

    public static int executeUpdate(String sql) {
        int code = 1;
        try {
            ProcessBuilder builder = new ProcessBuilder();
            builder.inheritIO();
            builder.command("sqlite3", Utils.getAListPath("data/data.db"), sql);
            Process process = builder.start();
            code = process.waitFor();
        } catch (Exception e) {
            log.warn("", e);
        }
        log.debug("executeUpdate {} result: {}", sql, code);
        return code;
    }

    public static String executeQuery(String sql) {
        log.debug("executeQuery {}", sql);
        try {
            ProcessBuilder builder = new ProcessBuilder();
            builder.command("sqlite3", Utils.getAListPath("data/data.db"), sql);
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
                sb.append(parts[1]).append(":").append(parts[0]).append("\n");
            } else {
                sb.append("本地:").append(line).append("\n");
            }
        }
        return sb.toString();
    }

    public static Path getDataPath(String... path) {
        String base = inDocker ? "/data" : "/opt/atv/data";
        return Path.of(base, path);
    }

    public static Path getWebPath(String... path) {
        String base = inDocker ? "/www" : "/opt/atv/www";
        return Path.of(base, path);
    }

    public static Path getIndexPath(String... path) {
        String base = inDocker ? "/data/index" : "/opt/atv/index";
        return Path.of(base, path);
    }

    public static Path getLogPath(String name) {
        String base = inDocker ? "/data/log" : "/opt/atv/log";
        return Path.of(base, name);
    }

    public static String getAListPath(String name) {
        if (name.startsWith("/")) {
            return name;
        }
        String base = inDocker ? "/opt/alist/" : "/opt/atv/alist/";
        return base + name;
    }

    public static int durationToSeconds(String duration) {
        if (StringUtils.isBlank(duration)) {
            return 0;
        }
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

    public static String trim(String text) {
        if (text == null) {
            return null;
        }
        return text.trim();
    }

    public static Collection<File> listFiles(Path path, String... ext) {
        try {
            return FileUtils.listFiles(path.toFile(), ext, false);
        } catch (UncheckedIOException e) {
            return List.of();
        }
    }

    public static Map<String, Object> readJson(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public static String toJsonString(Object object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public static String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");

        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }

        // In case of multiple IPs (comma-separated), take the first one
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0];
        }

        return ip;
    }

    public static String getQrCode(String text) throws IOException {
        log.info("get qr code for text: {}", text);
        if ("true".equals(System.getenv("NATIVE"))) {
            return getQrCodeByCli(text);
        }

        byte[] bytes = generateQRCodeImage(text);
        return Base64.getEncoder().encodeToString(bytes);
    }

    public static String getQrCodeByCli(String text) throws IOException {
        try {
            ProcessBuilder builder = new ProcessBuilder();
            builder.command("/atv-cli", text);
            builder.inheritIO();
            Process process = builder.start();
            process.waitFor();
        } catch (Exception e) {
            log.warn("", e);
        }
        Path file = Utils.getWebPath("tvbox", "qr.png");
        return Base64.getEncoder().encodeToString(Files.readAllBytes(file));
    }

    public static byte[] generateQRCodeImage(String text) throws IOException {
        try {
            return generateQRCodeImage(text, 256, 256);
        } catch (WriterException e) {
            throw new IOException("Write qr file failed.", e);
        }
    }

    public static byte[] generateQRCodeImage(String text, int width, int height)
            throws WriterException, IOException {
        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        BitMatrix bitMatrix = qrCodeWriter.encode(text, BarcodeFormat.QR_CODE, width, height);

        ByteArrayOutputStream pngOutputStream = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(bitMatrix, "PNG", pngOutputStream);
        return pngOutputStream.toByteArray();
    }

    public static String generateUsername() {
        String upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String lower = "abcdefghijklmnopqrstuvwxyz";
        String digits = "0123456789";

        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder();

        password.append(upper.charAt(random.nextInt(upper.length())));
        password.append(lower.charAt(random.nextInt(lower.length())));
        password.append(digits.charAt(random.nextInt(digits.length())));

        String all = upper + lower + digits;
        for (int i = 4; i < 8; i++) {
            password.append(all.charAt(random.nextInt(all.length())));
        }

        char[] chars = password.toString().toCharArray();
        for (int i = 0; i < chars.length; i++) {
            int j = random.nextInt(chars.length);
            char temp = chars[i];
            chars[i] = chars[j];
            chars[j] = temp;
        }

        return new String(chars);
    }

    public static String generateSecurePassword() {
        String upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String lower = "abcdefghijklmnopqrstuvwxyz";
        String digits = "0123456789";
        String special = "!@#$%^&*";

        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder();

        password.append(upper.charAt(random.nextInt(upper.length())));
        password.append(lower.charAt(random.nextInt(lower.length())));
        password.append(digits.charAt(random.nextInt(digits.length())));
        password.append(special.charAt(random.nextInt(special.length())));

        String all = upper + lower + digits + special;
        for (int i = 4; i < 12; i++) {
            password.append(all.charAt(random.nextInt(all.length())));
        }

        char[] chars = password.toString().toCharArray();
        for (int i = 0; i < chars.length; i++) {
            int j = random.nextInt(chars.length);
            char temp = chars[i];
            chars[i] = chars[j];
            chars[j] = temp;
        }

        return new String(chars);
    }

}
