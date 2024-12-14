package cn.har01d.alist_tvbox.live.service;

import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieList;

import java.io.IOException;

public interface LivePlatform {
    String getType();

    String getName();

    MovieList home() throws IOException;

    CategoryList category() throws IOException;

    MovieList list(String id, String sort, Integer pg) throws IOException;

    MovieList search(String wd) throws IOException;

    MovieList detail(String tid) throws IOException;

    default String playCount(int view) {
        if (view >= 10000) {
            return (view / 10000) + "万";
        } else if (view >= 1000) {
            return (view / 1000) + "千";
        } else {
            return view + "";
        }
    }

    default String playCount(String count) {
        if (count == null || count.isBlank()) {
            return null;
        }
        int view = Integer.parseInt(count);
        return playCount(view);
    }
}
