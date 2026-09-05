package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResourceRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 粘贴链接解析的出网白名单(SSRF 防护):链接来自 USER 输入,平台正则只做子串匹配不锚定 host,
 * 短链展开/页面取题必须先过 https + 平台官方域白名单,否则 http://127.0.0.1/b23.tv、
 * https://内网地址/#youku.com/show/id_x 之类能让服务端代发请求探测内网。
 */
class MediaSubscriptionResolveLinkTest {

    private final MediaSubscriptionService service = new MediaSubscriptionService(
            Mockito.mock(MediaSubscriptionRepository.class),
            Mockito.mock(MediaSubscriptionResourceRepository.class),
            null, null, null, null, null, null, null, null, null, null, null, null,
            new AppProperties(), new ObjectMapper(), null, null, null);

    // ---------- 白名单判定 ----------

    @Test
    void shortLinkRequiresExactB23HostOverHttps() {
        assertTrue(MediaSubscriptionService.isShortLink("https://b23.tv/abc123"));
        assertTrue(MediaSubscriptionService.isShortLink("https://www.b23.tv/abc123"));
        // 评审 P1 原始形态:contains("b23.tv") 旧判定会代发这条请求
        assertFalse(MediaSubscriptionService.isShortLink("http://127.0.0.1/b23.tv"));
        assertFalse(MediaSubscriptionService.isShortLink("https://127.0.0.1/b23.tv"));
        assertFalse(MediaSubscriptionService.isShortLink("https://b23.tv.evil.com/abc"));
        assertFalse(MediaSubscriptionService.isShortLink("https://evil.com/?u=b23.tv/abc"));
        assertFalse(MediaSubscriptionService.isShortLink("b23.tv/abc")); // 无 scheme
        assertFalse(MediaSubscriptionService.isShortLink("not a url"));
    }

    @Test
    void pageFetchAllowsOnlyOfficialPlatformHttpsHosts() {
        assertTrue(MediaSubscriptionService.isAllowedMetaLinkUrl("https://www.bilibili.com/bangumi/play/ss123"));
        assertTrue(MediaSubscriptionService.isAllowedMetaLinkUrl("https://m.youku.com/show/id_abc"));
        assertTrue(MediaSubscriptionService.isAllowedMetaLinkUrl("https://www.iqiyi.com/a_abc.html"));
        assertTrue(MediaSubscriptionService.isAllowedMetaLinkUrl("https://b23.tv/abc"));
        // host 不在白名单(内网 IP / 内网域名 / 白名单域做后缀伪装 / 平台特征只出现在参数或锚点里)
        assertFalse(MediaSubscriptionService.isAllowedMetaLinkUrl("http://192.168.50.10/iqiyi.com/a_abc.html"));
        assertFalse(MediaSubscriptionService.isAllowedMetaLinkUrl("https://internal.corp/#bilibili.com/bangumi/play/ss1"));
        assertFalse(MediaSubscriptionService.isAllowedMetaLinkUrl("https://youku.com.evil.com/show/id_abc"));
        assertFalse(MediaSubscriptionService.isAllowedMetaLinkUrl("https://evil.com/?x=bilibili.com/bangumi/play/ss1"));
        assertFalse(MediaSubscriptionService.isAllowedMetaLinkUrl("ftp://www.youku.com/show/id_abc"));
    }

    // ---------- 端到端(全部离线:被拒链路在出网前抛错,正常链路不触网) ----------

    @Test
    void doubanSubjectLinkResolvesOffline() {
        // metadataService 未注入,名称补全失败被吞,不影响 provider/id 装配
        Map<String, Object> result = service.resolveMetaLink("https://movie.douban.com/subject/26794407/");
        assertEquals("douban", result.get("provider"));
        assertEquals("26794407", result.get("id"));
        assertEquals(26794407, result.get("doubanId"));
    }

    @Test
    void localhostUrlCarryingB23MarkerIsRejectedBeforeAnyRequest() {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> service.resolveMetaLink("http://127.0.0.1/b23.tv"));
        assertTrue(e.getMessage().contains("无法识别"));
    }

    @Test
    void youkuMarkerOnAttackerHostIsRejected() {
        // 正则命中串里的 youku.com 特征 → 页面取题入口白名单拒绝,不发请求
        assertThrows(BadRequestException.class,
                () -> service.resolveMetaLink("https://internal.corp/#youku.com/show/id_abc"));
    }

    @Test
    void iqiyiMarkerOnPlainHttpLanAddressIsRejected() {
        assertThrows(BadRequestException.class,
                () -> service.resolveMetaLink("http://192.168.50.10/iqiyi.com/a_abc.html"));
    }
}
