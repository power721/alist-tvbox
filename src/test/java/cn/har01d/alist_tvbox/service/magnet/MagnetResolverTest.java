package cn.har01d.alist_tvbox.service.magnet;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 磁力元数据解析的纯函数部分(infohash 提取/种子解析);镜像拉取依赖外部站点不测。 */
class MagnetResolverTest {

    @Test
    void extractsHexInfoHash() {
        assertEquals("0123456789abcdef0123456789abcdef01234567",
                MagnetResolver.extractInfoHash("magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567&dn=x"));
    }

    @Test
    void extractsBase32InfoHashAsHex() {
        // base32 全 'A'(bit 全 0)32 字符 = 20 字节全 0
        assertEquals("0000000000000000000000000000000000000000",
                MagnetResolver.extractInfoHash("magnet:?xt=urn:btih:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
    }

    @Test
    void missingOrInvalidInfoHashReturnsNull() {
        assertNull(MagnetResolver.extractInfoHash("magnet:?dn=only-name"));
        assertNull(MagnetResolver.extractInfoHash("magnet:?xt=urn:btih:shorty"));
        assertNull(MagnetResolver.extractInfoHash(null));
        assertNull(MagnetResolver.extractInfoHash(""));
    }

    @Test
    void parsesMultiFileTorrent() {
        String torrent = "d4:infod5:filesld6:lengthi734002329e4:pathl4:Show16:S01E03.1080p.mkveed6:lengthi1048576e4:pathl9:extra.txt8:note.txteee4:name21:Show.S01.1080p.WEB-DLee";
        Optional<MagnetResolver.MagnetInfo> info =
                MagnetResolver.parseTorrent(torrent.getBytes(StandardCharsets.UTF_8), "0123456789abcdef0123456789abcdef01234567");

        assertTrue(info.isPresent());
        MagnetResolver.MagnetInfo metadata = info.get();
        assertEquals("Show.S01.1080p.WEB-DL", metadata.name());
        assertEquals("0123456789abcdef0123456789abcdef01234567", metadata.infoHash());
        assertEquals(2, metadata.files().size());
        assertEquals("Show/S01E03.1080p.mkv", metadata.files().get(0).path());
        assertEquals(734002329L, metadata.files().get(0).size());
        assertEquals(734002329L + 1048576L, metadata.totalSize());
    }

    @Test
    void parsesSingleFileTorrent() {
        String torrent = "d4:infod6:lengthi209715200e4:name18:Show.E05.2160p.mkvee";
        Optional<MagnetResolver.MagnetInfo> info =
                MagnetResolver.parseTorrent(torrent.getBytes(StandardCharsets.UTF_8), "abc");

        assertTrue(info.isPresent());
        assertEquals("Show.E05.2160p.mkv", info.get().name());
        assertEquals(1, info.get().files().size());
        assertEquals("Show.E05.2160p.mkv", info.get().files().get(0).path());
        assertEquals(209715200L, info.get().totalSize());
    }

    @Test
    void parsesEd2kLinkLocally() {
        String name = "测试剧 - 03 4K.mkv";
        long size = 834_000_000L;
        Optional<MagnetResolver.MagnetInfo> info = MagnetResolver.parseEd2k(
                "ed2k://|file|" + name + "|" + size + "|31D6CFE0D16AE931B73C59D7E0C089C0|/");
        assertTrue(info.isPresent());
        assertEquals(name, info.get().name());
        assertEquals(size, info.get().totalSize());
        assertEquals(1, info.get().files().size());
        assertEquals("31d6cfe0d16ae931b73c59d7e0c089c0", info.get().infoHash());
        assertTrue(MagnetResolver.parseEd2k("ed2k://|server|1.2.3.4|4321|/").isEmpty(), "server 链接不是文件");
        assertTrue(MagnetResolver.parseEd2k("ed2k://|file|bad|notanumber|hash|/").isEmpty(), "字节数畸形拒绝");
    }

    @Test
    void rejectsTorrentWithoutUsableFiles() {
        assertTrue(MagnetResolver.parseTorrent("d4:infod4:name4:testee".getBytes(StandardCharsets.UTF_8), "abc").isEmpty());
        assertTrue(MagnetResolver.parseTorrent("i42e".getBytes(StandardCharsets.UTF_8), "abc").isEmpty());
    }
}
