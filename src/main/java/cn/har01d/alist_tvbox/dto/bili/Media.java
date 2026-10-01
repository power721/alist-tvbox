package cn.har01d.alist_tvbox.dto.bili;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class Media {
    private String id;
    private String baseUrl;
    private List<String> backupUrl;
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
