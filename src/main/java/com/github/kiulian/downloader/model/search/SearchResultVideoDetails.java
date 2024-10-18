package com.github.kiulian.downloader.model.search;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.github.kiulian.downloader.model.AbstractListVideoDetails;
import com.github.kiulian.downloader.model.Utils;

import java.util.ArrayList;
import java.util.List;

public class SearchResultVideoDetails extends AbstractListVideoDetails implements SearchResultItem {

    private final boolean isMovie;
    private String description;
    private String viewCountText;
    private long viewCount;
    // Scheduled diffusion (seconds time stamp)
    private long startTime;
    // Subtitled, CC, ...
    private List<String> badges;
    // Animated images
    private List<String> richThumbnails;

    public SearchResultVideoDetails(JSONObject json, boolean isMovie) {
        super(json);
        this.isMovie = isMovie;
        if (json.containsKey("lengthText")) {
            JSONObject length = json.getJSONObject("lengthText");
            String lengthText;
            if (length.containsKey("runs")) {
                lengthText = Utils.parseRuns(length);
            } else {
                lengthText = length.getString("simpleText");
            }
            lengthSeconds = Utils.parseLengthSeconds(lengthText);
        }
        if (isMovie) {
            description = Utils.parseRuns(json.getJSONObject("descriptionSnippet"));
        } else if (json.containsKey("detailedMetadataSnippets")) {
            description = Utils.parseRuns(json.getJSONArray("detailedMetadataSnippets")
                    .getJSONObject(0)
                    .getJSONObject("snippetText"));
        }
        if (json.containsKey("upcomingEventData")) {
            String startTimeText = json.getJSONObject("upcomingEventData").getString("startTime");
            startTime = Long.parseLong(startTimeText);
            viewCount = -1;
        } else if (json.containsKey("viewCountText")) {
            JSONObject jsonCount = json.getJSONObject("viewCountText");
            if (jsonCount.containsKey("simpleText")) {
                viewCountText = jsonCount.getString("simpleText");
                viewCount = Utils.parseViewCount(viewCountText);
            } else if (jsonCount.containsKey("runs")) {
                viewCountText = Utils.parseRuns(jsonCount);
                try {
                    viewCount = Utils.parseViewCount(viewCountText);
                } catch (Exception e) {
                    viewCount = -1;
                }
            }
        }
        if (json.containsKey("badges")) {
            JSONArray jsonBadges = json.getJSONArray("badges");
            badges = new ArrayList<>(jsonBadges.size());
            for (int i = 0; i < jsonBadges.size(); i++) {
                JSONObject jsonBadge = jsonBadges.getJSONObject(i);
                if (jsonBadge.containsKey("metadataBadgeRenderer")) {
                    badges.add(jsonBadge.getJSONObject("metadataBadgeRenderer").getString("label"));
                }
            }
        }
        if (json.containsKey("richThumbnail")) {
            try {
                JSONArray jsonThumbs = json.getJSONObject("richThumbnail")
                        .getJSONObject("movingThumbnailRenderer")
                        .getJSONObject("movingThumbnailDetails")
                        .getJSONArray("thumbnails");
                richThumbnails = new ArrayList<>(jsonThumbs.size());
                for (int i = 0; i < jsonThumbs.size(); i++) {
                    richThumbnails.add(jsonThumbs.getJSONObject(i).getString("url"));
                }
            } catch (NullPointerException ignored) {}
        } else if (json.containsKey("thumbnail")) {
            try {
                JSONArray jsonThumbs = json.getJSONObject("thumbnail")
                        .getJSONArray("thumbnails");
                richThumbnails = new ArrayList<>(jsonThumbs.size());
                for (int i = 0; i < jsonThumbs.size(); i++) {
                    richThumbnails.add(jsonThumbs.getJSONObject(i).getString("url"));
                }
            } catch (NullPointerException ignored) {}
        }
    }

    @Override
    public SearchResultItemType type() {
        return SearchResultItemType.VIDEO;
    }

    @Override
    public SearchResultVideoDetails asVideo() {
        return this;
    }

    public boolean isMovie() {
        return isMovie;
    }

    public boolean isLive() {
        return viewCount == -1;
    }

    public String viewCountText() {
        return viewCountText;
    }

    public long viewCount() {
        return viewCount;
    }

    public long startTime() {
        return startTime;
    }

    public List<String> badges() {
        return badges;
    }

    public List<String> richThumbnails() {
        return richThumbnails;
    }

    public String description() {
        return description;
    }
}
