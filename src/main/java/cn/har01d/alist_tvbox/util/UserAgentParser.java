package cn.har01d.alist_tvbox.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UserAgentParser {
    private static final Pattern EDGE = Pattern.compile("Edg[a-z]*/(\\d+)");
    private static final Pattern OPERA = Pattern.compile("OPR/(\\d+)");
    private static final Pattern FIREFOX = Pattern.compile("Firefox/(\\d+)");
    private static final Pattern CHROME = Pattern.compile("Chrome/(\\d+)");
    private static final Pattern SAFARI = Pattern.compile("Version/(\\d+).*Safari|(?:^|[^a-z])Safari/(\\d+)");

    private UserAgentParser() {
    }

    public static String parseBrowser(String ua) {
        if (ua == null || ua.isBlank()) {
            return "未知";
        }
        if (ua.contains("ATV-Player") || ua.contains("ATV Player")) {
            return "ATV Player";
        }
        Matcher m = EDGE.matcher(ua);
        if (m.find()) return "Edge " + m.group(1);
        m = OPERA.matcher(ua);
        if (m.find()) return "Opera " + m.group(1);
        m = FIREFOX.matcher(ua);
        if (m.find()) return "Firefox " + m.group(1);
        m = CHROME.matcher(ua);
        if (m.find()) return "Chrome " + m.group(1);
        m = SAFARI.matcher(ua);
        if (m.find()) {
            String v = m.group(1) != null ? m.group(1) : m.group(2);
            return "Safari " + v;
        }
        return "未知";
    }

    public static String parseOs(String ua) {
        if (ua == null || ua.isBlank()) {
            return "未知";
        }
        String lower = ua.toLowerCase();
        if (lower.contains("windows") || lower.contains("win64") || lower.contains("win32")) {
            return "Windows";
        }
        if (lower.contains("iphone") || lower.contains("ipad") || lower.contains("ios")) {
            return "iOS";
        }
        if (lower.contains("mac os x") || lower.contains("macintosh") || lower.contains("darwin")) {
            return "macOS";
        }
        if (lower.contains("android")) {
            return "Android";
        }
        if (lower.contains("linux") || lower.contains("x11") || lower.contains("unix")) {
            return "Linux";
        }
        return "未知";
    }
}
