package cn.har01d.alist_tvbox.live.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 抖音 ao 纯音频档过滤判定(pure_live 56cd4d97 同口径):键名归一化命中 ao/audio/audioonly,
 * 或非空 URL 全部带 only_audio=1/true;混有正常视频 URL 的键不判,防误杀。
 */
class DouyinServiceTest {
    @Test
    void audioOnlyKeys() {
        assertTrue(DouyinService.isAudioOnlyVariant("ao", "https://pull.douyin.com/live.flv"));
        assertTrue(DouyinService.isAudioOnlyVariant("AO", ""));
        assertTrue(DouyinService.isAudioOnlyVariant("_audio_", ""));
        assertTrue(DouyinService.isAudioOnlyVariant("AudioOnly", ""));
    }

    @Test
    void videoKeysWithOnlyAudioParam() {
        assertTrue(DouyinService.isAudioOnlyVariant("FULL_HD1",
                "https://a.douyin.com/hd.flv?only_audio=1", "https://a.douyin.com/hd.m3u8?only_audio=true"));
        // 非空 URL 里只要有一个不是 only_audio,就不判音频(保守第二信源防误杀)
        assertFalse(DouyinService.isAudioOnlyVariant("FULL_HD1",
                "https://a.douyin.com/hd.flv?only_audio=1", "https://a.douyin.com/hd.m3u8"));
        assertFalse(DouyinService.isAudioOnlyVariant("_origin",
                "https://a.douyin.com/origin.flv", ""));
    }

    @Test
    void plainVideoKeysAndEmptyInput() {
        assertFalse(DouyinService.isAudioOnlyVariant("FULL_HD1", "https://a.douyin.com/hd.flv"));
        assertFalse(DouyinService.isAudioOnlyVariant("ORIGIN"));
        assertFalse(DouyinService.isAudioOnlyVariant(""));
        assertFalse(DouyinService.isAudioOnlyVariant(null));
    }

    /**
     * 直播判定(pure_live 4-1 同口径):room.status 优先(2=直播中),缺失时看信封
     * room_status(0=直播中),两者都没有按未开播。
     */
    @Test
    void liveStatusFallsBackToEnvelopeRoomStatus() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertTrue(DouyinService.douyinIsLive(
                mapper.createObjectNode().put("status", 2), mapper.createObjectNode().put("room_status", 1)));
        assertFalse(DouyinService.douyinIsLive(
                mapper.createObjectNode().put("status", 4), mapper.createObjectNode().put("room_status", 0)));
        // status 缺失:信封 room_status=0 即直播中(pure_live 4.x:enter 接口的 room 可能没有 status)
        assertTrue(DouyinService.douyinIsLive(
                mapper.createObjectNode(), mapper.createObjectNode().put("room_status", 0)));
        assertFalse(DouyinService.douyinIsLive(
                mapper.createObjectNode(), mapper.createObjectNode().put("room_status", 1)));
        // 两者都没有:按未开播
        assertFalse(DouyinService.douyinIsLive(mapper.createObjectNode(), mapper.createObjectNode()));
    }

    /**
     * 详情分区(pure_live M4.D 同口径):优先游戏名 game_tag_name,否则取
     * partition_road_map 最具体一层(子分区再到分区)。
     */
    @Test
    void areaPrefersGameNameThenSubPartition() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var room = mapper.createObjectNode();
        room.putObject("game_data").putObject("game_tag_info").put("game_tag_name", "王者荣耀");
        var roadMap = mapper.createObjectNode();
        roadMap.putObject("sub_partition").putObject("partition").put("title", "游戏");
        assertEquals("王者荣耀", DouyinService.douyinArea(room, roadMap));

        roadMap = mapper.createObjectNode();
        roadMap.putObject("sub_partition").putObject("partition").put("title", "英雄联盟");
        assertEquals("英雄联盟", DouyinService.douyinArea(mapper.createObjectNode(), roadMap));

        roadMap = mapper.createObjectNode();
        roadMap.putObject("partition").put("title", "网游");
        assertEquals("网游", DouyinService.douyinArea(mapper.createObjectNode(), roadMap));

        assertEquals("", DouyinService.douyinArea(mapper.createObjectNode(), mapper.createObjectNode()));
    }
}
