package cn.har01d.alist_tvbox.util;

import cn.har01d.alist_tvbox.dto.CatAudio;
import cn.har01d.alist_tvbox.dto.bili.Dash;
import cn.har01d.alist_tvbox.dto.bili.Data;
import cn.har01d.alist_tvbox.dto.bili.Media;
import cn.har01d.alist_tvbox.dto.bili.Resp;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
public final class DashUtils {
    private static final Map<String, Integer> audioIds = new HashMap<>();
    private static final Set<String> clients = new HashSet<>();

    static {
        clients.add("open");
        clients.add("com.fongmi.android.tv");
        clients.add("com.github.tvbox.osc.tk");
        clients.add("com.yek.android.c");
        clients.add("com.mygithub0.tvbox0.osdX");

        audioIds.put("30251", 192000);
        audioIds.put("30250", 192000);
        audioIds.put("30280", 192000);
        audioIds.put("30232", 132000);
        audioIds.put("30216", 64000);
    }

    private DashUtils() {
        throw new AssertionError();
    }

    public static boolean isClientSupport(String client) {
        return clients.contains(client);
    }

    public static Map<String, Object> convert(Resp resp, List<String> qns, String client) {
        Data data = resp.getData() == null ? resp.getResult() : resp.getData();
        Dash dash = data.getDash() == null ? (data.getVideoInfo() != null ? data.getVideoInfo().getDash() : null) : data.getDash();
        if (dash == null) {
            String url = "";
            if (!data.getDurl().isEmpty()) {
                url = data.getDurl().get(0).getUrl();
            } else if (!data.getDurls().isEmpty()) {
                url = data.getDurls().get(0).getDurl().get(0).getUrl();
            } else if (data.getVideoInfo() != null) {
                if (!data.getVideoInfo().getDurl().isEmpty()) {
                    url = data.getVideoInfo().getDurl().get(0).getUrl();
                } else {
                    url = data.getVideoInfo().getDurls().get(0).getDurl().get(0).getUrl();
                }
            }
            Map<String, Object> map = new HashMap<>();
            map.put("url", url);
            return map;
        }

        List<String> urls = new ArrayList<>();
        List<CatAudio> audios = new ArrayList<>();
        boolean hasAny = false;
        StringBuilder videoList = new StringBuilder();
        for (Media video : dash.getVideo()) {
            if (qns.contains(video.getId())) {
                hasAny = true;
                break;
            }
        }

        Map<String, String> quality = new HashMap<>();
        for (int i = 0; i < data.getAcceptQuality().size(); i++) {
            quality.put(String.valueOf(data.getAcceptQuality().get(i)), data.getAcceptDescription().get(i));
        }

        for (Media video : dash.getVideo()) {
            if (!hasAny || qns.contains(video.getId())) {
                videoList.append(getMedia(video));
                urls.add(quality.get(video.getId()) + " " + getCodec(video.getCodecid()));
                urls.add(video.getBaseUrl());
            }
        }

        StringBuilder audioList = new StringBuilder();
        for (Media audio : dash.getAudio()) {
            audioList.append(getMedia(audio));
            if (audioIds.containsKey(audio.getId())) {
                CatAudio catAudio = new CatAudio();
                catAudio.setBit(audioIds.get(audio.getId()));
                catAudio.setTitle(getAudioTitle(audio.getId()));
                catAudio.setUrl(audio.getBaseUrl());
                audios.add(catAudio);
            }
        }

        String mpd = getMpd(dash, videoList.toString(), audioList.toString());
        Map<String, Object> map = new HashMap<>();
        if ("open".equals(client)) {
            map.put("mpd", mpd);
            map.put("format", "application/dash+xml");
        } else if ("node".equals(client)) {
            audios.sort(Comparator.comparingInt(CatAudio::getBit).reversed());
            map.put("extra", Map.of("audio", audios));
            map.put("url", urls);
        } else {
            log.debug("{}", mpd);
            String encoded = Base64.getMimeEncoder().encodeToString(mpd.getBytes());
            String url = "data:application/dash+xml;base64," + encoded.replaceAll("\\r\\n", "\n") + "\n";
            map.put("url", url);
            map.put("format", "application/dash+xml");
        }
        map.put("jx", "0");
        map.put("parse", "0");
        return map;
    }

    private static String getCodec(String id) {
        if (id.equals("7")) {
            return "AVC";
        }
        if (id.equals("12")) {
            return "HEVC";
        }
        return "AV1";
    }

    private static String getAudioTitle(String id) {
        if (id.equals("30250")) {
            return "杜比全景声";
        }
        if (id.equals("30251")) {
            return "Hi-Res无损";
        }
        return (audioIds.get(id) / 1024) + "Kbps";
    }

    private static String getMedia(Media media) {
        if (media.getMimeType().startsWith("video")) {
            return getAdaptationSet(media, String.format(Locale.getDefault(), "height='%s' width='%s' frameRate='%s' sar='%s'", media.getHeight(), media.getWidth(), media.getFrameRate(), media.getSar()));
        } else if (media.getMimeType().startsWith("audio")) {
            return getAdaptationSet(media, String.format("numChannels='2' sampleRate='%s'", audioIds.get(media.getId())));
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
