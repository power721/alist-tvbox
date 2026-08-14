package cn.har01d.alist_tvbox.dto.playback;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * PULL 响应:游标分页下发变更。删先于插(client 侧先应用墓碑再 upsert)。
 * 字段名兼容 webhtv 风格(nextSince/items/deleted)。
 */
@Data
public class PlaybackSyncPage {
    private String nextSince;
    private List<PlaybackSyncInput> items = new ArrayList<>();
    private List<PlaybackDeleteInput> deleted = new ArrayList<>();
}
