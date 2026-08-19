package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 追剧订阅集数解析启发式:SxxEyy 优先,否则剥离技术标签后取最后一个合法数字(1..999)。
 */
class MediaSubscriptionCheckServiceTest {

    private final MediaSubscriptionCheckService service = new MediaSubscriptionCheckService(
            null, null, null, null, null, null, null, null, null, null, null, null, new AppProperties(), new ObjectMapper());

    @Test
    void seasonEpisodePattern() {
        assertEquals(5, service.parseEpisode("Show.S01E05.1080p.mkv", null));
        assertEquals(5, service.parseEpisode("Show.S01E05.1080p.mkv", 1));
        assertEquals(-1, service.parseEpisode("Show.S01E05.1080p.mkv", 2));
        assertEquals(12, service.parseEpisode("剧名.S02E12.2160p.WEB-DL.mkv", 2));
    }

    @Test
    void chineseEpisodeSuffix() {
        assertEquals(3, service.parseEpisode("边水往事.第03集.4K.mkv", null));
        assertEquals(12, service.parseEpisode("边水往事 第12集.mp4", null));
        assertEquals(20, service.parseEpisode("某剧.更新至20集", null));
    }

    @Test
    void trailingNumber() {
        assertEquals(7, service.parseEpisode("剧名.E07.mkv", null));
        assertEquals(9, service.parseEpisode("show.09.mkv", null));
    }

    @Test
    void techTagsAndYearsIgnored() {
        // 1080p/2160p/HEVC 等标签剥离,4 位年份超界 → 无集号
        assertEquals(-1, service.parseEpisode("Movie.2024.1080p.WEB-DL.HEVC.mkv", null));
        // 标签剥离后集号仍可识别
        assertEquals(6, service.parseEpisode("剧名.2024.1080p.第06集.mkv", null));
    }
}
