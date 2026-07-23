package cn.har01d.alist_tvbox.dto.tg;

import cn.har01d.alist_tvbox.dto.pansou.Link;
import cn.har01d.alist_tvbox.dto.pansou.SearchResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessageTest {

    // media.title path (tg-search) must clean the prefix the same way parseName does.
    private static Message fromMediaTitle(String title) {
        return new Message("0", "https://example.com/x", null, "", Instant.now(), List.of(), Map.of("title", title));
    }

    @Test
    void mediaTitle_exactPrefix_stripped() {
        assertEquals("【万米危机】1080P", fromMediaTitle("·✅✅✅【万米危机】1080P").getName());
    }

    @Test
    void mediaTitle_fe0fVariant_stripped() {
        // Telegram-scraped emoji usually carry U+FE0F variation selectors.
        assertEquals("【万米危机】1080P", fromMediaTitle("·✅️✅️✅️【万米危机】1080P").getName());
    }

    @Test
    void mediaTitle_alternateCheckmark_stripped() {
        // Different channels use different check emoji (here U+2714 instead of U+2705).
        assertEquals("【万米危机】", fromMediaTitle("·✔️✔️✔️【万米危机】").getName());
    }

    @Test
    void mediaTitle_katakanaMiddleDot_stripped() {
        // U+30FB middle dot instead of U+00B7.
        assertEquals("【万米危机】", fromMediaTitle("・✅✅✅【万米危机】").getName());
    }

    @Test
    void mediaTitle_descriptionMarker_truncated() {
        assertEquals("【万米危机】", fromMediaTitle("【万米危机】描述：这是描述内容").getName());
    }

    @Test
    void parseName_fe0fVariant_stripped() {
        // content-based path (channel scrape) must apply the same cleaning.
        Message m = new Message(1, "ch", "·✅️✅️✅️【万米危机】", null, null);
        assertEquals("【万米危机】", m.getName());
    }

    // pansou path (RemoteSearchService / TelegramService.parseMessage) must clean too.
    private static Message fromPansouTitle(String title) {
        SearchResult sr = new SearchResult();
        sr.setTitle(title);
        sr.setContent("备用");
        Link link = new Link();
        link.setUrl("https://pan.quark.cn/s/abc");
        return new Message(sr, link);
    }

    @Test
    void pansouTitle_exactPrefix_stripped() {
        assertEquals("【万米危机】1080P", fromPansouTitle("·✅✅✅【万米危机】1080P").getName());
    }

    @Test
    void pansouTitle_fe0fVariant_stripped() {
        assertEquals("【万米危机】", fromPansouTitle("·✅️✅️✅️【万米危机】").getName());
    }

    @Test
    void pansouTitle_blank_fallsBackToContent() {
        SearchResult sr = new SearchResult();
        sr.setTitle("");
        sr.setContent("名称：万米危机\nhttps://pan.quark.cn/s/abc");
        Link link = new Link();
        link.setUrl("https://pan.quark.cn/s/abc");
        assertEquals("万米危机", new Message(sr, link).getName());
    }
}
