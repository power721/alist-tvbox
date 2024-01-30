package cn.har01d.alist_tvbox.util;

import cn.har01d.alist_tvbox.dto.bili.Dash;
import cn.har01d.alist_tvbox.dto.bili.Data;
import cn.har01d.alist_tvbox.dto.bili.Media;
import cn.har01d.alist_tvbox.dto.bili.Resp;
import lombok.extern.slf4j.Slf4j;

import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Slf4j
public final class DashUtils {
    private static final Map<String, String> audios = new HashMap<>();

    static {
        audios.put("30280", "192000");
        audios.put("30232", "132000");
        audios.put("30216", "64000");
    }

    private DashUtils() {
        throw new AssertionError();
    }

    public static Map<String, Object> convert(Resp resp, boolean open) {
        Dash dash = resp.getData() == null ? resp.getResult().getDash() : resp.getData().getDash();
        if (dash == null) {
            Data data = resp.getData() == null ? resp.getResult() : resp.getData();
            String url = data.getDurl().get(0).getUrl();
            Map<String, Object> map = new HashMap<>();
            map.put("url", url);
            return map;
        }

        int qn = 16;
        StringBuilder videoList = new StringBuilder();
        for (Media video : dash.getVideo()) {
            qn = Math.max(qn, Integer.parseInt(video.getId()));
        }

        for (Media video : dash.getVideo()) {
            if (video.getId().equals(String.valueOf(qn))) {
                videoList.append(getMedia(video));
            }
        }

        StringBuilder audioList = new StringBuilder();
        for (Media audio : dash.getAudio()) {
            for (String key : audios.keySet()) {
                if (audio.getId().equals(key)) {
                    audioList.append(getMedia(audio));
                }
            }
        }

        String mpd = getMpd(dash, videoList.toString(), audioList.toString());
        Map<String, Object> map = new HashMap<>();
        if (open) {
            map.put("mpd", mpd);
        } else {
            log.debug("{}", mpd);
            String encoded = Base64.getMimeEncoder().encodeToString(mpd.getBytes());
            String url = "data:application/dash+xml;base64," + encoded.replaceAll("\\r\\n", "\n") + "\n";
            map.put("url", url);
        }
        map.put("jx", "0");
        map.put("parse", "0");
        map.put("key", "BiliBili");
        map.put("format", "application/dash+xml");
        return map;
    }

    private static String getMedia(Media media) {
        if (media.getMimeType().startsWith("video")) {
            return getAdaptationSet(media, String.format(Locale.getDefault(), "height='%s' width='%s' frameRate='%s' sar='%s'", media.getHeight(), media.getWidth(), media.getFrameRate(), media.getSar()));
        } else if (media.getMimeType().startsWith("audio")) {
            return getAdaptationSet(media, String.format("numChannels='2' sampleRate='%s'", audios.get(media.getId())));
        } else {
            return "";
        }
    }

    private static String getAdaptationSet(Media media, String params) {
        String id = media.getId() + "_" + media.getCodecid();
        String type = media.getMimeType().split("/")[0];
        String baseUrl = media.getBaseUrl().replace("&", "&amp;");
        return String.format(Locale.getDefault(),
                "<AdaptationSet>\n" +
                        "<ContentComponent contentType=\"%s\"/>\n" +
                        "<Representation id=\"%s\" bandwidth=\"%s\" codecs=\"%s\" mimeType=\"%s\" %s startWithSAP=\"%s\">\n" +
                        "<BaseURL>%s</BaseURL>\n" +
                        "<SegmentBase indexRange=\"%s\">\n" +
                        "<Initialization range=\"%s\"/>\n" +
                        "</SegmentBase>\n" +
                        "</Representation>\n" +
                        "</AdaptationSet>\n",
                type,
                id, media.getBandwidth(), media.getCodecs(), media.getMimeType(), params, media.getStartWithSap(),
                baseUrl,
                media.getSegmentBase().getIndexRange(),
                media.getSegmentBase().getInitialization());
    }

    private static String getMpd(Dash dash, String videoList, String audioList) {
        return String.format(Locale.getDefault(),
                "<MPD xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns=\"urn:mpeg:dash:schema:mpd:2011\" xsi:schemaLocation=\"urn:mpeg:dash:schema:mpd:2011 DASH-MPD.xsd\" type=\"static\" mediaPresentationDuration=\"PT%sS\" minBufferTime=\"PT%sS\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\">\n" +
                        "<Period duration=\"PT%sS\" start=\"PT0S\">\n" +
                        "%s\n" +
                        "%s\n" +
                        "</Period>\n" +
                        "</MPD>",
                dash.getDuration(), dash.getMinBufferTime(),
                dash.getDuration(),
                videoList,
                audioList);
    }
}
