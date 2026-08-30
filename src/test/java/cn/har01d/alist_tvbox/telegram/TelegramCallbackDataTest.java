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
        for (String data : new String[]{"home", "search", "cancel", "inbox"}) {
            TelegramCallbackData.Callback cb = TelegramCallbackData.parse(data);
            assertNotNull(cb);
            assertEquals(data, cb.action());
            assertEquals(0, cb.arg());
        }
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
    }
}
