package com.github.kiulian.downloader.model;

public class BrowseRequest {
   private final String cookie;

    public BrowseRequest(String cookie) {
        this.cookie = cookie;
    }

    public String getCookie() {
        return cookie;
    }
}
