package cn.har01d.alist_tvbox.live.service;

import org.junit.jupiter.api.Test;

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
}
