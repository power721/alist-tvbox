package cn.har01d.alist_tvbox.dto;

import lombok.Data;

/** 元数据单集详情(媒体详情页分集列表):标题/播出时间/简介/剧照,来自 provider 分集接口。 */
@Data
public class EpisodeInfo {
    private int number;
    private String title;
    /** 播出时间(epoch ms,北京时间 20:00 约定);null = 未公布 */
    private Long airTime;
    private String overview;
    private String still;
    /** 单集时长(分钟) */
    private Integer runtime;

    public EpisodeInfo() {
    }

    public EpisodeInfo(int number, String title, Long airTime) {
        this.number = number;
        this.title = title;
        this.airTime = airTime;
    }
}
