package cn.har01d.alist_tvbox.live.service;

import cn.har01d.alist_tvbox.live.model.*;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.util.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class KuaishouService implements LivePlatform {
    private final Map<String, String> categoryMap = new HashMap<>();
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private static final String CATEGORY_API = "https://live.kuaishou.com/live_api/category/data";
    private static final String GAME_BOARD_API = "https://live.kuaishou.com/live_api/gameboard/list";
    private static final String NON_GAME_BOARD_API = "https://live.kuaishou.com/live_api/non-gameboard/list";
    private static final String HOME_LIST_API = "https://live.kuaishou.com/live_api/home/list";
    private static final String ROOM_PAGE_API = "https://live.kuaishou.com/u/";

    private static final List<String> IMAGE_EXTENSIONS = Arrays.asList(
            "svgz", "pjp", "png", "ico", "avif", "tiff", "tif", "jfif",
            "svg", "xbm", "pjpeg", "webp", "jpg", "jpeg", "bmp", "gif"
    );

    public KuaishouService(RestTemplateBuilder builder, ObjectMapper objectMapper) {
        this.restTemplate = builder
                .defaultHeader("User-Agent", Constants.USER_AGENT)
                .defaultHeader("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3")
                .defaultHeader("connection", "keep-alive")
                .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public String getType() {
        return "ks";
    }

    @Override
    public String getName() {
        return "快手";
    }

    @Override
    public MovieList home() throws IOException {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        try {
            String url = HOME_LIST_API;
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(createHeaders()),
                    String.class
            );

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode dataList = root.path("data").path("list");

            for (JsonNode item : dataList) {
                JsonNode gameLiveInfoList = item.path("gameLiveInfo");
                for (JsonNode sitem : gameLiveInfoList) {
                    JsonNode liveInfoList = sitem.path("liveInfo");
                    for (JsonNode titem : liveInfoList) {
                        MovieDetail detail = new MovieDetail();
                        JsonNode author = titem.path("author");
                        JsonNode gameInfo = titem.path("gameInfo");

                        detail.setVod_id(getType() + "$" + author.path("id").asText());
                        detail.setVod_name(author.path("name").asText());
                        detail.setVod_pic(gameInfo.path("poster").asText());
                        detail.setVod_remarks(playCount(titem.path("watchingCount").asInt()));
                        list.add(detail);

                        if (list.size() >= 30) {
                            break;
                        }
                    }
                    if (list.size() >= 30) {
                        break;
                    }
                }
                if (list.size() >= 30) {
                    break;
                }
            }
        } catch (Exception e) {
            log.error("快手首页获取失败", e);
        }

        result.setList(list);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());

        log.debug("快手home result: {}", result);
        return result;
    }

    @Override
    public CategoryList category() throws IOException {
        CategoryList result = new CategoryList();
        List<Category> list = new ArrayList<>();

        String[][] categories = {
                {"1", "热门"},
                {"2", "网游"},
                {"3", "单机"},
                {"4", "手游"},
                {"5", "棋牌"},
                {"6", "娱乐"},
                {"7", "综合"},
                {"8", "文化"}
        };

        for (String[] cat : categories) {
            String catId = cat[0];
            String catName = cat[1];

            try {
                int page = 1;
                int pageSize = 30;
                String url = CATEGORY_API + "?type=" + catId + "&page=" + page + "&size=" + pageSize;

                ResponseEntity<String> response = restTemplate.exchange(
                        url,
                        HttpMethod.GET,
                        new HttpEntity<>(createHeaders()),
                        String.class
                );

                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode subList = root.path("data").path("list");

                for (JsonNode item : subList) {
                    String subId = item.path("id").asText();
                    String subName = item.path("name").asText();
                    String poster = item.path("poster").asText();

                    Category category = new Category();
                    category.setType_id(getType() + "-" + subId);
                    category.setType_name(catName + " - " + subName);
                    category.setType_flag(0);
                    category.setCover(poster);
                    categoryMap.put(category.getType_id(), subId);
                    list.add(category);
                }
            } catch (Exception e) {
                log.error("快手分类获取失败: {}", catName, e);
            }
        }

        result.setCategories(list);
        result.setTotal(result.getCategories().size());
        result.setLimit(result.getCategories().size());

        log.debug("快手category result: {}", result);
        return result;
    }

    @Override
    public MovieList list(String id, String sort, Integer pg) throws IOException {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        if (categoryMap.isEmpty()) {
            category();
        }

        String gameId = categoryMap.get(id);
        if (gameId == null) {
            return result;
        }

        try {
            boolean isGameBoard = gameId.length() < 7;
            String apiUrl = isGameBoard ? GAME_BOARD_API : NON_GAME_BOARD_API;
            String url = apiUrl + "?filterType=0&pageSize=30&gameId=" + gameId + "&page=" + pg;

            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(createHeaders()),
                    String.class
            );

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode roomList = root.path("data").path("list");

            for (JsonNode item : roomList) {
                JsonNode author = item.path("author");
                JsonNode gameInfo = item.path("gameInfo");

                MovieDetail detail = new MovieDetail();
                detail.setVod_id(getType() + "$" + author.path("id").asText());
                detail.setVod_name(item.path("caption").asText());
                String poster = item.path("poster").asText();
                detail.setVod_pic(isImage(poster) ? poster : poster + ".jpg");
                detail.setVod_remarks(author.path("name").asText() + " - " + playCount(item.path("watchingCount").asInt()));
                list.add(detail);
            }
        } catch (Exception e) {
            log.error("快手房间列表获取失败: {}", id, e);
        }

        result.setList(list);
        result.setPage(pg);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        result.setPagecount(100);

        log.debug("快手list result: {}", result);
        return result;
    }

    @Override
    public MovieList search(String wd) throws IOException {
        // 快手无法搜索主播，只能搜索游戏分类
        return new MovieList();
    }

    @Override
    public MovieList detail(String tid, String client) throws IOException {
        String[] parts = tid.split("\\$");
        String roomId = parts[1];

        MovieList result = new MovieList();
        MovieDetail detail = new MovieDetail();
        detail.setVod_id(tid);

        try {
            String url = ROOM_PAGE_API + roomId;
            HttpHeaders headers = createHeaders();
            headers.set("User-Agent", getRandomUserAgent());

            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class
            );

            String html = response.getBody();
            Pattern pattern = Pattern.compile("window\\.__INITIAL_STATE__=(.*?);", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(html);

            if (matcher.find()) {
                String jsonText = matcher.group(1).replace("undefined", "null");
                JsonNode jsonObj = objectMapper.readTree(jsonText);

                JsonNode playList = jsonObj.path("liveroom").path("playList");
                if (playList.isArray() && playList.size() > 0) {
                    JsonNode firstPlay = playList.get(0);
                    JsonNode liveStream = firstPlay.path("liveStream");
                    JsonNode author = firstPlay.path("author");
                    JsonNode gameInfo = firstPlay.path("gameInfo");
                    boolean isLiving = firstPlay.path("isLiving").asBoolean();

                    detail.setVod_name(author.path("name").asText());
                    detail.setVod_pic(isImage(liveStream.path("poster").asText()) ?
                            liveStream.path("poster").asText() :
                            liveStream.path("poster").asText() + ".jpg");
                    detail.setVod_actor(author.path("name").asText());
                    detail.setType_name(gameInfo.path("name").asText());
                    detail.setVod_remarks(playCount(isLiving ? gameInfo.path("watchingCount").asInt() : 0));
                    detail.setVod_content(author.path("description").asText());

                    if (isLiving) {
                        JsonNode playUrls = liveStream.path("playUrls");
                        parsePlayUrls(detail, playUrls);
                    }
                }
            }
        } catch (Exception e) {
            log.error("快手房间详情获取失败: {}", roomId, e);
        }

        result.getList().add(detail);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());

        log.debug("快手detail: {}", result);
        return result;
    }

    private void parsePlayUrls(MovieDetail movieDetail, JsonNode playUrls) {
        try {
            List<String> playFrom = new ArrayList<>();
            List<String> playUrlList = new ArrayList<>();

            JsonNode adaptationSet = playUrls.path("h264").path("adaptationSet");
            JsonNode representations = adaptationSet.path("representation");

            if (representations.isArray()) {
                List<JsonNode> qualityList = new ArrayList<>();
                representations.forEach(qualityList::add);
                // 按level降序排序
                qualityList.sort((a, b) -> {
                    int levelA = a.path("level").asInt();
                    int levelB = b.path("level").asInt();
                    return Integer.compare(levelB, levelA);
                });

                List<String> urls = new ArrayList<>();
                for (JsonNode quality : qualityList) {
                    String name = quality.path("name").asText();
                    String url = quality.path("url").asText();
                    urls.add(name + "$" + url);
                }

                playFrom.add("原画");
                playUrlList.add(String.join("#", urls));
            }

            movieDetail.setVod_play_from(String.join("$$$", playFrom));
            movieDetail.setVod_play_url(String.join("$$$", playUrlList));
        } catch (Exception e) {
            log.error("快手播放URL解析失败", e);
        }
    }

    private boolean isImage(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String ext = url.substring(url.lastIndexOf('.') + 1).toLowerCase();
        return IMAGE_EXTENSIONS.contains(ext);
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", Constants.USER_AGENT);
        headers.set("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3");
        headers.set("connection", "keep-alive");
        headers.set("sec-ch-ua", "Google Chrome;v=107, Chromium;v=107, Not=A?Brand;v=24");
        headers.set("sec-ch-ua-platform", "macOS");
        headers.set("Sec-Fetch-Dest", "document");
        headers.set("Sec-Fetch-Mode", "navigate");
        headers.set("Sec-Fetch-Site", "same-origin");
        headers.set("Sec-Fetch-User", "?1");
        return headers;
    }

    private String getRandomUserAgent() {
        String[] userAgents = {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/107.0.0.0 Safari/537.36",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/107.0.0.0 Safari/537.36",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/106.0.0.0 Safari/537.36"
        };
        return userAgents[new Random().nextInt(userAgents.length)];
    }
}
