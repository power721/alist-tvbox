package cn.har01d.alist_tvbox.dto;

public record ParseRequest(String url, String title, String keyword) {
    public ParseRequest(String url) {
        this(url, null, null);
    }

    public ParseRequest(String url, String title) {
        this(url, title, null);
    }
}
