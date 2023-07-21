package cn.har01d.alist_tvbox.dto.bili;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BiliBiliChannelItem {
    private long id;
    private String bvid;
    private String cover;
    private String name;
    private String duration;
    @JsonProperty("author_name")
    private String author;
    private List<BiliBiliChannelItem> items = new ArrayList<>();
}
