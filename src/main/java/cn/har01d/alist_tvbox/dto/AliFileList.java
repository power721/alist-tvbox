package cn.har01d.alist_tvbox.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AliFileList {
    private List<AliFileItem> items = new ArrayList<>();
    @JsonProperty("next_marker")
    private String next;
}
