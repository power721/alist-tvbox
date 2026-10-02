package cn.har01d.alist_tvbox.service.sitesearch;

import java.util.List;
import java.util.Map;

/**
 * HTTP 原语(站点搜索源公共):状态码 + Set-Cookie 列表 + 响应体 + 通用响应头;
 * 服务覆写 {@code http()} 供单测打桩。
 */
record Resp(int code, List<String> setCookies, String body, Map<String, List<String>> headers) {
    Resp(int code, List<String> setCookies, String body) {
        this(code, setCookies, body, Map.of());
    }

    /** 大小写无关取首个响应头值(x-refreshed-token 等自定义头),缺头回落空串。 */
    String firstHeader(String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                return entry.getValue().getFirst();
            }
        }
        return "";
    }
}
