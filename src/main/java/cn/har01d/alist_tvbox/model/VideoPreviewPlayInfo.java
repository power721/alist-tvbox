package cn.har01d.alist_tvbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class VideoPreviewPlayInfo {
    @JsonProperty("live_transcoding_task_list")
    private List<LiveTranscoding> list;
}
