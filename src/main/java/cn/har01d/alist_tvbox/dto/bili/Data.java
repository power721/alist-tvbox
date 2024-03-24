package cn.har01d.alist_tvbox.dto.bili;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

@lombok.Data
public class Data {
    private int quality;
    private String qrcodeKey;
    private String url;
    private String aid;
    private String cid;
    private String title;
    private String tname;
    private String pic;
    private String format;
    private Long duration;
    private String desc;
    @JsonProperty("accept_description")
    private List<String> acceptDescription = new ArrayList<>();
    @JsonProperty("accept_quality")
    private List<Integer> acceptQuality = new ArrayList<>();
    private List<Page> pages;
    private Dash dash;
    private List<BiliBiliPlay.DUrl> durl = new ArrayList<>();
}
