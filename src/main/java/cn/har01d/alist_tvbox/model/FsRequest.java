package cn.har01d.alist_tvbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class FsRequest {
    private int page = 1;
    private String path;
    private String password = "";
    @JsonProperty("per_page")
    private int size = 25;
    private boolean refresh;
}
