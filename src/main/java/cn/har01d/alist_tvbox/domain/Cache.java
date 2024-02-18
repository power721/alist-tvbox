package cn.har01d.alist_tvbox.domain;

public record Cache<T>(String id, T data, long expiration) {
    public boolean isValid() {
        return System.currentTimeMillis() < expiration;
    }
}
