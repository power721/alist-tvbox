package cn.har01d.alist_tvbox.live.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveUrlParserTest {
    @ParameterizedTest
    @CsvSource({
            "https://www.huya.com/11342412, huya, 11342412",
            "https://m.huya.com/11342412, huya, 11342412",
            "https://www.huya.com/kaerlol, huya, kaerlol",
            "https://www.douyu.com/288016, douyu, 288016",
            "https://live.bilibili.com/6, bili, 6",
            "https://cc.163.com/362433, cc, 362433",
            "https://cc.163.com/user/362433/, cc, 362433",
            "https://live.kuaishou.com/u/3x9r7wqbqvi6cks, ks, 3x9r7wqbqvi6cks",
            "https://live.kuaishou.cn/u/3x9r7wqbqvi6cks, ks, 3x9r7wqbqvi6cks",
            "https://live.douyin.com/745964462470, douyin, 745964462470",
            "https://www.twitch.tv/riotgames, twitch, riotgames",
            "https://m.twitch.tv/riotgames/videos, twitch, riotgames",
            "https://play.sooplive.com/abbbbbb, soop, abbbbbb",
            "https://www.afreecatv.com/abbbbbb, soop, abbbbbb",
            "live.bilibili.com/6, bili, 6",
            "https://www.huya.com/11342412?from=share, huya, 11342412",
    })
    void parseRecognizesOfficialRoomUrls(String url, String platform, String roomId) {
        String[] result = LiveUrlParser.parse(url);

        assertNotNull(result);
        assertEquals(platform, result[0]);
        assertEquals(roomId, result[1]);
    }

    @Test
    void parseRejectsUnknownOrInvalidInput() {
        assertNull(LiveUrlParser.parse(null));
        assertNull(LiveUrlParser.parse("  "));
        assertNull(LiveUrlParser.parse("https://www.baidu.com/s?wd=1"));
        assertNull(LiveUrlParser.parse("https://www.bilibili.com/video/BV1xx411c7mD")); // 视频页不是直播间
        assertNull(LiveUrlParser.parse("https://www.huya.com/")); // 首页没有房间号
        assertNull(LiveUrlParser.parse("https://live.kuaishou.com/u")); // /u 后没有主播 id
        assertNull(LiveUrlParser.parse("https://cc.163.com/user")); // /user 后没有主播 id
        assertNull(LiveUrlParser.parse("https://live.kuaishou.com/profile/abc")); // 非房间页
        assertNull(LiveUrlParser.parse("https://live.douyin.com/user/MS4wLjABAAAA")); // 主播主页不是房间页
        assertNull(LiveUrlParser.parse("https://live.bilibili.com/6?extra=a b")); // 非法字符
        assertNull(LiveUrlParser.parse("huya.com/" + "a".repeat(65))); // 超长
    }

    @Test
    void extractUrlFromShareText() {
        assertEquals("https://www.huya.com/11342412",
                LiveUrlParser.extractUrl("【主播】正在直播,快来看 https://www.huya.com/11342412 复制打开"));
        assertEquals("https://b23.tv/abc123", LiveUrlParser.extractUrl("https://b23.tv/abc123。快来看"));
        assertEquals("https://live.douyin.com/745964462470",
                LiveUrlParser.extractUrl("看直播 https://live.douyin.com/745964462470,快看"));
        assertEquals("live.bilibili.com/6", LiveUrlParser.extractUrl("快来 live.bilibili.com/6 一起看"));
        assertNull(LiveUrlParser.extractUrl("今天天气不错"));
        assertNull(LiveUrlParser.extractUrl(null));
    }

    @Test
    void buildRoomUrlMatchesParseRules() {
        assertEquals("https://www.huya.com/11342412", LiveUrlParser.buildRoomUrl("huya", "11342412"));
        assertEquals("https://www.douyu.com/288016", LiveUrlParser.buildRoomUrl("douyu", "288016"));
        assertEquals("https://live.bilibili.com/6", LiveUrlParser.buildRoomUrl("bili", "6"));
        assertEquals("https://cc.163.com/user/362433/", LiveUrlParser.buildRoomUrl("cc", "362433"));
        assertEquals("https://live.kuaishou.com/u/3x9r7wqbqvi6cks", LiveUrlParser.buildRoomUrl("ks", "3x9r7wqbqvi6cks"));
        assertEquals("https://live.douyin.com/745964462470", LiveUrlParser.buildRoomUrl("douyin", "745964462470"));
        assertEquals("https://www.twitch.tv/riotgames", LiveUrlParser.buildRoomUrl("twitch", "riotgames"));
        assertEquals("https://play.sooplive.com/abbbbbb", LiveUrlParser.buildRoomUrl("soop", "abbbbbb"));
        assertNull(LiveUrlParser.buildRoomUrl("unknown", "123"));
        assertNull(LiveUrlParser.buildRoomUrl(null, "123"));
        assertNull(LiveUrlParser.buildRoomUrl("huya", null));
        assertNull(LiveUrlParser.buildRoomUrl("huya", "6?x=1")); // 非法字符不得拼入外部跳转
    }

    @Test
    void detectShareLinks() {
        assertTrue(LiveUrlParser.isShareLink("https://b23.tv/abc123"));
        assertTrue(LiveUrlParser.isShareLink("https://v.douyin.com/iFRv8x6/"));
        assertTrue(LiveUrlParser.isShareLink("https://v.kuaishou.com/2xYzAbC"));
        assertTrue(LiveUrlParser.isShareLink("https://www.iesdouyin.com/share/liveweb/index?room_id=732061698"));
        assertFalse(LiveUrlParser.isShareLink("https://www.huya.com/11342412"));
        assertFalse(LiveUrlParser.isShareLink("https://www.baidu.com"));
        assertFalse(LiveUrlParser.isShareLink(null));
    }
}
