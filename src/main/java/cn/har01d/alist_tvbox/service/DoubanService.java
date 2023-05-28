package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.DoubanData;
import cn.har01d.alist_tvbox.dto.DoubanDto;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.util.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class DoubanService {

    private static final Pattern DB_URL = Pattern.compile("https://movie.douban.com/subject/(\\d+)");

    private final RestTemplate restTemplate;
    LoadingCache<String, DoubanData> cache = Caffeine.newBuilder()
            .maximumSize(100)
            .build(this::load);

    public DoubanService(RestTemplateBuilder builder) {
        this.restTemplate = builder
                .defaultHeader(HttpHeaders.ACCEPT, Constants.ACCEPT)
                .defaultHeader(HttpHeaders.USER_AGENT, Constants.USER_AGENT)
                .build();
    }

    public DoubanData getDataFromUrl(String url) {
        if (StringUtils.isNotEmpty(url)) {
            Matcher matcher = DB_URL.matcher(url);
            if (matcher.find()) {
                String id = matcher.group(1);
                try {
                    return getById(id);
                } catch (Exception e) {
                    log.warn("", e);
                }
            }
        }

        return null;
    }

    public DoubanData getById(String id) {
        return cache.get(id);
    }

    public DoubanData load(String id) {
        String url = "https://api.wmdb.tv/movie/api?id=" + id;
        log.info("get douban info from {}", url);
        DoubanDto dto = restTemplate.getForObject(url, DoubanDto.class);
        if (dto == null || dto.getData() == null || dto.getData().isEmpty()) {
            return null;
        }

        DoubanData data = dto.getData().get(0);
        data.setEpisodes(dto.getEpisodes());
        data.setYear(dto.getYear());
        log.info("douban info: {}", data);
        return data;
    }

    public List<MovieDetail> getHotRank() {
        List<MovieDetail> list = new ArrayList<>();
        Map<String, Object> request = new HashMap<>();
        request.put("pageNum", 0);
        request.put("pageSize", 100);
        try {
            JsonNode response = restTemplate.postForObject("https://pbaccess.video.qq.com/trpc.videosearch.hot_rank.HotRankServantHttp/HotRankHttp", request, JsonNode.class);
            ArrayNode arrayNode = (ArrayNode) response.path("data").path("navItemList").path(0).path("hotRankResult").path("rankItemList");
            for (JsonNode node : arrayNode) {
                MovieDetail detail = new MovieDetail();
                detail.setVod_name(node.get("title").asText());
                detail.setVod_id("msearch:" + detail.getVod_name());
                detail.setVod_pic("https://avatars.githubusercontent.com/u/97389433?s=120&v=4");
                detail.setVod_remarks(node.get("changeOrder").asText());
                list.add(detail);
            }
        } catch (Exception e) {
            log.warn("", e);
        }

        return list;
    }
}
