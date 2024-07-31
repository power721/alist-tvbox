package cn.har01d.alist_tvbox.dto.bili;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BiliBiliSeasonInfoList {
    private String actors;
    private String cover;
    private String evaluate;
    private String title;
    private List<BiliBiliSeasonInfo> list = new ArrayList<>();
    private List<BiliBiliSeasonInfo> episodes = new ArrayList<>();
    private int total;
    private List<Section> section;

    @Data
    public static class Section {
        private String title;
        private List<BiliBiliSeasonInfo> episodes = new ArrayList<>();
    }
}
