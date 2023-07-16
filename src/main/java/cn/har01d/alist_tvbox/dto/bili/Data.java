package cn.har01d.alist_tvbox.dto.bili;

import java.util.ArrayList;
import java.util.List;

@lombok.Data
public class Data {
    private Boolean isLogin;
    private Integer vipType;
    private String qrcodeKey;
    private String url;
    private String aid;
    private String cid;
    private String title;
    private String tname;
    private String pic;
    private Long duration;
    private String desc;
    private List<String> acceptDescription;
    private List<Integer> acceptQuality;
    private List<Page> pages;
    private Dash dash;
    private List<BiliBiliPlay.DUrl> durl = new ArrayList<>();
}
