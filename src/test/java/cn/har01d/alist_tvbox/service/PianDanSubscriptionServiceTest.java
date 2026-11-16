package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * pianDanEntry 载荷解析:重点是 T4 壳子(FongMi 系 type4)双重编码载荷的补解兼容
 * (T3 spider 裸拼 GET 契约下服务端收到的已是解码态,须不受影响)。
 */
@ExtendWith(MockitoExtension.class)
class PianDanSubscriptionServiceTest {
    @Mock
    private MediaSubscriptionService mediaSubscriptionService;
    @Mock
    private MediaSubscriptionCheckService checkService;
    @Mock
    private PianDanService pianDanService;

    private PianDanSubscriptionService service() {
        return new PianDanSubscriptionService(mediaSubscriptionService, checkService, pianDanService);
    }

    @Test
    void parsesRawPayloadWithLiteralSeparator() {
        var entry = service().pianDanEntry("db:37806882|我的有罪之人");

        assertEquals("db:37806882", entry.vodId());
        assertEquals("我的有罪之人", entry.name());
        assertEquals(37806882, entry.doubanId());
    }

    @Test
    void decodesDoubleEncodedT4Payload() {
        // T4 客户端 addQueryParameter 对预编码 id 再编码一次,服务端解一层后载荷仍为编码态
        var entry = service().pianDanEntry("db%3A37806882%7C%E6%88%91%E7%9A%84%E6%9C%89%E7%BD%AA%E4%B9%8B%E4%BA%BA");

        assertEquals("db:37806882", entry.vodId());
        assertEquals("我的有罪之人", entry.name());
        assertEquals(37806882, entry.doubanId());
    }

    @Test
    void decodesDoubleEncodedT4PayloadWithSeason() {
        var entry = service().pianDanEntry("tmdb%3Atv%3A42%7C%E6%B5%8B%E8%AF%95%E5%89%A7%7C2");

        assertEquals("tmdb:tv:42", entry.vodId());
        assertEquals("测试剧", entry.name());
        assertEquals(2, entry.season());
    }

    @Test
    void keepsPayloadWithLiteralSeparatorUndecoded() {
        // T3 路径载荷必含字面 |,标题里形似 %7C 的文本不得触发补解
        var entry = service().pianDanEntry("db:1|A%7CB");

        assertEquals("db:1", entry.vodId());
        assertEquals("A%7CB", entry.name());
    }

    @Test
    void malformedEscapeWithoutSeparatorStillRejected() {
        // 无字面 | 且含 %7C 但解码失败(孤立 % 序列):保持原样走原逻辑 → 400
        assertThrows(BadRequestException.class,
                () -> service().pianDanEntry("db%3A1%7C10%的喜欢"));
    }
}
