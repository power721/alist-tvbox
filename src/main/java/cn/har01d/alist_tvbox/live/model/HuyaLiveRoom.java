package cn.har01d.alist_tvbox.live.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class HuyaLiveRoom {
    @Data
    public static class BitRateInfoList {
        private List<LiveBitRateInfo> value;
    }

    @Data
    public static class LiveBitRateInfo {
        @JsonProperty("sDisplayName")
        private String sDisplayName;
        @JsonProperty("iBitRate")
        private int iBitRate;
    }
}
