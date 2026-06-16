package cn.har01d.alist_tvbox.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class SiteDto {
    private String name;
    private String url;
    private String password;
    private String token;
    private String indexFile;
    private String folder;
    private boolean searchable;
    private boolean disabled;
    private boolean xiaoya;
    @JsonProperty("order")
    private int sortOrder;
    @JsonProperty("version")
    private Integer storageVersion;
}
