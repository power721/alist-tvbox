package com.github.kiulian.downloader.model;

import com.alibaba.fastjson.JSONObject;

// Video item of a list (playlist, or search result).
public class AbstractListVideoDetails extends AbstractVideoDetails {

    public AbstractListVideoDetails(JSONObject json) {
        super(json);
        if (json.containsKey("shortBylineText")) {
            author = Utils.parseRuns(json.getJSONObject("shortBylineText"));
        } else {
            author = Utils.parseRuns(json.getJSONObject("longBylineText"));
        }
        JSONObject jsonTitle = json.getJSONObject("title");
        if (jsonTitle.containsKey("simpleText")) {
            title = jsonTitle.getString("simpleText");
        } else {
            title = Utils.parseRuns(jsonTitle);
        }
    }
}
