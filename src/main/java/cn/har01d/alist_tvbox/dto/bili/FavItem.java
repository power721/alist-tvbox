package cn.har01d.alist_tvbox.dto.bili;

import lombok.Data;

@Data
public class FavItem {
    private String bvid;
    private String cover;
    private String intro;
    private String title;
    private int duration;
}
