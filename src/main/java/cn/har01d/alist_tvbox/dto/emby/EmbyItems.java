package cn.har01d.alist_tvbox.dto.emby;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class EmbyItems {
    @JsonProperty("Items")
    private List<EmbyItem> items;

    @JsonProperty("TotalRecordCount")
    private int total;
}
