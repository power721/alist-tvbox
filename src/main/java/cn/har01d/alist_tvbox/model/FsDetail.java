package cn.har01d.alist_tvbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class FsDetail {
    private String name;
    private int type;
    @JsonProperty("is_dir")
    private boolean isDir;
    private String modified;
    private long size;
    private String sign;
    private String thumb;
    private String provider;
    private String readme;
    @JsonProperty("raw_url")
    private String rawUrl;
}
