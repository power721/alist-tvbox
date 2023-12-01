package cn.har01d.alist_tvbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class VideoPreview {
    @JsonProperty("video_preview_play_info")
    private VideoPreviewPlayInfo playInfo;
}
