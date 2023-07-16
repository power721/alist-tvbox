package cn.har01d.alist_tvbox.dto.bili;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class Media {
    private String id;
    private String baseUrl;
    private String bandwidth;
    private String mimeType;
    private String codecs;
    private String width;
    private String height;
    private String frameRate;
    private String sar;
    private String startWithSap;
    @JsonProperty("segment_base")
    private Segment segmentBase;
    private String codecid;
}
