package cn.har01d.alist_tvbox.live.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LiveShortLinkResolverTest {
    @Test
    void douyinRoomIdFromReflowPathAndQuery() {
        assertEquals("745964462470",
                LiveShortLinkResolver.douyinRoomId("https://webcast.amemv.com/webcast/room/reflow/745964462470"));
        assertEquals("732061698",
                LiveShortLinkResolver.douyinRoomId("https://www.iesdouyin.com/share/liveweb/index?live_id=1&room_id=732061698&type_id=0"));
    }

    @Test
    void douyinRoomIdRejectsNonSharePages() {
        assertNull(LiveShortLinkResolver.douyinRoomId("https://live.douyin.com/6")); // 已是 webRid 形式,parse 先行命中
        assertNull(LiveShortLinkResolver.douyinRoomId("https://live.douyin.com/user/MS4wLjABAAAA"));
        assertNull(LiveShortLinkResolver.douyinRoomId("https://www.baidu.com/?room_id=732061698")); // 非抖音系域名
        assertNull(LiveShortLinkResolver.douyinRoomId("https://www.iesdouyin.com/share/video/1")); // 无 room_id
        assertNull(LiveShortLinkResolver.douyinRoomId(null));
    }
}
