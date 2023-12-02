package cn.har01d.alist_tvbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class VideoPreviewPlayInfo {
    @JsonProperty("live_transcoding_task_list")
    private List<LiveTranscoding> videos = new ArrayList<>();

    @JsonProperty("live_transcoding_subtitle_task_list")
    private List<LiveTranscodingSubtitle> subtitles = new ArrayList<>();
}
