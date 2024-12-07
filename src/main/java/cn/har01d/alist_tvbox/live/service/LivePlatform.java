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
}
