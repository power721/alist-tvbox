package cn.har01d.alist_tvbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class LiveTranscoding {
    @JsonProperty("template_id")
    private String id;
    private String status;
    private String url;
}
