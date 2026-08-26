package cn.har01d.alist_tvbox.service.sitesearch;

import cn.har01d.alist_tvbox.entity.SettingRepository;
import org.apache.commons.lang3.StringUtils;

import java.net.IDN;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站点搜索源公共工具:Setting 读取、站点地址归一化、提取码折叠、Cookie 解析拼接、盘型判定。
 * 盘链/观影/蜗牛/玩偶四个源的等价逻辑收口于此,改口径只动这一处。
 */
final class SiteSearchSupport {
    private SiteSearchSupport() {
    }

    /** Setting 读取(仓库空容错,缺项/空值回落空串)。 */
    static String setting(SettingRepository repository, String name) {
        if (repository == null) {
            return "";
        }
        return repository.findById(name).map(s -> StringUtils.defaultString(s.getValue())).orElse("");
    }

    /**
     * 结构化提取码折进 URL 查询参数:URL 已带 pwd=/password=/passcode=(不区分大小写)不重复折;
     * 参数名由调用方按盘型决定(百度/迅雷/123 用 pwd=,115 用 password=)。密码值原样拼入,
     * 需要 URL 编码的源(盘链)自行预编码。
     */
    static String appendPasswordParam(String url, String password, String param) {
        String raw = StringUtils.trimToEmpty(url);
        String code = StringUtils.trimToEmpty(password);
        if (raw.isEmpty() || code.isEmpty() || StringUtils.isEmpty(param)) {
            return raw;
        }
        String lowered = raw.toLowerCase();
        if (lowered.contains("pwd=") || lowered.contains("password=") || lowered.contains("passcode=")) {
            return raw;
        }
        return raw + (raw.contains("?") ? "&" : "?") + param + code;
    }

    /**
     * 站点地址归一化:补 scheme、IDNA 编码中文域名、去路径(py _normalize_host;
     * 不能先过 URI.create——Java URI 拒绝非 ASCII 主机);空/非法回落 fallback。
     */
    static String normalizeHost(String value, String fallback) {
        String host = StringUtils.trimToEmpty(value).replaceAll("/+$", "");
        if (host.isEmpty()) {
            return fallback;
        }
        String lower = host.toLowerCase();
        String scheme = "https";
        if (lower.startsWith("http://")) {
            scheme = "http";
            host = host.substring(7);
        } else if (lower.startsWith("https://")) {
            host = host.substring(8);
        }
        int cut = StringUtils.indexOfAny(host, '/', '?', '#');
        if (cut >= 0) {
            host = host.substring(0, cut);
        }
        String port = "";
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) {
            port = host.substring(colon);
            host = host.substring(0, colon);
        }
        if (host.isEmpty()) {
            return fallback;
        }
        try {
            return scheme + "://" + IDN.toASCII(host) + port;
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Set-Cookie 响应头列表 → 保序 Cookie map(取分号前段,跳过无 = 的段)。 */
    static Map<String, String> parseCookies(List<String> setCookies) {
        Map<String, String> cookies = new LinkedHashMap<>();
        for (String header : setCookies == null ? List.<String>of() : setCookies) {
            String pair = StringUtils.substringBefore(header, ";").trim();
            int eq = pair.indexOf('=');
            if (eq > 0) {
                cookies.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return cookies;
    }

    /** Cookie map 拼 "k=v; k=v" 请求头(保序)。 */
    static String joinCookies(Map<String, String> cookies) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    /** 盘型代码是否数字(TVBox 挂载口径:非数字 = 磁力/未知盘,对候选池无意义)。 */
    static boolean isNumeric(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
