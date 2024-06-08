package com.github.kiulian.downloader.model.search;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class SearchResultShelf implements SearchResultItem {

    private final String title;
    private final List<SearchResultVideoDetails> videos;

    public SearchResultShelf(JSONObject json) {
        title = json.getJSONObject("title").getString("simpleText");
        JSONObject jsonContent = json.getJSONObject("content");
        
        // verticalListRenderer / horizontalMovieListRenderer
        String contentRendererKey = jsonContent.keySet().iterator().next();
        boolean isMovieShelf = contentRendererKey.contains("Movie");
        JSONArray jsonItems = jsonContent.getJSONObject(contentRendererKey).getJSONArray("items");
        videos = new ArrayList<>();
        for (int i = 0; i < jsonItems.size(); i++) {
            JSONObject jsonItem = jsonItems.getJSONObject(i);
            String itemRendererKey = jsonItem.keySet().iterator().next();
            try {
                videos.add(new SearchResultVideoDetails(jsonItem.getJSONObject(itemRendererKey), isMovieShelf));
            } catch (Exception e) {
                // ignore
            }
        }
    }

    @Override
    public SearchResultItemType type() {
        return SearchResultItemType.SHELF;
    }

    @Override
    public SearchResultShelf asShelf() {
        return this;
    }

    @Override
    public String title() {
        return title;
    }

    public List<SearchResultVideoDetails> videos() {
        return videos;
    }

}
