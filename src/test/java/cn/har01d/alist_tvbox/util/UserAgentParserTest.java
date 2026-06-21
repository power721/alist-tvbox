package cn.har01d.alist_tvbox.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserAgentParserTest {
    @Test
    void parsesChromeOnWindows() {
        String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
        assertEquals("Chrome 120", UserAgentParser.parseBrowser(ua));
        assertEquals("Windows", UserAgentParser.parseOs(ua));
    }

    @Test
    void parsesEdgeBeforeChrome() {
        String ua = "Mozilla/5.0 (Windows NT 10.0) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0";
        assertEquals("Edge 120", UserAgentParser.parseBrowser(ua));
    }

    @Test
    void parsesFirefoxOnMac() {
        String ua = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_1) Gecko/20100101 Firefox/121.0";
        assertEquals("Firefox 121", UserAgentParser.parseBrowser(ua));
        assertEquals("macOS", UserAgentParser.parseOs(ua));
    }

    @Test
    void parsesSafariOnIphone() {
        String ua = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 Mobile/15E148 Safari/604.1";
        assertEquals("Safari 17", UserAgentParser.parseBrowser(ua));
        assertEquals("iOS", UserAgentParser.parseOs(ua));
    }

    @Test
    void parsesOperaAndroid() {
        String ua = "Mozilla/5.0 (Linux; Android 13) Chrome/120.0.0.0 Safari/537.36 OPR/105.0.0.0";
        assertEquals("Opera 105", UserAgentParser.parseBrowser(ua));
        assertEquals("Android", UserAgentParser.parseOs(ua));
    }

    @Test
    void parsesLinux() {
        String ua = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
        assertEquals("Linux", UserAgentParser.parseOs(ua));
    }

    @Test
    void unknownWhenNullOrBlank() {
        assertEquals("未知", UserAgentParser.parseBrowser(null));
        assertEquals("未知", UserAgentParser.parseBrowser(""));
        assertEquals("未知", UserAgentParser.parseBrowser("   "));
        assertEquals("未知", UserAgentParser.parseOs(null));
        assertEquals("未知", UserAgentParser.parseOs("curl/8.0"));
    }
}
