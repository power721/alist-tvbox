package cn.har01d.alist_tvbox.dto.pansou;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class Link {
    private String type;
    private String url;
    private String password;
    @JsonProperty("work_title")
    private String workTitle;
}
