package cn.har01d.alist_tvbox.dto;

import lombok.Data;

/** 单集播出时间(season 日程用)。episode=0 表示集数未知(如腾讯官方分集列表只有日期)。 */
@Data
public class EpisodeAirDate {
    private int episode;
    /** 播出时间(epoch ms,北京时间 20:00 约定) */
    private long airTime;

    public EpisodeAirDate() {
    }

    public EpisodeAirDate(int episode, long airTime) {
        this.episode = episode;
        this.airTime = airTime;
    }
}
