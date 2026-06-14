package cn.har01d.alist_tvbox.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiliCookieRefreshUtilsTest {

    @Test
    void getCorrespondPathReturnsLowerHexCiphertext() throws Exception {
        String correspondPath = BiliCookieRefreshUtils.getCorrespondPath(1684466082562L);

        assertNotNull(correspondPath);
        assertEquals(256, correspondPath.length());
        assertTrue(correspondPath.matches("[0-9a-f]+"));
    }

    @Test
    void extractRefreshCsrfReadsTokenFromHtml() {
        String html = """
                <!DOCTYPE html>
                <html lang="zh-Hans">
                <body>
                  <div id="1-name">b0cc8411ded2f9db2cff2edb3123acac</div>
                  <div id="token-iframe-app"></div>
                </body>
                </html>
                """;

        assertEquals("b0cc8411ded2f9db2cff2edb3123acac", BiliCookieRefreshUtils.extractRefreshCsrf(html));
    }

    @Test
    void extractRefreshCsrfReadsTokenWhenIdIsNotFirstAttribute() {
        String html = """
                <!DOCTYPE html>
                <html lang="zh-Hans">
                <body>
                  <div class="token" data-key="csrf" id="1-name">
                    b0cc8411ded2f9db2cff2edb3123acac
                  </div>
                </body>
                </html>
                """;

        assertEquals("b0cc8411ded2f9db2cff2edb3123acac", BiliCookieRefreshUtils.extractRefreshCsrf(html));
    }

    @Test
    void mergeCookieHeaderReplacesExistingCookieValues() {
        String cookie = "SESSDATA=old; bili_jct=oldcsrf; DedeUserID=100";

        String merged = BiliCookieRefreshUtils.mergeCookieHeader(cookie, List.of(
                "SESSDATA=new; Path=/; Domain=bilibili.com",
                "bili_jct=newcsrf; Path=/; Domain=bilibili.com",
                "sid=newsid; Path=/; Domain=bilibili.com"
        ));

        assertEquals("SESSDATA=new; bili_jct=newcsrf; DedeUserID=100; sid=newsid", merged);
    }

    @Test
    void ensureBuvid3AddsCookieWhenMissing() {
        String cookie = "SESSDATA=abc; bili_jct=csrf";

        String updated = BiliCookieRefreshUtils.ensureBuvid3(cookie);

        assertTrue(updated.startsWith("SESSDATA=abc; bili_jct=csrf; buvid3="));
    }

    @Test
    void getCookieValueReturnsNullWhenCookieMissing() {
        assertNull(BiliCookieRefreshUtils.getCookieValue("SESSDATA=abc", "bili_jct"));
    }
}
