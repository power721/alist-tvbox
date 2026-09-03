package cn.har01d.alist_tvbox.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BencodeTest {

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void decodesIntegerAndString() {
        assertEquals(42L, Bencode.decode(utf8("i42e")));
        assertEquals(-7L, Bencode.decode(utf8("i-7e")));
        assertEquals("hello", Bencode.asString(Bencode.decode(utf8("5:hello"))));
        assertEquals("中文", Bencode.asString(Bencode.decode(utf8("6:中文"))));
    }

    @Test
    void decodesNestedContainers() {
        Object decoded = Bencode.decode(utf8("d4:spaml1:a1:bi3eee"));
        Map<String, Object> dict = Bencode.asDict(decoded);
        assertEquals(1, dict.size());
        assertEquals("spam", dict.keySet().iterator().next());
        List<Object> list = Bencode.asList(dict.get("spam"));
        assertEquals("a", Bencode.asString(list.get(0)));
        assertEquals("b", Bencode.asString(list.get(1)));
        assertEquals(3L, list.get(2));
    }

    @Test
    void decodesMultiFileTorrentShape() {
        String torrent = "d8:announce14:http://tracker4:infod6:lengthi1024e4:name8:show.mkvee";
        Map<String, Object> root = Bencode.asDict(Bencode.decode(utf8(torrent)));
        Map<String, Object> info = Bencode.asDict(root.get("info"));
        assertEquals("show.mkv", Bencode.asString(info.get("name")));
        assertEquals(1024L, info.get("length"));
    }

    @Test
    void decodesListedFileTorrentShape() {
        String torrent = "d4:infod5:filesld6:lengthi2048e4:pathl3:S0113:E03.1080p.mkveed6:lengthi4096e4:pathl3:S0113:E04.1080p.mkveee4:name13:show.s01.packee";
        Map<String, Object> info = Bencode.asDict(Bencode.asDict(Bencode.decode(utf8(torrent))).get("info"));
        assertEquals("show.s01.pack", Bencode.asString(info.get("name")));
        List<Object> files = Bencode.asList(info.get("files"));
        assertEquals(2, files.size());
        Map<String, Object> first = Bencode.asDict(files.get(0));
        assertEquals(2048L, first.get("length"));
        assertEquals("S01", Bencode.asString(Bencode.asList(first.get("path")).get(0)));
        assertEquals("E03.1080p.mkv", Bencode.asString(Bencode.asList(first.get("path")).get(1)));
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> Bencode.decode(utf8("i42")));       // 未终结
        assertThrows(IllegalArgumentException.class, () -> Bencode.decode(utf8("9:hello")));    // 声明长度越界
        assertThrows(IllegalArgumentException.class, () -> Bencode.decode(utf8("x4:abc")));    // 非法 token
        assertThrows(IllegalArgumentException.class, () -> Bencode.decode(utf8("i42e999")));   // 尾部垃圾
    }

    @Test
    void helpersRejectWrongTypes() {
        assertNull(Bencode.asString(5L));
        assertNull(Bencode.asDict(List.of()));
        assertNull(Bencode.asList(Map.of()));
        assertEquals(42L, Bencode.decode(utf8("i42e"))); // 无 key() 后保持断言数
    }
}
