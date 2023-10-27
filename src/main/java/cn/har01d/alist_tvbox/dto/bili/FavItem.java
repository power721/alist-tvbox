package cn.har01d.alist_tvbox.dto.bili;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class FavItem {
    private String bvid;
    private String cover;
    private String intro;
    private String title;
    private int duration;
    @JsonProperty("cnt_info")
    private CntInfo info = new CntInfo();

    @Data
    public static class CntInfo {
        private int play;
    }
}
