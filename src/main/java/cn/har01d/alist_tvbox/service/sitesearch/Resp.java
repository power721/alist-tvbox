package cn.har01d.alist_tvbox.service.sitesearch;

import java.util.List;

/**
 * HTTP 原语(站点搜索源公共):状态码 + Set-Cookie 列表 + 响应体;
 * 服务覆写 {@code http()} 供单测打桩。
 */
record Resp(int code, List<String> setCookies, String body) {
}
