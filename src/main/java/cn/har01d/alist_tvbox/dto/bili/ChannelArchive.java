package cn.har01d.alist_tvbox.dto.bili;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class ChannelArchive {
    private String author_name;
    private String bvid;
    private String cover;
    private String duration;
    private String name;
    @JsonProperty("view_count")
    private String viewCount;
}
