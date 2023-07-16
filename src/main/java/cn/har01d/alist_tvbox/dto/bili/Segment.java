package cn.har01d.alist_tvbox.dto.bili;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class Segment {
    private String initialization;
    @JsonProperty("index_range")
    private String indexRange;
}
