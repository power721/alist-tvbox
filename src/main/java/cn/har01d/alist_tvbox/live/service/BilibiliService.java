package cn.har01d.alist_tvbox.live.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.live.model.BilibiliCategoriesResponse;
import cn.har01d.alist_tvbox.live.model.BilibiliCategory;
import cn.har01d.alist_tvbox.live.model.BilibiliRoomPlayInfo;
import cn.har01d.alist_tvbox.live.model.BilibiliRoomPlayResponse;
import cn.har01d.alist_tvbox.live.model.BilibiliRoomResponse;
import cn.har01d.alist_tvbox.live.model.BilibiliRoomsResponse;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.util.Constants;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static cn.har01d.alist_tvbox.util.Constants.FOLDER;

@Slf4j
@Service
public class BilibiliService implements LivePlatform {
    private final Map<String, String> userMap = new HashMap<>();
    private final Map<String, List<BilibiliCategory>> categoryMap = new HashMap<>();
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;

    public BilibiliService(RestTemplateBuilder builder, AppProperties appProperties) {
        this.restTemplate = builder
                .defaultHeader("User-Agent", Constants.MOBILE_USER_AGENT)
                .build();
        this.appProperties = appProperties;
    }

    @Override
    public String getType() {
        return "bili";
    }

    @Override
    public String getName() {
        return "B站";
    }

    @Override
    public MovieList home() throws IOException {
        return null;
    }

    @Override
    public CategoryList category() throws IOException {
        CategoryList result = new CategoryList();
        List<Category> list = new ArrayList<>();

        String url = "https://api.live.bilibili.com/xlive/web-interface/v1/index/getWebAreaList?source_id=2";
        var response = restTemplate.getForObject(url, BilibiliCategoriesResponse.class);

        for (var data : response.getData().getData()) {
            categoryMap.put(String.valueOf(data.getId()), data.getList());
            Category category = new Category();
            category.setType_id(getType() + "-" + data.getId());
            category.setType_name(data.getName());
            category.setType_flag(0);
            category.setCover("/bilibili.jpg");
            list.add(category);
        }

        result.setCategories(list);
        result.setTotal(result.getCategories().size());
        result.setLimit(result.getCategories().size());

        log.debug("category result: {}", result);
        return result;
    }

    @Override
    public MovieList list(String tid, String sort, Integer pg) throws IOException {
        String[] parts = tid.split("-");

        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        if (parts.length == 2) {
            if (categoryMap.isEmpty()) {
                category();
            }

            String id = parts[1];
            for (var item : categoryMap.get(id)) {
                MovieDetail detail = new MovieDetail();
                detail.setVod_id(tid + "-" + item.getId());
                detail.setVod_name(item.getName());
                detail.setVod_pic(fixCover(item.getPic()));
                detail.setVod_remarks(item.getParent_name());
                detail.setVod_tag(FOLDER);
                list.add(detail);
            }
        } else {
            String pid = parts[1];
            String id = parts[2];
            String url = "https://api.live.bilibili.com/xlive/web-interface/v1/second/getList?platform=web&parent_area_id=" + pid + "&area_id=" + id + "&sort_type=&page=" + pg;
            log.debug("url: {}", url);
            var response = restTemplate.getForObject(url, BilibiliRoomsResponse.class);
            for (var room : response.getData().getList()) {
                MovieDetail detail = new MovieDetail();
                detail.setVod_id(getType() + "$" + room.getRoomid());
                detail.setVod_name(room.getTitle());
                detail.setVod_pic(fixCover(room.getCover()));
                detail.setVod_remarks(room.getUname());
                userMap.put(String.valueOf(room.getRoomid()), room.getUname());
                list.add(detail);
            }
            if (list.size() < 40) {
                result.setPagecount(pg);
            } else {
                result.setPagecount(pg + 1);
            }
        }

        result.setList(list);
        result.setPage(pg);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());

        log.debug("list result: {}", result);
        return result;
    }

    @Override
    public MovieList search(String wd) throws IOException {
        return null;
    }

    @Override
    public MovieList detail(String tid) throws IOException {
        String[] parts = tid.split("\\$");
        String id = parts[1];
        MovieList result = new MovieList();
        String url = "https://api.live.bilibili.com/room/v1/Room/get_info?room_id=" + id;
        var response = restTemplate.getForObject(url, BilibiliRoomResponse.class);
        var room = response.getData();
        MovieDetail detail = new MovieDetail();
        detail.setVod_id(tid);
        detail.setVod_name(room.getTitle());
        detail.setVod_pic(fixCover(room.getCover()));
        detail.setVod_actor(userMap.get(id));
        detail.setType_name(room.getArea_name());
        detail.setVod_remarks(playCount(room.getOnline()));
        parseUrl(detail, id);
        result.getList().add(detail);

        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        log.debug("detail: {}", result);
        return result;
    }

    private void parseUrl(MovieDetail movieDetail, String id) throws IOException {
        //id = getRealRoomId(id);
        List<String> playFrom = new ArrayList<>();
        List<String> playUrl = new ArrayList<>();

        String url = "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo?protocol=0,1&format=0,1,2&codec=0,1&platform=web&room_id=" + id;
        var response = restTemplate.getForObject(url, BilibiliRoomPlayResponse.class);

        int count = 1;
        var streams = response.getData().getPlayurl_info().getPlayurl().getStream();
        Map<Integer, String> map = new HashMap<>();
        for (var qn : response.getData().getPlayurl_info().getPlayurl().getG_qn_desc()) {
            map.put(qn.getQn(), qn.getDesc());
        }

        for (var stream : streams) {
            playFrom.add("线路" + count++);
            List<String> urls = new ArrayList<>();
            for (var format : stream.getFormat()) {
                for (var codec : format.getCodec()) {
                    String baseUrl = codec.getBase_url();
                    int i = 1;
                    List<BilibiliRoomPlayInfo.UrlInfo> list = new ArrayList<>(codec.getUrl_info());
                    Collections.reverse(list);
                    for (var urlInfo : list) {
                        url = urlInfo.getHost() + baseUrl + urlInfo.getExtra();
                        urls.add(map.get(codec.getCurrent_qn()) + "-" + format.getFormat_name() + "-" + codec.getCodec_name() + "-" + i + "$" + url);
                        i++;
                    }
                }
            }
            playUrl.add(String.join("#", urls));
        }

        movieDetail.setVod_play_from(String.join("$$$", playFrom));
        movieDetail.setVod_play_url(String.join("$$$", playUrl));
    }

    private String getRealRoomId(String roomId) {
        ObjectNode response = restTemplate.getForObject("https://api.live.bilibili.com/xlive/web-room/v1/index/getH5InfoByRoom?room_id=" + roomId, ObjectNode.class);
        if (response.get("code").asInt() == 0) {
            return response.get("data").get("room_info").get("room_id").asText();
        }
        return roomId;
    }

    private String fixCover(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        // nginx https
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .scheme(appProperties.isEnableHttps() && !Utils.isLocalAddress() ? "https" : "http") // nginx https
                .replacePath("/images")
                .replaceQuery("url=" + url)
                .build()
                .toUriString();
    }
}
