package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.bili.BiliBiliFeedResponse;
import cn.har01d.alist_tvbox.dto.bili.BiliBiliHistoryResponse;
import cn.har01d.alist_tvbox.dto.bili.BiliBiliHistoryResult;
import cn.har01d.alist_tvbox.dto.bili.BiliBiliHotResponse;
import cn.har01d.alist_tvbox.dto.bili.BiliBiliInfo;
import cn.har01d.alist_tvbox.dto.bili.BiliBiliInfoResponse;
import cn.har01d.alist_tvbox.dto.bili.BiliBiliSearchResponse;
import cn.har01d.alist_tvbox.dto.bili.BiliBiliSearchResult;
import cn.har01d.alist_tvbox.dto.bili.BiliBiliSeasonInfo;
import cn.har01d.alist_tvbox.dto.bili.BiliBiliSeasonResponse;
import cn.har01d.alist_tvbox.dto.bili.BiliBiliTokenResponse;
import cn.har01d.alist_tvbox.dto.bili.BiliBiliVideoInfo;
import cn.har01d.alist_tvbox.dto.bili.BiliBiliVideoInfoResponse;
import cn.har01d.alist_tvbox.dto.bili.Resp;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.model.Filter;
import cn.har01d.alist_tvbox.model.FilterValue;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.util.Constants;
import cn.har01d.alist_tvbox.util.DashUtils;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static cn.har01d.alist_tvbox.util.Constants.BILIBILI_COOKIE;
import static cn.har01d.alist_tvbox.util.Constants.BILI_BILI;
import static cn.har01d.alist_tvbox.util.Constants.COLLECTION;
import static cn.har01d.alist_tvbox.util.Constants.FILE;
import static cn.har01d.alist_tvbox.util.Constants.LIST_PIC;

@Slf4j
@Service
public class BiliBiliService {
    private static final String INFO_API = "https://api.bilibili.com/x/web-interface/view?bvid=";
    private static final String HOT_API = "https://api.bilibili.com/x/web-interface/ranking/v2?type=%s&rid=%d";
    private static final String LIST_API = "https://api.bilibili.com/x/web-interface/newlist_rank?main_ver=v3&search_type=video&view_type=hot_rank&copy_right=-1&new_web_tag=1&order=click&cate_id=%s&page=%d&pagesize=30&time_from=%s&time_to=%s";
    private static final String SEASON_API = "https://api.bilibili.com/pgc/season/rank/web/list?day=3&season_type=%d";
    private static final String HISTORY_API = "https://api.bilibili.com/x/web-interface/history/cursor?ps=30&type=archive&business=archive&max=%s";
    private static final String PLAY_API1 = "https://api.bilibili.com/pgc/player/web/playurl?bvid=%s&cid=%s&qn=&type=&otype=json&fourk=1&fnver=0&fnval=4048";
    private static final String PLAY_API = "https://api.bilibili.com/x/player/playurl?bvid=%s&cid=%s&qn=&type=&otype=json&fourk=1&fnver=0&fnval=4048";
    private static final String PLAY_API2 = "https://api.bilibili.com/x/player/playurl?bvid=%s&cid=%s&qn=127&platform=html5&high_quality=1";

    private static final String TOKEN_API = "https://api.bilibili.com/x/player/playurl/token?%said=%d&cid=%d";
    private static final String POPULAR_API = "https://api.bilibili.com/x/web-interface/popular?ps=30&pn=";
    private static final String SEARCH_API = "https://api.bilibili.com/x/web-interface/search/type?jsonp=jsonp&search_type=video&highlight=1&page_size=50&keyword=%s&order=%s&page=%d";
    private static final String TOP_FEED_API = "https://api.bilibili.com/x/web-interface/wbi/index/top/feed/rcmd";
    public static final String NAV_API = "https://api.bilibili.com/x/web-interface/nav";

    private final List<FilterValue> filters = Arrays.asList(
            new FilterValue("综合排序", ""),
            new FilterValue("最多播放", "click"),
            new FilterValue("最新发布", "pubdate"),
            new FilterValue("最多弹幕", "dm"),
            new FilterValue("最多收藏️", "stow")
    );
    private final Map<String, List<FilterValue>> filterMap = new HashMap<>();
    private final SettingRepository settingRepository;
    private final SubscriptionService subscriptionService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private List<Setting> types = new ArrayList<>();
    private MovieDetail searchPlaylist;
    private String keyword = "";
    private int searchPage;
    private LocalDateTime keyTime = LocalDateTime.now();

    public BiliBiliService(SettingRepository settingRepository,
                           SubscriptionService subscriptionService,
                           RestTemplateBuilder builder,
                           ObjectMapper objectMapper) {
        this.settingRepository = settingRepository;
        this.subscriptionService = subscriptionService;
        this.restTemplate = builder
                .defaultHeader("Referer", "https://www.bilibili.com/")
                .defaultHeader(HttpHeaders.USER_AGENT, Constants.USER_AGENT)
                .build();
        this.objectMapper = objectMapper;

    }

    private void addType(String name, String value) {
        types.add(new Setting(name, value));
    }

    private void addType(String name, String value, String filters) {
        types.add(new Setting(name, value));
        filterMap.put(name, Arrays.asList(filters.split("&")).stream().map(e -> {
            String[] parts = e.split("=");
            return new FilterValue(parts[0], parts.length == 1 ? "" : parts[1]);
        }).collect(Collectors.toList()));
    }

    private List<Setting> getTypes() {
        types = new ArrayList<>();
        if (settingRepository.findById("bilibili_history").map(Setting::getValue).orElse("").equals("true")) {
            addType("历史记录", "history$0");
        }
        addType("全站", "0"); // https://api.bilibili.com/x/web-interface/ranking/v2?rid=0&type=all
        addType("热门", "pop$1");
        addType("科技", "188", "主分区=&数码=95&软件应用=230&计算机技术=231&科工机械=232&极客DIY=233");
        addType("知识", "36", "主分区=&科学科普=201&社科·法律·心理=124&人文历史=228&财经商业=207&校园学习=208&职业职场=209&设计·创意=229");
        addType("动画", "1", "主分区=&MAD·AMV=24&MMD·3D=25&短片·手书·配音=47&手办·模玩=210&特摄=86&动漫杂谈=253&综合=27");
        addType("音乐", "3", "主分区=");
        addType("游戏", "4", "主分区=");
        addType("娱乐", "5", "主分区=");
        addType("影视", "181", "主分区=");
        addType("舞蹈", "129", "主分区=");
        addType("运动", "234", "主分区=");
        addType("汽车", "223", "主分区=");
        addType("生活", "160", "主分区=");
        addType("美食", "211", "主分区=");
        addType("动物圈", "217", "主分区=");
        addType("时尚", "155", "主分区=");
        addType("鬼畜", "119", "主分区=");
        addType("国创相关", "168", "主分区=");
        addType("国产动画", "season$1"); // https://api.bilibili.com/pgc/web/rank/list?day=3&season_type=1
        addType("电影", "season$2"); // https://api.bilibili.com/pgc/season/rank/web/list?day=3&season_type=2
        addType("纪录片", "season$3"); // https://api.bilibili.com/pgc/season/rank/web/list?day=3&season_type=3
        addType("番剧", "season$4"); // https://api.bilibili.com/pgc/season/rank/web/list?day=3&season_type=4
        addType("电视剧", "season$5"); // https://api.bilibili.com/pgc/season/rank/web/list?day=3&season_type=5
        addType("综艺", "season$7"); // https://api.bilibili.com/pgc/season/rank/web/list?day=3&season_type=7
        addType("原创", "origin$0"); // https://api.bilibili.com/x/web-interface/ranking/v2?rid=0&type=origin
        addType("新人", "rookie$0"); // https://api.bilibili.com/x/web-interface/ranking/v2?rid=0&type=rookie
        return types;
    }

    public CategoryList getCategoryList() {
        CategoryList result = new CategoryList();
        try {
            Path file = Paths.get("/data/bilibili.txt");
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file)) {
                    String[] parts = line.split(":");
                    String name = parts[1];
                    if (parts.length == 3) {
                        name = parts[2];
                    }
                    Category category = new Category();
                    category.setType_id(parts[0] + ":" + parts[1]);
                    category.setType_name(name);
                    category.setType_flag(0);
                    result.getFilters().put(category.getType_id(), List.of(new Filter("sort", "排序", filters)));
                    result.getCategories().add(category);
                }
            }
        } catch (Exception e) {
            log.warn("", e);
        }

        for (Setting item : getTypes()) {
            Category category = new Category();
            category.setType_id(item.getValue());
            category.setType_name(item.getName());
            category.setType_flag(0);
            if (filterMap.containsKey(item.getName())) {
                result.getFilters().put(category.getType_id(), List.of(new Filter("type", "分类", filterMap.get(item.getName()))));
            }
            result.getCategories().add(category);
        }

//        List<MovieDetail> list = new ArrayList<>();
//        MovieDetail movieDetail = new MovieDetail();
//        movieDetail.setVod_id("recommend");
//        list.add(movieDetail);
//        result.setList(list);

        return result;
    }

    public MovieList recommend() {
        List<BiliBiliInfo> list = getTopFeed();
        MovieList result = new MovieList();
        for (BiliBiliInfo info : list) {
            MovieDetail movieDetail = getMovieDetail(info);
            result.getList().add(movieDetail);
        }

        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        return result;
    }

    private MovieDetail getMovieDetail(BiliBiliSeasonInfo info) {
        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id("season:" + info.getSeason_id());
        movieDetail.setVod_name(info.getTitle());
        movieDetail.setVod_tag(FILE);
        movieDetail.setType_name(info.getBadge());
        movieDetail.setVod_pic(info.getCover());
        movieDetail.setVod_play_from(BILI_BILI);
        movieDetail.setVod_play_url(buildPlayUrl(movieDetail.getVod_id()));
        movieDetail.setVod_remarks(info.getRating());
        movieDetail.setVod_content(info.getDesc() + ";\n" + info.getStat().getView() + "播放;\n" + info.getStat().getFollow() + "关注");
        return movieDetail;
    }

    private MovieDetail getMovieDetail(BiliBiliVideoInfo.Video info) {
        String id = info.getBvid();
        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(id);
        movieDetail.setVod_name(info.getTitle());
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_pic(fixUrl(info.getPic()));
        movieDetail.setVod_play_from(BILI_BILI);
        movieDetail.setVod_play_url(buildPlayUrl(id));
        movieDetail.setVod_remarks(seconds2String(info.getDuration()));
        movieDetail.setVod_content(info.getDescription());
        return movieDetail;
    }

    private MovieDetail getMovieDetail(BiliBiliHistoryResult.Video info) {
        String id = info.getHistory().getBvid();
        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(id);
        movieDetail.setVod_name(info.getTitle());
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_pic(fixUrl(info.getCover()));
        movieDetail.setVod_play_from(BILI_BILI);
        movieDetail.setVod_play_url(buildPlayUrl(id));
        movieDetail.setVod_remarks(seconds2String(info.getDuration()));
        return movieDetail;
    }

    private MovieDetail getMovieDetail(BiliBiliInfo info) {
        String id = info.getBvid();
        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(id);
        movieDetail.setVod_name(info.getTitle());
        movieDetail.setVod_tag(FILE);
        if (info.getOwner() != null) {
            movieDetail.setVod_director(info.getOwner().getName());
        }
        movieDetail.setType_name(info.getTname());
        movieDetail.setVod_time(Instant.ofEpochSecond(info.getPubdate()).toString());
        movieDetail.setVod_pic(info.getPic());
        movieDetail.setVod_play_from(BILI_BILI);
        movieDetail.setVod_play_url(buildPlayUrl(id));
        movieDetail.setVod_remarks(seconds2String(info.getDuration()));
        if (info.getStat() != null) {
            movieDetail.setVod_content(info.getDesc() + ";\n" + info.getStat().getView() + "播放;\n" + info.getStat().getLike() + "点赞");
        } else {
            movieDetail.setVod_content(info.getDesc());
        }
        return movieDetail;
    }

    private String seconds2String(long total) {
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long seconds = total % 60;

        if (hours > 0) {
            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%02d:%02d", minutes, seconds);
        } else {
            return String.format("0:%02d", seconds);
        }
    }

    public List<BiliBiliInfo> getPopular(int page) {
        BiliBiliHotResponse hotResponse = restTemplate.getForObject(POPULAR_API + page, BiliBiliHotResponse.class);
        return hotResponse.getData().getList();
    }

    public BiliBiliVideoInfo getRankList(String type, int page) {
        LocalDate now = LocalDate.now();
        String from = now.minusDays(7).toString().replace("-", "");
        String to = now.toString().replace("-", "");
        String url = String.format(LIST_API, type, page, from, to);
        BiliBiliVideoInfoResponse hotResponse = restTemplate.getForObject(url, BiliBiliVideoInfoResponse.class);
        return hotResponse.getData();
    }

    public List<BiliBiliInfo> getTopFeed() {
        Map<String, Object> map = new HashMap<>();
        map.put("web_location", "");
        map.put("y_num", "4");
        map.put("fresh_type", "4");
        map.put("feed_version", "V8");
        map.put("fresh_idx_1h", "1");
        map.put("fetch_row", "4");
        map.put("fresh_idx", "1");
        map.put("brush", "1");
        map.put("homepage_ver", "1");
        map.put("ps", "30");
        map.put("last_y_num", "5");

        HttpEntity<Void> entity = buildHttpEntity(null);
        Map<String, Object> json = restTemplate.exchange(NAV_API, HttpMethod.GET, entity, Map.class).getBody();
        Map<String, Object> data = (Map<String, Object>) json.get("data");
        Map<String, Object> wbi = (Map<String, Object>) data.get("wbi_img");
        String imgKey = getKey((String) wbi.get("img_url"));
        String subKey = getKey((String) wbi.get("sub_url"));
        String url = TOP_FEED_API + "?" + Utils.encryptWbi(map, imgKey, subKey);
        log.info("{}", url);

        entity = buildHttpEntity(null);
        ResponseEntity<BiliBiliFeedResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, BiliBiliFeedResponse.class);
        return response.getBody().getData().getItem();
    }

    private String getKey(String url) {
        int start = url.lastIndexOf('/') + 1;
        int end = url.lastIndexOf('.');
        return url.substring(start, end);
    }

    public List<BiliBiliInfo> getHotRank(String type, int rid) {
        BiliBiliHotResponse hotResponse = restTemplate.getForObject(String.format(HOT_API, type, rid), BiliBiliHotResponse.class);
        return hotResponse.getData().getList();
    }

    public MovieList getSeasonRank(int type) {
        String url = String.format(SEASON_API, type);
        HttpEntity<Void> entity = buildHttpEntity(null);
        ResponseEntity<BiliBiliSeasonResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, BiliBiliSeasonResponse.class);
        BiliBiliSeasonResponse hotResponse = response.getBody();
        MovieList result = new MovieList();
        for (BiliBiliSeasonInfo info : hotResponse.getData().getList()) {
            MovieDetail movieDetail = getMovieDetail(info);
            result.getList().add(movieDetail);
        }
        log.debug("getSeasonRank: {} {}", url, result);
        return result;
    }

    private List<String> ids = new ArrayList<>();

    public MovieList getHistory(int page) {
        if (page == 1) {
            ids = new ArrayList<>();
            ids.add("");
        }
        String url = String.format(HISTORY_API, ids.get(page - 1));
        HttpEntity<Void> entity = buildHttpEntity(null);
        ResponseEntity<BiliBiliHistoryResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, BiliBiliHistoryResponse.class);
        BiliBiliHistoryResponse hotResponse = response.getBody();
        MovieList result = new MovieList();
        for (BiliBiliHistoryResult.Video info : hotResponse.getData().getList()) {
            MovieDetail movieDetail = getMovieDetail(info);
            result.getList().add(movieDetail);
        }
        ids.add(String.valueOf(hotResponse.getData().getCursor().getMax()));
        result.setTotal(2000);
        result.setPagecount(50);
        log.debug("getHistory: {} {}", url, result);
        return result;
    }

    public MovieList getDetail(String bvid) throws IOException {
        if (bvid.startsWith(COLLECTION)) {
            return getPlaylist(bvid);
        }

        if (bvid.startsWith("season:")) {
            return getBangumi(bvid);
        }

        BiliBiliInfo info = getInfo(bvid);
        MovieList result = new MovieList();
        MovieDetail movieDetail = getMovieDetail(info);
        result.getList().add(movieDetail);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        log.debug("detail: {}", result);
        return result;
    }

    private static final Pattern SCRIPT = Pattern.compile("<script\\s+id=\"__NEXT_DATA__\"\\s+type=\"application/json\"\\s*>(.*?)</script\\s*>");
    private static final Pattern EP_MAP = Pattern.compile("\"epMap\"\\s*:\\s*(.+?)\\s*,\\s*\"initEpList\"");
    private static final Pattern MEDIA_INFO = Pattern.compile("\"mediaInfo\"\\s*:\\s*.+?\"title\":\"(.+?)\",\\s*.+?\\s*\"sectionsMap\"");
    private static final Pattern VIDEO_ID = Pattern.compile("\"videoId\"\\s*:\\s*\"(ep|ss)(\\d+)\"");

    private MovieList getBangumi(String tid) throws IOException {
        MovieList result = new MovieList();

        String[] parts = tid.split(":");
        String sid = parts[1];
        String url = "https://www.bilibili.com/bangumi/play/ss" + sid;
        HttpEntity<Void> entity = buildHttpEntity(null);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
        String html = response.getBody();
        Matcher m = SCRIPT.matcher(html);
        if (m.find()) {
            List<BiliBiliSeasonInfo> list = new ArrayList<>();
            String json = m.group(1);
            String title = "xxx";
            m = MEDIA_INFO.matcher(json);
            if (m.find()) {
                title = m.group(1);
            }
            m = EP_MAP.matcher(json);
            if (m.find()) {
                String data = m.group(1);
                Map<String, Object> map = objectMapper.readValue(data, Map.class);
                for (Map.Entry<String, Object> entry : map.entrySet()) {
                    data = objectMapper.writeValueAsString(entry.getValue());
                    log.info("{}", data);
                    BiliBiliSeasonInfo info = objectMapper.readValue(data, BiliBiliSeasonInfo.class);
                    info.setEpid(Integer.parseInt(entry.getKey()));
                    list.add(info);
                }
            }

            m = VIDEO_ID.matcher(json);
            if (m.find()) {
                String type = m.group(1);
                String videoId = m.group(2);
                MovieDetail movieDetail = new MovieDetail();
                movieDetail.setVod_id(type + videoId);
                movieDetail.setVod_name(title);
                movieDetail.setVod_tag(FILE);
                movieDetail.setVod_pic(LIST_PIC);
                movieDetail.setVod_play_from(BILI_BILI);
                String playUrl = list.stream()
                        .sorted(Comparator.comparingInt(BiliBiliSeasonInfo::getEpid))
                        .map(e -> e.getTitle() + "$" + buildPlayUrl(e))
                        .collect(Collectors.joining("#"));
                movieDetail.setVod_play_url(playUrl);
                result.getList().add(movieDetail);
            }
        }
        log.debug("{}: {}", tid, result);
        return result;
    }

    public MovieList getPlaylist(String tid) {
        String[] parts = tid.split("\\$");
        String wd = parts[1];
        int type = Integer.parseInt(parts[2]);
        int page = Integer.parseInt(parts[3]);
        MovieList result = new MovieList();

        if (searchPlaylist != null && page == searchPage && wd.equals(keyword)) {
            result.getList().add(searchPlaylist);
            return result;
        }

        HttpEntity<Void> entity = buildHttpEntity(null);

        List<BiliBiliSearchResult.Video> list = new ArrayList<>();

        searchPage = page;
        int size = type > 0 ? 1 : 2;
        int start = page * size;
        int end = start + size;

        for (int i = start; i < end; i++) {
            String url = String.format(SEARCH_API, wd, getSort(type), i + 1);
            ResponseEntity<BiliBiliSearchResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, BiliBiliSearchResponse.class);
            List<BiliBiliSearchResult.Video> videos = response.getBody().getData().getResult();
            list.addAll(videos);
        }

        searchPlaylist = new MovieDetail();
        searchPlaylist.setVod_id(COLLECTION + "$" + wd + "$0$" + page);
        searchPlaylist.setVod_name(wd + "合集" + (page + 1));
        searchPlaylist.setVod_tag(FILE);
        searchPlaylist.setVod_pic(LIST_PIC);
        searchPlaylist.setVod_play_from(BILI_BILI);
        String playUrl = list.stream().map(e -> fixTitle(e.getTitle()) + "$" + buildPlayUrl(e.getBvid())).collect(Collectors.joining("#"));
        searchPlaylist.setVod_play_url(playUrl);
        searchPlaylist.setVod_content("共" + list.size() + "个视频");
        result.getList().add(searchPlaylist);

        return result;
    }

    private <T> HttpEntity<T> buildHttpEntity(T data) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Referer", "https://api.bilibili.com/");
        headers.add("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7,ja;q=0.6,zh-TW;q=0.5");
        headers.add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
        headers.add(HttpHeaders.USER_AGENT, "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36");
        String cookie = settingRepository.findById(BILIBILI_COOKIE).map(Setting::getValue).orElse("");
        if (StringUtils.isNotBlank(cookie)) {
            headers.add("Cookie", cookie);
        }
        HttpEntity<T> entity = new HttpEntity<>(data, headers);
        return entity;
    }

    private BiliBiliInfo getInfo(String bvid) {
        BiliBiliInfoResponse infoResponse = restTemplate.getForObject(INFO_API + bvid, BiliBiliInfoResponse.class);
        if (infoResponse.getCode() == 0) {
            return infoResponse.getData();
        } else {
            log.warn("get BiliBili video info failed: {}", infoResponse.getMessage());
        }
        return null;
    }

    private String getToken(long aid, long cid, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Referer", "https://api.bilibili.com/");
        headers.add("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7,ja;q=0.6,zh-TW;q=0.5");
        headers.add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
        headers.add(HttpHeaders.USER_AGENT, "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36");
        headers.add("Cookie", cookie);

        HttpEntity<Void> entity = new HttpEntity<>(null, headers);
        String url = String.format(TOKEN_API, aid, cid);
        log.info("token url: {}", url);
        ResponseEntity<BiliBiliTokenResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, BiliBiliTokenResponse.class);
        return response.getBody().getData().getToken();
    }

    public Map<String, String> getPlayUrl(String bvid) {
        String url;
        String[] parts = bvid.split("\\$");
        Map<String, String> result;
        if (parts.length == 2) {
            url = String.format(PLAY_API1, parts[0], parts[1]);

        } else {
            BiliBiliInfo info = getInfo(bvid);
            url = String.format(PLAY_API, bvid, info.getCid());
        }

        HttpEntity<Void> entity = buildHttpEntity(null);
        ResponseEntity<Resp> response = restTemplate.exchange(url, HttpMethod.GET, entity, Resp.class);
        log.debug("uel: {}  response: {}", url, response.getBody());

        result = DashUtils.convert(response.getBody());
        String cookie = entity.getHeaders().getFirst("Cookie");
        result.put("header", "{\"Referer\":\"https://www.bilibili.com\",\"cookie\":\"" + cookie + "\",\"User-Agent\":\"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.127 Safari/537.36\"}");

        log.debug("{} {}", url, result);
        return result;
    }

    private String buildPlayUrl(BiliBiliSeasonInfo info) {
        return info.getBvid() + "$" + info.getCid();
    }

    private String buildPlayUrl(String bvid) {
        return bvid;
    }

    public MovieList getMovieList(String tid, String filter, String sort, int page) {
        if (tid.startsWith("search:")) {
            String[] parts = tid.split(":");
            return search(parts[1], sort, page);
        }

        String type = "";
        if (StringUtils.isNotBlank(filter)) {
            try {
                Map<String, String> map = objectMapper.readValue(filter, Map.class);
                type = map.getOrDefault("type", type);
            } catch (Exception e) {
                log.warn("", e);
            }
        }

        if (StringUtils.isNotBlank(type)) {
            return getMovieListByType(page, type);
        }

        String[] parts = tid.split("\\$");
        MovieList result = new MovieList();
        List<BiliBiliInfo> list;
        if (parts.length == 1) {
            list = getHotRank("all", Integer.parseInt(tid));
        } else if ("season".equals(parts[0])) {
            return getSeasonRank(Integer.parseInt(parts[1]));
        } else if ("pop".equals(parts[0])) {
            list = getPopular(page);
        } else if ("history".equals(parts[0])) {
            return getHistory(page);
        } else {
            list = getHotRank(parts[0], Integer.parseInt(parts[1]));
        }

        for (BiliBiliInfo info : list) {
            MovieDetail movieDetail = getMovieDetail(info);
            result.getList().add(movieDetail);
        }

        if ("pop".equals(parts[0])) {
            result.setTotal(600);
            result.setPage(page);
            result.setPagecount(20);
        } else {
            result.setTotal(result.getList().size());
        }

        result.setLimit(result.getList().size());
        return result;
    }

    @NotNull
    private MovieList getMovieListByType(int page, String type) {
        MovieList result = new MovieList();
        BiliBiliVideoInfo rank = getRankList(type, page);
//            MovieDetail movieDetail = new MovieDetail();
//            movieDetail.setVod_id(COLLECTION + "$" + wd + "$" + getType(sort) + "$" + (page - 1));
//            movieDetail.setVod_name(wd + "合集" + page);
//            movieDetail.setVod_tag(FILE);
//            movieDetail.setVod_pic(LIST_PIC);
//            movieDetail.setVod_play_from(BILI_BILI);
//            String playUrl = rank.getResult().stream().map(e -> fixTitle(e.getTitle()) + "$" + buildPlayUrl(e.getBvid())).collect(Collectors.joining("#"));
//            movieDetail.setVod_play_url(playUrl);
//            movieDetail.setVod_content("共" + rank.getResult().size() + "个视频");
//            result.getList().add(movieDetail);

        for (BiliBiliVideoInfo.Video video : rank.getResult()) {
            MovieDetail movieDetail = getMovieDetail(video);
            result.getList().add(movieDetail);
        }
        result.setTotal(rank.getNumResults());
        result.setPage(page);
        result.setPagecount(rank.getNumPages());
        result.setLimit(result.getList().size());
        return result;
    }

    public MovieList search(String wd, String sort, int pg) {
        MovieList result = new MovieList();
        HttpEntity<Void> entity = buildHttpEntity(null);

        List<BiliBiliSearchResult.Video> list = new ArrayList<>();

        int pages = 1;
        if (pg > 0) {
            String url = String.format(SEARCH_API, wd, sort, pg);
            log.debug("{}", url);
            ResponseEntity<BiliBiliSearchResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, BiliBiliSearchResponse.class);
            List<BiliBiliSearchResult.Video> videos = response.getBody().getData().getResult();

            keyword = wd;
            searchPage = pg - 1;
            MovieDetail movieDetail = new MovieDetail();
            movieDetail.setVod_id(COLLECTION + "$" + wd + "$" + getType(sort) + "$" + (pg - 1));
            movieDetail.setVod_name(wd + "合集" + pg);
            movieDetail.setVod_tag(FILE);
            movieDetail.setVod_pic(LIST_PIC);
            movieDetail.setVod_play_from(BILI_BILI);
            String playUrl = videos.stream().map(e -> fixTitle(e.getTitle()) + "$" + buildPlayUrl(e.getBvid())).collect(Collectors.joining("#"));
            movieDetail.setVod_play_url(playUrl);
            movieDetail.setVod_content("共" + videos.size() + "个视频");
            searchPlaylist = movieDetail;
            result.getList().add(movieDetail);

            list.addAll(videos);
            result.setTotal(response.getBody().getData().getNumResults());
            result.setPagecount(response.getBody().getData().getNumPages());
            log.debug("response: {}", result);
        } else {
            for (int i = 1; i <= 2; i++) {
                String url = String.format(SEARCH_API, wd, "", i);
                log.debug("{}", url);
                ResponseEntity<BiliBiliSearchResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, BiliBiliSearchResponse.class);
                List<BiliBiliSearchResult.Video> videos = response.getBody().getData().getResult();
                list.addAll(videos);
                pages = response.getBody().getData().getNumPages();
            }

            keyword = wd;
            searchPage = 0;
            pages = (pages + 1) / 2;
            for (int i = 0; i < pages; i++) {
                MovieDetail movieDetail = new MovieDetail();
                movieDetail.setVod_id(COLLECTION + "$" + wd + "$0$" + i);
                movieDetail.setVod_name(wd + "合集" + (i + 1));
                movieDetail.setVod_tag(FILE);
                movieDetail.setVod_pic(LIST_PIC);
                movieDetail.setVod_play_from(BILI_BILI);
                if (i == 0) {
                    String playUrl = list.stream().map(e -> fixTitle(e.getTitle()) + "$" + buildPlayUrl(e.getBvid())).collect(Collectors.joining("#"));
                    movieDetail.setVod_play_url(playUrl);
                    movieDetail.setVod_content("共" + list.size() + "个视频");
                    searchPlaylist = movieDetail;
                }
                result.getList().add(movieDetail);
            }

            log.info("search \"{}\" result: {}", wd, list.size());
            result.setTotal(result.getList().size());
        }

        for (BiliBiliSearchResult.Video info : list) {
            MovieDetail movieDetail = getSearchMovieDetail(info);
            result.getList().add(movieDetail);
        }

        result.setLimit(result.getList().size());
        return result;
    }

    private static int getType(String sort) {
        if (sort == null) {
            return 1;
        }
        switch (sort) {
            case "click":
                return 2;
            case "pubdate":
                return 3;
            case "dm":
                return 4;
            case "stow":
                return 5;
            default:
                return 1;
        }
    }

    private static String getSort(Integer type) {
        if (type == null) {
            return "";
        }
        switch (type) {
            case 2:
                return "click";
            case 3:
                return "pubdate";
            case 4:
                return "dm";
            case 5:
                return "stow";
            default:
                return "";
        }
    }

    private MovieDetail getSearchMovieDetail(BiliBiliSearchResult.Video info) {
        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(info.getBvid());
        movieDetail.setVod_name(fixTitle(info.getTitle()));
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_director(info.getAuthor());
        movieDetail.setType_name(info.getTypename());
        movieDetail.setVod_time(Instant.ofEpochSecond(info.getPubdate()).toString());
        movieDetail.setVod_pic(fixUrl(info.getPic()));
        movieDetail.setVod_play_from(BILI_BILI);
        movieDetail.setVod_play_url(buildPlayUrl(info.getBvid()));
        movieDetail.setVod_remarks(info.getDuration());
        movieDetail.setVod_content(info.getDescription());
        return movieDetail;
    }

    private static String fixTitle(String title) {
        return title
                .replace("#", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("<em class=\"keyword\">", "")
                .replace("</em>", "");
    }

    private static String fixUrl(String url) {
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        return url;
    }
}
