package cn.har01d.alist_tvbox.telegram;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** callback data 编解码:合法集合解析、带参/无参、畸形输入容错(返回 null 不炸会话)。 */
class TelegramCallbackDataTest {

    @Test
    void parseActionsWithArg() {
        TelegramCallbackData.Callback cb = TelegramCallbackData.parse("sub:12");
        assertNotNull(cb);
        assertEquals(TelegramCallbackData.SUB, cb.action());
        assertEquals(12, cb.arg());

        cb = TelegramCallbackData.parse("pickp:3");
        assertNotNull(cb);
        assertEquals(TelegramCallbackData.RESULT_PAGE, cb.action());
        assertEquals(3, cb.arg());
    }

    @Test
    void parseActionsWithoutArg() {
        for (String data : new String[]{"home", "search", "cancel", "inbox", "cal", "pd"}) {
            TelegramCallbackData.Callback cb = TelegramCallbackData.parse(data);
            assertNotNull(cb);
            assertEquals(data, cb.action());
            assertEquals(0, cb.arg());
            assertNull(cb.arg2());
        }
        // 无参 action 带参也归一到 0,野按钮不炸
        assertEquals(0, TelegramCallbackData.parse("cal:1").arg());
    }

    @Test
    void argRequiredForParameterizedActions() {
        assertNull(TelegramCallbackData.parse("sub"));
        assertNull(TelegramCallbackData.parse("subs:"));
    }

    @Test
    void malformedDataReturnsNull() {
        assertNull(TelegramCallbackData.parse(null));
        assertNull(TelegramCallbackData.parse(""));
        assertNull(TelegramCallbackData.parse("sub:abc"));
        assertNull(TelegramCallbackData.parse("unknown:1"));
        assertNull(TelegramCallbackData.parse("sub:1:2:3"));
        assertNull(TelegramCallbackData.parse("x".repeat(65)));
    }

    @Test
    void ofBuildsData() {
        assertEquals("subdelc:42", TelegramCallbackData.of(TelegramCallbackData.SUB_DELETE_CONFIRM, 42));
        assertEquals("pdadd:3:5", TelegramCallbackData.of(TelegramCallbackData.PIAN_DAN_ADD, 3, 5));
    }

    @Test
    void parsePianDanActions() {
        TelegramCallbackData.Callback cb = TelegramCallbackData.parse("pd");
        assertNotNull(cb);
        assertEquals(TelegramCallbackData.PIAN_DAN, cb.action());
        assertEquals(0, cb.arg());
        assertNull(cb.arg2());

        cb = TelegramCallbackData.parse("pdadd:3:5");
        assertNotNull(cb);
        assertEquals(TelegramCallbackData.PIAN_DAN_ADD, cb.action());
        assertEquals(3, cb.arg());
        assertEquals(5, cb.arg2());

        // 不带季号的加入追剧:第二参数缺省
        cb = TelegramCallbackData.parse("pdadd:3");
        assertNotNull(cb);
        assertNull(cb.arg2());

        assertNull(TelegramCallbackData.parse("pdadd"));
        assertNull(TelegramCallbackData.parse("pdadd:3:x"));
    }
}
