package cn.har01d.alist_tvbox.live.service;

import cn.har01d.alist_tvbox.live.model.CcCategoryResponse;
import cn.har01d.alist_tvbox.live.model.CcPlayInfo;
import cn.har01d.alist_tvbox.live.model.CcRoomList;
import cn.har01d.alist_tvbox.live.model.CcRoomsResponse;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.util.Constants;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class CcService implements LivePlatform {
    private final Cache<String, MovieList> cache = Caffeine.newBuilder()
            .maximumSize(20)
            .expireAfterWrite(Duration.ofMinutes(15))
            .build();
    private final RestTemplate restTemplate;

    public CcService(RestTemplateBuilder builder) {
        this.restTemplate = builder
                .defaultHeader("User-Agent", Constants.MOBILE_USER_AGENT)
                .build();
    }

    @Override
    public String getType() {
        return "cc";
    }

    @Override
    public String getName() {
        return "网易";
    }

    @Override
    public MovieList home() throws IOException {
        return null;
    }

    @Override
    public CategoryList category() throws IOException {
        CategoryList result = new CategoryList();
        List<Category> list = new ArrayList<>();

        String url = "https://api.cc.163.com/v1/wapcc/gamecategory?catetype=0";
        var response = restTemplate.getForObject(url, CcCategoryResponse.class);

        for (var item : response.getData().getCategory_info().getGame_list()) {
            Category category = new Category();
            category.setType_id(getType() + "-" + item.getGametype());
            category.setType_name(item.getName());
            category.setType_flag(0);
            category.setCover(item.getCover());
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

        String url = "https://cc.163.com/api/category/" + gid + "/?format=json&tag_id=0&start=0&size=120";
        var response = restTemplate.getForObject(url, CcRoomList.class);
        for (var room : response.getLives()) {
            MovieDetail detail = new MovieDetail();
            detail.setVod_id(getType() + "$" + room.getCuteid());
            detail.setVod_name(room.getTitle());
            detail.setVod_pic(room.getPoster());
            detail.setVod_remarks(room.getNickname());
            list.add(detail);
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
        MovieList result = cache.getIfPresent(tid);
        if (result != null) {
            return result;
        }

        String[] parts = tid.split("\\$");
        String id = parts[1];
        result = new MovieList();
        String url = "https://api.cc.163.com/v1/activitylives/anchor/lives?anchor_ccid=" + id;
        var json = restTemplate.getForObject(url, ObjectNode.class);
        String cid = json.get("data").get(id).get("channel_id").asText();
        url = "https://cc.163.com/live/channel/?channelids=" + cid;
        var response = restTemplate.getForObject(url, CcRoomsResponse.class);
        var room = response.getData().get(0);
        MovieDetail detail = new MovieDetail();
        detail.setVod_id(tid);
        detail.setVod_name(room.getTitle());
        detail.setVod_pic(room.getPoster());
        detail.setVod_actor(room.getNickname());
        detail.setType_name(room.getGamename());
        detail.setVod_remarks(playCount(room.getVisitor()));
        detail.setVod_play_from("线路1");
        detail.setVod_play_url("原画$" + room.getSharefile());
        parseUrl(detail, id);
        result.getList().add(detail);

        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        log.debug("detail: {}", result);
        cache.put(tid, result);
        return result;
    }

    private void parseUrl(MovieDetail detail, String id) {
        String url = "https://vapi.cc.163.com/video_play_url/" + id;
        var response = restTemplate.getForObject(url, CcPlayInfo.class);

        List<String> playFrom = new ArrayList<>();
        List<String> playUrl = new ArrayList<>();
        Map<Integer, String> map = new HashMap<>();
        response.getTcvbr_list().forEach((name, vbr) -> {
            map.put(vbr, name);
        });

        for (var cdn : response.getCdn_list()) {
            playFrom.add(cdn);
            List<String> urls = new ArrayList<>();
            for (var vbr : response.getVbr_list()) {
                url = "https://vapi.cc.163.com/video_play_url/" + id + "?cdn=" + cdn + "&vbr=" + vbr;
                var play = restTemplate.getForObject(url, CcPlayInfo.class);
                urls.add(response.getVbrname_mapping().get(map.get(vbr)) + "$" + play.getVideourl());
            }
            playUrl.add(String.join("#", urls));
        }

        detail.setVod_play_from(String.join("$$$", playFrom));
        detail.setVod_play_url(String.join("$$$", playUrl));
    }
}
