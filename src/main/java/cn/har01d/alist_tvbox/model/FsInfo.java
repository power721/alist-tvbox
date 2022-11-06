package cn.har01d.alist_tvbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class FsInfo {
    private String name;
    private int type;
    @JsonProperty("is_dir")
    private boolean isDir;
    private String modified;
    private long size;
    private String sign;
    private String thumb;
}
