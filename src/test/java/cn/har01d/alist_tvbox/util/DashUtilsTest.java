package cn.har01d.alist_tvbox.util;

import cn.har01d.alist_tvbox.dto.CatAudio;
import cn.har01d.alist_tvbox.dto.bili.Dash;
import cn.har01d.alist_tvbox.dto.bili.Data;
import cn.har01d.alist_tvbox.dto.bili.Media;
import cn.har01d.alist_tvbox.dto.bili.Resp;
import cn.har01d.alist_tvbox.dto.bili.Segment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DashUtilsTest {
    private static final String MCDN = "https://xy112x222x23xy.mcdn.bilivideo.cn:4483/upos/C0DE/xxx.m4s?e=exp&os=mcdn";
    private static final String MIRROR = "https://upos-sz-mirrorcos.bilivideo.com/upos/C0DE/xxx.m4s?e=exp&os=upos";
    private static final String MIRROR_B = "https://upos-sz-mirrorcosb.bilivideo.com/upos/C0DE/xxx.m4s?e=exp&os=upos";

    @Test
    void swapsPcdnPrimaryToMirrorBackup() {
        Map<String, Object> map = DashUtils.convert(resp(MCDN, List.of(MIRROR, MIRROR_B), MCDN, List.of(MIRROR)), List.of(), "node");
        List<String> urls = (List<String>) map.get("url");
        assertEquals(MIRROR, urls.get(1));
        @SuppressWarnings("unchecked")
        List<CatAudio> audios = (List<CatAudio>) ((Map<String, Object>) map.get("extra")).get("audio");
        assertEquals(1, audios.size());
        assertEquals(MIRROR, audios.get(0).getUrl());
    }

    @Test
    void dataUriMpdEmbedsSwappedUrl() {
        Map<String, Object> map = DashUtils.convert(resp(MCDN, List.of(MIRROR), MCDN, List.of(MIRROR)), List.of(), "com.fongmi.android.tv");
        String url = (String) map.get("url");
        assertTrue(url.startsWith("data:application/dash+xml;base64,"));
        String mpd = new String(Base64.getMimeDecoder().decode(url.substring("data:application/dash+xml;base64,".length())));
        assertFalse(mpd.contains("mcdn"));
        assertTrue(mpd.contains("upos-sz-mirrorcos"));
    }

    @Test
    void keepsRegularPrimary() {
        Map<String, Object> map = DashUtils.convert(resp(MIRROR, List.of(MIRROR_B), MIRROR, List.of(MIRROR_B)), List.of(), "node");
        List<String> urls = (List<String>) map.get("url");
        assertEquals(MIRROR, urls.get(1));
    }

    @Test
    void keepsPcdnWhenAllBackupsPcdn() {
        String szbdyd = "https://p2p.szbdyd.com/upos/C0DE/xxx.m4s?e=exp";
        Map<String, Object> map = DashUtils.convert(resp(MCDN, List.of(szbdyd), MIRROR, List.of()), List.of(), "node");
        List<String> urls = (List<String>) map.get("url");
        assertEquals(MCDN, urls.get(1));
    }

    @Test
    void emptyPrimaryFallsBackToFirstBackup() {
        Map<String, Object> map = DashUtils.convert(resp("", List.of(MIRROR, MIRROR_B), "", List.of(MIRROR_B)), List.of(), "node");
        List<String> urls = (List<String>) map.get("url");
        assertEquals(MIRROR, urls.get(1));
    }

    @Test
    void nullBackupUrlIsSafe() {
        Map<String, Object> map = DashUtils.convert(resp(MCDN, null, MIRROR, null), List.of(), "node");
        List<String> urls = (List<String>) map.get("url");
        assertEquals(MCDN, urls.get(1));
    }

    @Test
    void isPcdn() {
        assertTrue(DashUtils.isPcdn(MCDN));
        assertTrue(DashUtils.isPcdn("https://upos-sz-p2p-test.bilivideo.com/upos/a.m4s"));
        assertTrue(DashUtils.isPcdn("https://xy.szbdyd.com/upos/a.m4s?e=1"));
        assertFalse(DashUtils.isPcdn(MIRROR));
        assertFalse(DashUtils.isPcdn("https://cn-jsnt-ct-01-06.bilivideo.com/upos/a.m4s"));
        assertFalse(DashUtils.isPcdn("not-a-url"));
    }

    private static Resp resp(String videoUrl, List<String> videoBackup, String audioUrl, List<String> audioBackup) {
        Resp resp = new Resp();
        Data data = new Data();
        Dash dash = new Dash();
        dash.setDuration("600");
        dash.setMinBufferTime("1.5");
        dash.setVideo(List.of(media("32", videoUrl, videoBackup, "video/mp4", "avc1.640034")));
        dash.setAudio(List.of(media("30280", audioUrl, audioBackup, "audio/mp4", "mp4a.40.2")));
        data.setDash(dash);
        data.setAcceptQuality(new ArrayList<>(List.of(32)));
        data.setAcceptDescription(new ArrayList<>(List.of("1080P")));
        resp.setData(data);
        return resp;
    }

    private static Media media(String id, String baseUrl, List<String> backupUrl, String mimeType, String codecs) {
        Media media = new Media();
        media.setId(id);
        media.setBaseUrl(baseUrl);
        media.setBackupUrl(backupUrl);
        media.setMimeType(mimeType);
        media.setCodecs(codecs);
        media.setBandwidth("2000000");
        media.setCodecid("7");
        Segment segment = new Segment();
        segment.setInitialization("0-1000");
        segment.setIndexRange("1001-2000");
        media.setSegmentBase(segment);
        return media;
    }
}
