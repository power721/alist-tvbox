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
        clients.add("com.silent.android.webhtv");

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

        boolean multiBase = supportsMultiBaseUrl(client);
        for (Media video : dash.getVideo()) {
            if (!hasAny || qns.contains(video.getId())) {
                videoList.append(getMedia(video, multiBase));
                urls.add(quality.get(video.getId()) + " " + getCodec(video.getCodecid()));
                urls.add(resolveUrl(video));
            }
        }

        StringBuilder audioList = new StringBuilder();
        for (Media audio : dash.getAudio()) {
            audioList.append(getMedia(audio, multiBase));
            if (audioIds.containsKey(audio.getId())) {
                CatAudio catAudio = new CatAudio();
                catAudio.setBit(audioIds.get(audio.getId()));
                catAudio.setTitle(getAudioTitle(audio.getId()));
                catAudio.setUrl(resolveUrl(audio));
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

    // B 站调度会把 baseUrl 分到 PCDN/P2P 节点(mcdn*.bilivideo.cn、*.szbdyd.com),
    // 直连时长视频大偏移 Range(续播 seek)易挂起且限速;MPD 每个 Representation 仅嵌一个
    // BaseURL 无备线可换,命中 PCDN 即改用 backupUrl 里的常规 CDN(路径与参数跨节点通用)。
    static String resolveUrl(Media media) {
        return resolveUrls(media).get(0);
    }

    // 多线路 MPD 仅对已验证支持多条 <BaseURL> 故障转移的消费方开放:gui=桌面端(对每条
    // BaseURL 各建资产),com.fongmi.android.tv=内嵌 media3(逐条收集为 failover 候选)。
    // 老壳子(tk/影视仓/OKJOY/webhtv)引擎年龄未知,老 exo2 对多条 BaseURL 是后条覆盖前条,
    // 多线路会退化成永远用最后一条,维持单线路。爬虫端代理会把每条 BaseURL 各改写为一个
    // 分片代理地址,exo 在其间失败转移即等于换 CDN 线路。
    private static boolean supportsMultiBaseUrl(String client) {
        return "gui".equals(client) || "com.fongmi.android.tv".equals(client);
    }

    // 线路全集,首条为择优结果(非 PCDN 优先,主线路干净则保主线路),其余按 B 站原始顺序跟随。
    // 同表示线路字节相同,客户端中途换线安全;ISO DASH 同层级多条 BaseURL 即标准故障转移形态。
    static List<String> resolveUrls(Media media) {
        List<String> all = new ArrayList<>();
        String primary = media.getBaseUrl();
        if (primary != null && !primary.isEmpty()) {
            all.add(primary);
        }
        List<String> backups = media.getBackupUrl();
        if (backups != null) {
            for (String backup : backups) {
                if (backup != null && !backup.isEmpty() && !all.contains(backup)) {
                    all.add(backup);
                }
            }
        }
        if (all.isEmpty()) {
            all.add("");
            return all;
        }
        String first = all.get(0);
        if (isPcdn(first)) {
            for (String candidate : all) {
                if (!isPcdn(candidate)) {
                    first = candidate;
                    log.debug("swap PCDN host: {} -> {}", all.get(0), candidate);
                    break;
                }
            }
        }
        List<String> ordered = new ArrayList<>();
        ordered.add(first);
        for (String url : all) {
            if (!url.equals(first)) {
                ordered.add(url);
            }
        }
        return ordered;
    }

    static boolean isPcdn(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return false;
        }
        String host = url.substring(scheme + 3);
        int end = host.indexOf('/');
        if (end >= 0) {
            host = host.substring(0, end);
        }
        int port = host.indexOf(':');
        if (port >= 0) {
            // mcdn PCDN 家族恒走 4483 端口:除 mcdn.bilivideo.cn 外还有第三方边缘域
            // (如 edge.mountaintoys.cn,host 无任何 mcdn 字样),常规 upos CDN 恒 443
            if (host.substring(port + 1).equals("4483")) {
                return true;
            }
            host = host.substring(0, port);
        }
        host = host.toLowerCase(Locale.ROOT);
        // os=mcdn 是 B 站调度参数里的节点类型标记,兜住非 4483 端口的三方 PCDN 域
        return host.contains("mcdn") || host.endsWith(".szbdyd.com") || host.contains("p2p") || url.contains("os=mcdn");
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

    private static String getMedia(Media media, boolean multiBase) {
        if (media.getMimeType().startsWith("video")) {
            return getAdaptationSet(media, String.format(Locale.getDefault(), "height='%s' width='%s' frameRate='%s' sar='%s'", media.getHeight(), media.getWidth(), media.getFrameRate(), media.getSar()), multiBase);
        } else if (media.getMimeType().startsWith("audio")) {
            return getAdaptationSet(media, String.format("numChannels='2' sampleRate='%s'", audioIds.get(media.getId())), multiBase);
        } else {
            return "";
        }
    }

    private static String getAdaptationSet(Media media, String params, boolean multiBase) {
        String id = media.getId() + "_" + media.getCodecid();
        String type = media.getMimeType().split("/")[0];
        StringBuilder baseUrls = new StringBuilder();
        List<String> urls = resolveUrls(media);
        int count = multiBase ? urls.size() : 1;
        for (int i = 0; i < count; i++) {
            baseUrls.append("<BaseURL>").append(urls.get(i).replace("&", "&amp;")).append("</BaseURL>\n");
        }
        return String.format(Locale.getDefault(),
                "<AdaptationSet>\n" +
                        "<ContentComponent contentType=\"%s\"/>\n" +
                        "<Representation id=\"%s\" bandwidth=\"%s\" codecs=\"%s\" mimeType=\"%s\" %s startWithSAP=\"%s\">\n" +
                        "%s" +
                        "<SegmentBase indexRange=\"%s\">\n" +
                        "<Initialization range=\"%s\"/>\n" +
                        "</SegmentBase>\n" +
                        "</Representation>\n" +
                        "</AdaptationSet>\n",
                type,
                id, media.getBandwidth(), media.getCodecs(), media.getMimeType(), params, media.getStartWithSap(),
                baseUrls,
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
