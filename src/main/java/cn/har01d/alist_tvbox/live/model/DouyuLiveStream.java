package cn.har01d.alist_tvbox.live.model;

import lombok.Data;

import java.util.List;

@Data
public class DouyuLiveStream {
    private List<CdnsWithName> cdnsWithName;
    private List<BitRate> multirates;

    @Data
    public static class CdnsWithName {
        private String name;
        private String cdn;
    }

    @Data
    public static class BitRate {
        private String name;
        private int rate;
    }
}
