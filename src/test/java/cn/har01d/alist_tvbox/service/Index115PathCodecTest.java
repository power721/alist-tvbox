package cn.har01d.alist_tvbox.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Index115PathCodecTest {
    @Test
    void rootDecodesToNull() {
        assertNull(Index115PathCodec.decode(null));
        assertNull(Index115PathCodec.decode(""));
        assertNull(Index115PathCodec.decode("/"));
    }

    @Test
    void shareRootRoundTrips() {
        String p = Index115PathCodec.shareRoot("sw1", "6666");
        String[] d = Index115PathCodec.decode(p);
        assertEquals("sw1", d[0]);
        assertEquals("6666", d[1]);
        assertNull(d[2]);
    }

    @Test
    void childRoundTrips() {
        String p = Index115PathCodec.child("sw1", "6666", "file42");
        String[] d = Index115PathCodec.decode(p);
        assertEquals("sw1", d[0]);
        assertEquals("6666", d[1]);
        assertEquals("file42", d[2]);
    }

    @Test
    void nonIndexReturnsNull() {
        assertNull(Index115PathCodec.decode("/Movies/2024"));
    }
}
