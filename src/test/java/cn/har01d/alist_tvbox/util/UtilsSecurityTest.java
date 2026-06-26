package cn.har01d.alist_tvbox.util;

import cn.har01d.alist_tvbox.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全相关纯函数回归测试:SSRF 拦截、路径穿越拦截、日志脱敏。
 */
class UtilsSecurityTest {

    // ---------- isSafeExternalUrl (SSRF 防护) ----------

    @Test
    void shouldRejectLoopbackUrls() {
        assertFalse(Utils.isSafeExternalUrl("http://127.0.0.1:4567/api/profiles"));
        assertFalse(Utils.isSafeExternalUrl("http://localhost/admin"));
        assertFalse(Utils.isSafeExternalUrl("http://0.0.0.0/x"));
    }

    @Test
    void shouldRejectIPv6LoopbackBracket() {
        // URI.getHost() 返回带方括号的 [::1],必须去掉方括号并解析才能拦住
        assertFalse(Utils.isSafeExternalUrl("http://[::1]:8080/admin"));
        assertFalse(Utils.isSafeExternalUrl("http://[0:0:0:0:0:0:0:1]/x"));
    }

    @Test
    void shouldRejectMetadataAndLinkLocalUrls() {
        assertFalse(Utils.isSafeExternalUrl("http://169.254.169.254/latest/meta-data/"));
        assertFalse(Utils.isSafeExternalUrl("http://169.254.1.1/x"));
        assertFalse(Utils.isSafeExternalUrl("http://metadata.google.internal/"));
    }

    @Test
    void shouldRejectNonHttpSchemes() {
        assertFalse(Utils.isSafeExternalUrl("file:///etc/passwd"));
        assertFalse(Utils.isSafeExternalUrl("gopher://x"));
        assertFalse(Utils.isSafeExternalUrl("dict://x"));
    }

    @Test
    void shouldAllowPublicHttpUrls() {
        assertTrue(Utils.isSafeExternalUrl("https://example.com/img.png"));
        assertTrue(Utils.isSafeExternalUrl("http://img.doubanio.com/x.jpg"));
    }

    @Test
    void shouldRejectMalformedUrls() {
        assertFalse(Utils.isSafeExternalUrl("not a url"));
        assertFalse(Utils.isSafeExternalUrl(""));
    }

    // ---------- requireSafePathSegment (路径穿越防护) ----------

    @Test
    void shouldRejectPathTraversal() {
        assertThrows(BadRequestException.class, () -> Utils.requireSafePathSegment("../../etc/passwd"));
        assertThrows(BadRequestException.class, () -> Utils.requireSafePathSegment("a/b"));
        assertThrows(BadRequestException.class, () -> Utils.requireSafePathSegment("a\\b"));
        assertThrows(BadRequestException.class, () -> Utils.requireSafePathSegment(null));
        assertThrows(BadRequestException.class, () -> Utils.requireSafePathSegment(""));
    }

    @Test
    void shouldAcceptSafeSegment() {
        assertDoesNotThrow(() -> Utils.requireSafePathSegment("1"));
        assertDoesNotThrow(() -> Utils.requireSafePathSegment("index_name-01"));
    }

    // ---------- mask (日志脱敏) ----------

    @Test
    void shouldMaskLongSecret() {
        assertEquals("ab****yz", Utils.mask("abcdefghijxyz"));
    }

    @Test
    void shouldMaskShortSecret() {
        assertEquals("****", Utils.mask("token"));
        assertEquals("****", Utils.mask("ab"));
    }

    @Test
    void shouldMaskNull() {
        assertEquals("", Utils.mask(null));
    }
}
