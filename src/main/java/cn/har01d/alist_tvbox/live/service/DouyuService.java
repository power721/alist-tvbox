package cn.har01d.alist_tvbox.live.service;

import cn.har01d.alist_tvbox.live.model.DouyuCategoryResponse;
import cn.har01d.alist_tvbox.live.model.DouyuRoomResponse;
import cn.har01d.alist_tvbox.live.model.DouyuRoomsResponse;
import cn.har01d.alist_tvbox.live.model.DouyuStreamResponse;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.util.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.StringEscapeUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class DouyuService implements LivePlatform {
    private final Map<String, String> categoryMap = new HashMap<>();
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public DouyuService(RestTemplateBuilder builder, ObjectMapper objectMapper) {
        this.restTemplate = builder
                .defaultHeader("User-Agent", Constants.MOBILE_USER_AGENT)
                .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public String getType() {
        return "douyu";
    }

    @Override
    public String getName() {
        return "斗鱼";
    }

    @Override
    public MovieList home() throws IOException {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        for (int i = 0; i < 6; i++) {
            MovieList temp = list("", i);
            list.addAll(temp.getList());
            if (temp.getList().size() < 8) {
                break;
            }
        }

        result.setList(list);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());

        log.debug("home result: {}", result);
        return result;
    }

    @Override
    public CategoryList category() throws IOException {
        CategoryList result = new CategoryList();
        List<Category> list = new ArrayList<>();

        String url = "https://m.douyu.com/api/cate/list";
        var response = restTemplate.getForObject(url, DouyuCategoryResponse.class);

        for (var item : response.getData().getCate2Info()) {
            Category category = new Category();
            category.setType_id(getType() + "-" + item.getCate2Id());
            category.setType_name(item.getCate2Name());
            category.setType_flag(0);
            category.setCover(item.getPic());
            categoryMap.put(category.getType_id(), item.getShortName());
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
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        if (categoryMap.isEmpty()) {
            category();
        }

        int size = 6;
        int start = (pg - 1) * size + 1;
        int end = start + size;
        for (int i = start; i < end; i++) {
            MovieList temp = list(categoryMap.get(id), i);
            list.addAll(temp.getList());
            if (temp.getList().size() < 8) {
                break;
            }
            result.setPagecount((temp.getPagecount() + size - 1) / size);
        }

        result.setList(list);
        result.setPage(pg);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());

        log.debug("list result: {}", result);
        return result;
    }

    private MovieList list(String type, int pg) {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        String url = "https://m.douyu.com/api/room/list?page=" + pg + "&type=" + type;
        var response = restTemplate.getForObject(url, DouyuRoomsResponse.class);
        for (var room : response.getData().getList()) {
            MovieDetail detail = new MovieDetail();
            detail.setVod_id(getType() + "$" + room.getRid());
            detail.setVod_name(room.getRoomName());
            detail.setVod_pic(room.getRoomSrc());
            detail.setVod_remarks(room.getNickname());
            list.add(detail);
        }
        result.setList(list);
        result.setPagecount(response.getData().getPageCount());
        return result;
    }

    @Override
    public MovieList search(String wd) throws IOException {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        var response = restTemplate.postForObject("https://m.douyu.com/api/search/anchor?offset=0&limit=30&sk=" + wd, null, DouyuRoomsResponse.class);
        for (var room : response.getData().getList()) {
            MovieDetail detail = new MovieDetail();
            detail.setVod_id(getType() + "$" + room.getRoomId());
            detail.setVod_name(room.getRoomName());
            detail.setVod_pic(room.getRoomSrc());
            detail.setVod_remarks(room.getNickname());
            list.add(detail);
        }

        result.setList(list);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());

        log.debug("search result: {}", result);
        return result;
    }

    @Override
    public MovieList detail(String tid) throws IOException {
        String[] parts = tid.split("\\$");
        String id = parts[1];
        MovieList result = new MovieList();
        String url = "http://open.douyucdn.cn/api/RoomApi/room/" + id;
        var response = restTemplate.getForObject(url, DouyuRoomResponse.class);
        var room = response.getData();
        MovieDetail detail = new MovieDetail();
        detail.setVod_id(tid);
        detail.setVod_name(room.getRoom_name());
        detail.setVod_pic(room.getRoom_thumb());
        detail.setVod_actor(room.getOwner_name());
        detail.setType_name(room.getCate_name());
        detail.setVod_remarks(playCount(room.getOnline()));
        parseUrl(detail, id);
        result.getList().add(detail);

        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        log.debug("detail: {}", result);
        return result;
    }

    private void parseUrl(MovieDetail movieDetail, String id) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Referer", "https://www.douyu.com/" + id);
        String url = "https://www.douyu.com/swf_api/homeH5Enc?rids=" + id;
        String html = restTemplate.getForObject(url, String.class);
        ObjectNode node = objectMapper.readValue(html, ObjectNode.class);
        node = (ObjectNode) node.get("data");
        String crptext = node.get("room" + id).asText();
        String data = getPlayArgs(crptext, id);
        String dataUse = data + "&cdn=&rate=-1&ver=Douyu_223061205&iar=1&ive=1&hevc=0&fa=0";
        url = "https://www.douyu.com/lapi/live/getH5Play/" + id;

        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<String> request = new HttpEntity<>(dataUse, headers);
        ResponseEntity<DouyuStreamResponse> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                request,
                DouyuStreamResponse.class
        );
        log.debug("{}", response.getBody());

        List<String> playFrom = new ArrayList<>();
        List<String> playUrl = new ArrayList<>();

        var stream = response.getBody().getData();
        for (var cdn : stream.getCdnsWithName()) {
            playFrom.add(cdn.getName());
            List<String> urls = new ArrayList<>();
            for (var bitRate : stream.getMultirates()) {
                url = getPlayUrl(id, dataUse, bitRate.getRate(), cdn.getCdn());
                urls.add(bitRate.getName() + "$" + url);
            }
            playUrl.add(String.join("#", urls));
        }

        movieDetail.setVod_play_from(String.join("$$$", playFrom));
        movieDetail.setVod_play_url(String.join("$$$", playUrl));
    }

    private String getPlayUrl(String id, String args, int rate, String cdn) {
        args += "&cdn=" + cdn + "&rate=" + rate;
        String url = "https://www.douyu.com/lapi/live/getH5Play/" + id;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("Referer", "https://www.douyu.com/" + id);
        HttpEntity<String> request = new HttpEntity<>(args, headers);
        ResponseEntity<ObjectNode> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                request,
                ObjectNode.class
        );

        ObjectNode data = (ObjectNode) response.getBody().get("data");
        String rtmpUrl = data.get("rtmp_url").asText();
        String rtmpLive = data.get("rtmp_live").asText();
        rtmpLive = StringEscapeUtils.unescapeHtml4(rtmpLive);
        return rtmpUrl + "/" + rtmpLive;
    }

    private final Pattern pattern = Pattern.compile("(vdwdae325w_64we[\\s\\S]*?function ub98484234[\\s\\S]*?)function");

    private String getPlayArgs(String crptext, String realRoomId) {
        try {
            Matcher matcher = pattern.matcher(crptext);
            if (matcher.find()) {
                crptext = matcher.group(1);
            } else {
                return "";
            }

            String regex1 = "eval.*?;}";
            String replacement = "strc;}";
            crptext = crptext.replaceAll(regex1, replacement);
            crptext = crptext.replaceAll("\"", "\\\"");
            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("html", crptext);
            requestBody.put("rid", realRoomId);
            ObjectNode result = restTemplate.postForObject("http://alive.nsapps.cn/api/AllLive/DouyuSign", requestBody, ObjectNode.class);
            return result.get("data").asText();
        } catch (Exception e) {
            log.error("斗鱼---getPlayArgs异常", e);
        }
        return "";
    }

}
