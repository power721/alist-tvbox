package cn.har01d.alist_tvbox.live.service;

import cn.har01d.alist_tvbox.live.model.HuyaCategoryList;
import cn.har01d.alist_tvbox.live.model.HuyaLiveRoomInfoListResponse;
import cn.har01d.alist_tvbox.live.model.HuyaLiveRoom;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class HuyaService implements LivePlatform {
    private static final Pattern OwnerName = Pattern.compile("\"sNick\":\"([\\s\\S]*?)\",");
    private static final Pattern RoomName = Pattern.compile("\"sIntroduction\":\"([\\s\\S]*?)\",");
    private static final Pattern RoomPic = Pattern.compile("\"sScreenshot\":\"([\\s\\S]*?)\",");
    private static final Pattern OwnerPic = Pattern.compile("\"sAvatar180\":\"([\\s\\S]*?)\",");
    private static final Pattern AREA = Pattern.compile("\"sGameFullName\":\"([\\s\\S]*?)\",");
    private static final Pattern Num = Pattern.compile("\"lActivityCount\":([\\s\\S]*?),");
    private static final Pattern ISLIVE = Pattern.compile("\"eLiveStatus\":([\\s\\S]*?),");
    private static final Pattern S_STREAM_NAME = Pattern.compile("\"sStreamName\":\"([\\s\\S]*?)\",");
    private static final Pattern S_FLV_URL = Pattern.compile("\"sFlvUrl\":\"([\\s\\S]*?)\",");
    private static final Pattern S_FLV_URL_SUFFIX = Pattern.compile("\"sFlvUrlSuffix\":\"([\\s\\S]*?)\",");
    private static final Pattern S_FLV_ANTI_CODE = Pattern.compile("\"sFlvAntiCode\":\"([\\s\\S]*?)\",");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public HuyaService(RestTemplateBuilder builder, ObjectMapper objectMapper) {
        this.restTemplate = builder
                .defaultHeader("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1")
                .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public String getType() {
        return "huya";
    }

    @Override
    public String getName() {
        return "虎牙";
    }

    @Override
    public MovieList home() throws JsonProcessingException {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();
        String url = "https://www.huya.com/cache.php?m=LiveList&do=getLiveListByPage&tagAll=0";
        var json = restTemplate.getForObject(url, String.class);
        log.trace("home json: {}", json);
        var response = objectMapper.readValue(json, HuyaLiveRoomInfoListResponse.class);
        for (var room : response.getData().getDatas()) {
            MovieDetail detail = new MovieDetail();
            detail.setVod_id(getType() + "$" + room.getProfileRoom());
            detail.setVod_name(room.getIntroduction());
            detail.setVod_pic(room.getScreenshot());
            detail.setVod_remarks(room.getNick());
            list.add(detail);
        }

        result.setList(list);
        result.setTotal(list.size());
        result.setLimit(list.size());

        log.debug("home result: {}", result);
        return result;
    }

    @Override
    public CategoryList category() throws IOException {
        CategoryList result = new CategoryList();
        List<Category> list = new ArrayList<>();

        var json = restTemplate.getForObject("https://m.huya.com/cache.php?m=Game&do=ajaxGameList&bussType=", String.class);
        log.trace("category json: {}", json);
        var huyaCategoryList = objectMapper.readValue(json, HuyaCategoryList.class);
        for (var item : huyaCategoryList.getGameList()) {
            Category category = new Category();
            category.setType_id(getType() + "-" + item.getGid());
            category.setType_name(item.getGameFullName());
            category.setType_flag(0);
            category.setCover("https://huyaimg.msstatic.com/cdnimage/game/" + item.getGid() + "-MS.jpg");
            list.add(category);
        }

        result.setCategories(list);
        result.setTotal(result.getCategories().size());
        result.setLimit(result.getCategories().size());

        log.debug("category result: {}", result);
        return result;
    }

    @Override
    public MovieList list(String id, String sort, Integer pg) throws IOException {
        String[] parts = id.split("-");
        String gid = parts[1];

        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        String url = "https://www.huya.com/cache.php?m=LiveList&do=getLiveListByPage&gameId=" + gid + "&tagAll=0&page=" + pg;
        var json = restTemplate.getForObject(url, String.class);
        log.trace("list json: {}", json);
        var response = objectMapper.readValue(json, HuyaLiveRoomInfoListResponse.class);
        for (var room : response.getData().getDatas()) {
            MovieDetail detail = new MovieDetail();
            detail.setVod_id(getType() + "$" + room.getProfileRoom());
            detail.setVod_name(room.getIntroduction());
            detail.setVod_pic(room.getScreenshot());
            detail.setVod_remarks(room.getNick());
            list.add(detail);
        }

        result.setList(list);
        result.setPage(pg);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        result.setPagecount(response.getData().getTotalPage());

        log.debug("list result: {}", result);
        return result;
    }

    @Override
    public MovieList search(String wd) {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        String url = "https://search.cdn.huya.com/?m=Search&do=getSearchContent&q=" + wd + "&uid=0&v=4&typ=-5&livestate=0&rows=30&start=0";
        ObjectNode json = restTemplate.getForObject(url, ObjectNode.class);
        log.trace("search json: {}", json);
        ObjectNode response = (ObjectNode) json.get("response");
        response = (ObjectNode) response.get("1");
        ArrayNode arrayNode = (ArrayNode) response.get("docs");
        for (int i = 0; i < arrayNode.size(); i++) {
            MovieDetail detail = new MovieDetail();
            ObjectNode item = (ObjectNode) arrayNode.get(i);
            detail.setVod_id(getType() + "$" + item.get("room_id").asText());
            detail.setVod_name(item.get("live_intro").asText());
            detail.setVod_pic(item.get("game_avatarUrl52").asText());
            detail.setVod_remarks(playCount(item.get("game_activityCount").asInt()));
            list.add(detail);
        }

        result.setList(list);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());

        return result;
    }

    @Override
    public MovieList detail(String tid) throws IOException {
        String[] parts = tid.split("\\$");
        String id = parts[1];
        MovieList result = new MovieList();
        String url = "https://m.huya.com/" + id;
        var response = restTemplate.getForObject(url, String.class);
        Matcher matcherOwnerName = OwnerName.matcher(response);
        Matcher matcherRoomName = RoomName.matcher(response);
        Matcher matcherRoomPic = RoomPic.matcher(response);
        Matcher matcherOwnerPic = OwnerPic.matcher(response);
        Matcher matcherAREA = AREA.matcher(response);
        Matcher matcherNum = Num.matcher(response);
        Matcher matcherISLIVE = ISLIVE.matcher(response);
        if (!(matcherOwnerName.find() && matcherRoomName.find() && matcherRoomPic.find()
                && matcherOwnerPic.find() && matcherAREA.find() && matcherNum.find()
                && matcherISLIVE.find())) {
            log.info("虎牙获取房间信息异常,roomId:[{}]", tid);
            return result;
        }
        String resultOwnerName = matcherOwnerName.group();
        String resultRoomName = matcherRoomName.group();
        String resultRoomPic = matcherRoomPic.group();
        String resultAREA = matcherAREA.group();
        String resultNum = matcherNum.group();

        MovieDetail detail = new MovieDetail();
        detail.setVod_id(tid);
        detail.setVod_actor(getMatchResult(resultOwnerName, "\":\"", "\""));
        detail.setVod_name(getMatchResult(resultRoomName, "\":\"", "\""));
        detail.setVod_pic(getMatchResult(resultRoomPic, "\":\"", "\""));
        detail.setType_name(getMatchResult(resultAREA, "\":\"", "\""));
        String count = getMatchResult(resultNum, "\":", ",");
        if (!count.isEmpty()) {
            detail.setVod_remarks(playCount(count));
        }
        parseUrls(detail, response);
        result.getList().add(detail);

        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        log.debug("detail: {}", result);
        return result;
    }

    private String playCount(String count) {
        if (count == null || count.isBlank()) {
            return null;
        }
        int view = Integer.parseInt(count);
        if (view >= 10000) {
            return (view / 10000) + "万";
        } else if (view >= 1000) {
            return (view / 1000) + "千";
        } else {
            return view + "";
        }
    }

    private String playCount(int view) {
        if (view >= 10000) {
            return (view / 10000) + "万";
        } else if (view >= 1000) {
            return (view / 1000) + "千";
        } else {
            return view + "";
        }
    }

    private void parseUrls(MovieDetail movieDetail, String html) throws IOException {
        int start = html.indexOf("window.HNF_GLOBAL_INIT = ") + 25;
        int end = html.indexOf("</script>", start);
        List<String> playFrom = new ArrayList<>();
        List<String> playUrl = new ArrayList<>();
        if (start > 0 && end > start) {
            String uid = getUid(13, 10);
            String json = html.substring(start, end);
            start = json.indexOf("vBitRateInfo");
            start = json.indexOf("\"value\":", start);
            end = json.indexOf("],", start) + 1;
            log.trace("vBitRateInfo: {}", "{" + json.substring(start, end) + "}");
            HuyaLiveRoom.BitRateInfoList vBitRateInfo = objectMapper.readValue("{" + json.substring(start, end) + "}", HuyaLiveRoom.BitRateInfoList.class);

            List<String> sStreamNames = findAll(json, S_STREAM_NAME);
            List<String> sFlvUrl = findAll(json, S_FLV_URL);
            List<String> sFlvUrlSuffix = findAll(json, S_FLV_URL_SUFFIX);
            List<String> sFlvAntiCode = findAll(json, S_FLV_ANTI_CODE);

            for (int i = 0; i < sStreamNames.size(); i++) {
                playFrom.add("线路" + (i + 1));
                String streamName = sStreamNames.get(i);
                String streamUrl = sFlvUrl.get(i).replace("\\u002F", "/") + "/" + streamName + "." + sFlvUrlSuffix.get(i);
                streamUrl = streamUrl.replace("http://", "https://");
                streamUrl += "?" + processAnticode(sFlvAntiCode.get(i), uid, streamName);
                List<String> urls = new ArrayList<>();
                for (var bitRateInfo : vBitRateInfo.getValue()) {
                    String url = streamUrl;
                    int bitRate = bitRateInfo.getIBitRate();
                    if (bitRate > 0) {
                        url += "&ratio=" + bitRate;
                    }
                    String qualityName = bitRateInfo.getSDisplayName();
                    if (!qualityName.contains("HDR")) {
                        urls.add(qualityName + "$" + url);
                    }
                }
                playUrl.add(String.join("#", urls));
            }
        }
        movieDetail.setVod_play_from(String.join("$$$", playFrom));
        movieDetail.setVod_play_url(String.join("$$$", playUrl));
    }

    private List<String> findAll(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        List<String> list = new ArrayList<>();
        while (matcher.find()) {
            String value = matcher.group(1);
            if (!value.isBlank()) {
                list.add(value);
            }
        }
        return list;
    }

    private String processAnticode(String anticode, String uid, String streamname) {
        Map<String, String> q = new HashMap<>();
        try {
            for (String param : anticode.split("&")) {
                String[] pair = param.split("=");
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String value = "";
                if (pair.length > 1) {
                    value = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                }
                q.put(key, value);
            }
        } catch (Exception e) {
            log.warn("", e);
        }
        q.put("t", "102");
        q.put("ctype", "tars_mp");

        long seqid = System.currentTimeMillis() + Long.parseLong(uid);

        // wsTime
        String wsTime = Long.toHexString(Instant.now().toEpochMilli() / 1000 + 21600);

        // wsSecret
        String fm = new String(Base64.getDecoder().decode(URLDecoder.decode(q.get("fm"), StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        String wsSecretPrefix = fm.split("_")[0];

        String wsSecretHash = Utils.md5(String.format("%s|%s|%s", seqid, q.get("ctype"), q.get("t")));
        String wsSecret = Utils.md5(String.format("%s_%s_%s_%s_%s", wsSecretPrefix, uid, streamname, wsSecretHash, wsTime));

        LinkedHashMap<String, String> resultParamMap = new LinkedHashMap<>();
        resultParamMap.put("wsSecret", wsSecret);
        resultParamMap.put("wsTime", wsTime);
        resultParamMap.put("seqid", String.valueOf(seqid));
        resultParamMap.put("ctype", q.get("ctype"));
        resultParamMap.put("ver", "1");
        resultParamMap.put("fs", q.get("fs"));
        resultParamMap.put("uid", uid);
        resultParamMap.put("uuid", getUUid());
        resultParamMap.put("t", q.get("t"));
        resultParamMap.put("sv", "2401310321");
        resultParamMap.put("dMod", "mseh-0");
        resultParamMap.put("sdkPcdn", "2_1");
        resultParamMap.put("sdk_sid", "1733532984780");
        resultParamMap.put("a_block", "0");
        resultParamMap.put("codec", "264");
        return buildQueryString(resultParamMap);
    }

    private String getMatchResult(String str, String indexStartStr, String indexEndStr) {
        String result;
        result = str.substring(str.indexOf(indexStartStr) + indexStartStr.length(), str.lastIndexOf(indexEndStr));
        return result;
    }

    public static String getUUid() {
        long currentTime = System.currentTimeMillis();
        SecureRandom random = new SecureRandom();
        int randomValue = random.nextInt(Integer.MAX_VALUE);
        long result = (currentTime % 10000000000L * 1000L + randomValue) % 4294967295L;
        return Long.toString(result);
    }

    private String buildQueryString(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((key, value) -> {
            sb.append(key).append("=").append(value).append("&");
        });
        if (!sb.isEmpty()) {
            sb.setLength(sb.length() - 1); // 移除最后一个"&"字符
        }
        return sb.toString();
    }

    public String getUid(Integer length, Integer bound) {
        Random random = new Random();
        char[] characters = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
        StringBuilder uid = new StringBuilder();

        if (length != null) {
            for (int i = 0; i < length; i++) {
                uid.append(characters[random.nextInt(bound != null ? bound : characters.length)]);
            }
        }

        return uid.toString();
    }
}
