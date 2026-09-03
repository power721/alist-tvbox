package cn.har01d.alist_tvbox.service.magnet;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void bencodeLookaheadRejectsErrorPages() {
        // 镜像站返回 200+HTML(btcache.me SPA 首页线上形态):前置内容校验直接判非种子,
        // 不让 Bencode 解码器抛"invalid bencode token at 0"混淆镜像故障与未收录
        assertTrue(MagnetResolver.looksLikeBencode("d4:infod".getBytes(StandardCharsets.UTF_8)), "字典开头");
        assertTrue(MagnetResolver.looksLikeBencode("l4:list".getBytes(StandardCharsets.UTF_8)), "列表开头");
        assertTrue(MagnetResolver.looksLikeBencode("i42e".getBytes(StandardCharsets.UTF_8)), "整数开头");
        assertTrue(MagnetResolver.looksLikeBencode("4:spam".getBytes(StandardCharsets.UTF_8)), "字节串长度前缀开头");
        assertFalse(MagnetResolver.looksLikeBencode("<!DOCTYPE html>".getBytes(StandardCharsets.UTF_8)), "HTML 页");
        assertFalse(MagnetResolver.looksLikeBencode("{\"error\":1}".getBytes(StandardCharsets.UTF_8)), "JSON 错误体");
        assertFalse(MagnetResolver.looksLikeBencode(new byte[0]), "空体");
        assertFalse(MagnetResolver.looksLikeBencode(null), "null");
    }
}
