package cn.har01d.alist_tvbox.dto;

public record ParseRequest(String url, String title) {
    public ParseRequest(String url) {
        this(url, null);
    }
}
