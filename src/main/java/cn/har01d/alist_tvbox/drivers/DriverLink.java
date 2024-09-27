package cn.har01d.alist_tvbox.drivers;

import java.util.Map;

public record DriverLink(String url, Map<String, String> headers) {
}
