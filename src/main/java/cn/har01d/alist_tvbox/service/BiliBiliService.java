package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.NavigationDto;
import cn.har01d.alist_tvbox.dto.bili.*;
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
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static cn.har01d.alist_tvbox.util.Constants.*;

@Slf4j
@Service
public class BiliBiliService {
    private static final int VIDEO_DASH = 16;
    private static final int VIDEO_HDR = 64;
    private static final int VIDEO_4K = 128;
    private static final int DOLBY_AUDIO = 256;
    private static final int DOLBY_VIDEO = 512;
    private static final int VIDEO_8K = 1024;
    private static final int VIDEO_AV1 = 2048;
    private static final int FN_VAL = VIDEO_DASH + VIDEO_HDR + VIDEO_4K + DOLBY_AUDIO + VIDEO_8K + VIDEO_AV1;
    private static final String INFO_API = "https://api.bilibili.com/x/web-interface/view?bvid=";
    private static final String HOT_API = "https://api.bilibili.com/x/web-interface/ranking/v2?type=%s&rid=%d";
    private static final String LIST_API = "https://api.bilibili.com/x/web-interface/newlist_rank?main_ver=v3&search_type=video&view_type=hot_rank&copy_right=-1&new_web_tag=1&order=click&cate_id=%s&page=%d&pagesize=30&time_from=%s&time_to=%s";
    private static final String SEASON_API = "https://api.bilibili.com/pgc/season/rank/web/list?day=3&season_type=%d";
    private static final String HISTORY_API = "https://api.bilibili.com/x/web-interface/history/cursor?ps=30&type=archive&business=archive&max=%s&view_at=%s";
    private static final String PLAY_API1 = "https://api.bilibili.com/pgc/player/web/playurl?avid=%s&cid=%s&ep_id=%s&qn=127&type=&otype=json&fourk=1&fnver=0&fnval=" + FN_VAL; //dash
    private static final String PLAY_API = "https://api.bilibili.com/x/player/playurl?avid=%s&cid=%s&qn=127&type=&otype=json&fourk=1&fnver=0&fnval=" + FN_VAL; //dash
    private static final String PLAY_API2 = "https://api.bilibili.com/x/player/playurl?avid=%s&cid=%s&qn=127&platform=html5&high_quality=1"; // mp4

    private static final String TOKEN_API = "https://api.bilibili.com/x/player/playurl/token?%said=%d&cid=%d";
    private static final String POPULAR_API = "https://api.bilibili.com/x/web-interface/popular?ps=30&pn=";
    private static final String SEARCH_API = "https://api.bilibili.com/x/web-interface/search/type?jsonp=jsonp&search_type=video&highlight=1&page_size=50&keyword=%s&order=%s&page=%d";
    private static final String TOP_FEED_API = "https://api.bilibili.com/x/web-interface/wbi/index/top/feed/rcmd";
    public static final String NAV_API = "https://api.bilibili.com/x/web-interface/nav";
    public static final String REGION_API = "https://api.bilibili.com/x/web-interface/dynamic/region?ps=%d&rid=%s&pn=%d";
    public static final String CHANNEL_API = "https://api.bilibili.com/x/web-interface/web/channel/multiple/list?channel_id=%s&sort_type=%s&offset=%s&page_size=30";

    private final List<FilterValue> filters1 = Arrays.asList(
            new FilterValue("综合排序", ""),
            new FilterValue("最多播放", "click"),
            new FilterValue("最新发布", "pubdate"),
            new FilterValue("最多弹幕", "dm"),
            new FilterValue("最多收藏️", "stow")
    );

    private final List<FilterValue> filters2 = Arrays.asList(
            new FilterValue("最多播放", "hot"),
            new FilterValue("最新发布", "new")
    );
    private final SettingRepository settingRepository;
    private final AppProperties appProperties;
    private final NavigationService navigationService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private MovieDetail searchPlaylist;
    private String keyword = "";
    private int searchPage;

    public BiliBiliService(SettingRepository settingRepository,
                           AppProperties appProperties,
                           NavigationService navigationService,
                           RestTemplateBuilder builder,
                           ObjectMapper objectMapper) {
        this.settingRepository = settingRepository;
        this.appProperties = appProperties;
        this.navigationService = navigationService;
        this.restTemplate = builder
                .defaultHeader(HttpHeaders.REFERER, "https://www.bilibili.com/")
                .defaultHeader(HttpHeaders.USER_AGENT, Constants.USER_AGENT)
                .build();
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void setup() {
        if (!settingRepository.existsById("api_key")) {
            String apiKey = UUID.randomUUID().toString();
            log.debug("generate api key: {}", apiKey);
            settingRepository.save(new Setting("api_key", apiKey));
        }
    }

    private void checkLogin() {
        HttpEntity<Void> entity = buildHttpEntity(null);
        Map<String, Object> json = restTemplate.exchange(NAV_API, HttpMethod.GET, entity, Map.class).getBody();
        Map<String, Object> data = (Map<String, Object>) json.get("data");
        log.info("user: {} isLogin: {} vip: {}", data.get("uname"), data.get("isLogin"), data.get("vipType"));
    }

    public CategoryList getCategoryList() {
        checkLogin();
        CategoryList result = new CategoryList();
        navigationService.list().stream()
                .filter(NavigationDto::isShow)
                .forEach(item -> {
                    Category category = new Category();
                    String value = item.getValue();
                    if (item.getType() == 3) {
                        value = "channel:" + value;
                    }
                    if (item.getType() == 4) {
                        value = "search:" + value;
                    }
                    category.setType_id(value);
                    category.setType_name(item.getName());
                    category.setType_flag(0);
                    if (!item.getChildren().isEmpty()) {
                        List<FilterValue> filters = new ArrayList<>();
                        filters.add(new FilterValue("主分区", ""));
                        item.getChildren().stream().filter(NavigationDto::isShow).map(e -> new FilterValue(e.getName(), e.getValue())).forEach(filters::add);
                        Filter filter1 = new Filter("category", "分类", filters);
                        Filter filter2 = new Filter("type", "类型", List.of(new FilterValue("最新", ""), new FilterValue("热门", "hot")));
                        result.getFilters().put(category.getType_id(), List.of(filter1, filter2));
                    }
                    if (item.getType() == 3) {
                        result.getFilters().put(category.getType_id(), List.of(new Filter("sort", "排序", filters2)));
                    }
                    if (item.getType() == 4) {
                        result.getFilters().put(category.getType_id(), List.of(new Filter("sort", "排序", filters1)));
                    }
                    result.getCategories().add(category);
                });

        result.setTotal(result.getCategories().size());
        result.setLimit(result.getCategories().size());
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
        movieDetail.setVod_pic(fixUrl(info.getCover()));
        //movieDetail.setVod_play_from(BILI_BILI);
        //movieDetail.setVod_play_url(buildPlayUrl(movieDetail.getVod_id()));
        movieDetail.setVod_remarks(info.getRating());
        //movieDetail.setVod_content(info.getDesc() + "; " + info.getStat().getView() + "播放; " + info.getStat().getFollow() + "关注");
        return movieDetail;
    }

    private MovieDetail getMovieDetail(BiliBiliVideoInfo.Video info) {
        String id = info.getBvid();
        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(id);
        movieDetail.setVod_name(info.getTitle());
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_pic(fixUrl(info.getPic()));
        //movieDetail.setVod_play_from(BILI_BILI);
        //movieDetail.setVod_play_url(buildPlayUrl(id));
        movieDetail.setVod_remarks(seconds2String(info.getDuration()));
        //movieDetail.setVod_content(info.getDescription());
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

    private MovieDetail getMovieDetail(BiliBiliChannelItem info) {
        String id = info.getBvid();
        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(id);
        movieDetail.setVod_name(info.getName());
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_director(info.getAuthor());
        movieDetail.setVod_pic(fixUrl(info.getCover()));
        movieDetail.setVod_play_from(BILI_BILI);
        movieDetail.setVod_play_url(buildPlayUrl(id));
        movieDetail.setVod_remarks(info.getDuration());
        return movieDetail;
    }

    private MovieDetail getMovieDetail(BiliBiliInfo info) {
        return getMovieDetail(info, false);
    }

    private MovieDetail getMovieDetail(BiliBiliInfo info, boolean full) {
        String id = info.getBvid();
        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(id);
        movieDetail.setVod_name(info.getTitle());
        movieDetail.setVod_tag(FILE);
        movieDetail.setType_name(info.getTname());
        movieDetail.setVod_remarks(seconds2String(info.getDuration()));
        movieDetail.setVod_pic(fixUrl(info.getPic()));
        if (full) {
            movieDetail.setVod_time(Instant.ofEpochSecond(info.getPubdate()).toString());
            movieDetail.setVod_play_from(BILI_BILI);
            movieDetail.setVod_play_url(buildPlayUrl(id));
            if (info.getOwner() != null) {
                movieDetail.setVod_director(info.getOwner().getName());
            }
            if (info.getStat() != null) {
                movieDetail.setVod_content(info.getDesc() + "; " + info.getStat().getView() + "播放; " + info.getStat().getLike() + "点赞");
            } else {
                movieDetail.setVod_content(info.getDesc());
            }
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
        log.debug("{} {}", url, hotResponse);
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
        log.debug("{}", url);

        entity = buildHttpEntity(null);
        ResponseEntity<BiliBiliFeedResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, BiliBiliFeedResponse.class);
        return response.getBody().getData().getItem();
    }

    private String getKey(String url) {
        int start = url.lastIndexOf('/') + 1;
        int end = url.lastIndexOf('.');
        return url.substring(start, end);
    }

    public List<BiliBiliInfo> getHotRank(String type, int rid, int page) {
        if (page > 1) {
            return new ArrayList<>();
        }
        BiliBiliHotResponse hotResponse = restTemplate.getForObject(String.format(HOT_API, type, rid), BiliBiliHotResponse.class);
        return hotResponse.getData().getList();
    }

    public MovieList getSeasonRank(int type, int page) {
        MovieList result = new MovieList();
        if (page > 1) {
            return result;
        }
        String url = String.format(SEASON_API, type);
        HttpEntity<Void> entity = buildHttpEntity(null);
        ResponseEntity<BiliBiliSeasonResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, BiliBiliSeasonResponse.class);
        BiliBiliSeasonResponse hotResponse = response.getBody();
        for (BiliBiliSeasonInfo info : hotResponse.getData().getList()) {
            MovieDetail movieDetail = getMovieDetail(info);
            result.getList().add(movieDetail);
        }
        result.setLimit(result.getList().size());
        result.setTotal(result.getList().size());
        result.setPagecount(1);
        log.debug("getSeasonRank: {} {}", url, result);
        return result;
    }

    public MovieList getRegion(String tid, int page) {
        MovieList result = new MovieList();
        int size = 30;
        String url = String.format(REGION_API, size, tid, page);
        HttpEntity<Void> entity = buildHttpEntity(null);
        ResponseEntity<BiliBiliListResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, BiliBiliListResponse.class);
        BiliBiliListResponse hotResponse = response.getBody();
        for (BiliBiliInfo info : hotResponse.getData().getArchives()) {
            MovieDetail movieDetail = getMovieDetail(info);
            result.getList().add(movieDetail);
        }
        result.setLimit(result.getList().size());
        result.setTotal(hotResponse.getData().getPage().getCount());
        result.setPagecount((result.getTotal() + size - 1) / size);
        log.debug("getRegion: {} {}", url, result);
        return result;
    }

    private List<BiliBiliHistoryResult.Cursor> cursors = new ArrayList<>();

    public MovieList getHistory(int page) {
        if (page == 1) {
            cursors = new ArrayList<>();
            cursors.add(new BiliBiliHistoryResult.Cursor());
        }
        BiliBiliHistoryResult.Cursor cursor = cursors.get(page - 1);
        String url = String.format(HISTORY_API, cursor.getMax(), cursor.getViewAt());
        HttpEntity<Void> entity = buildHttpEntity(null);
        ResponseEntity<BiliBiliHistoryResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, BiliBiliHistoryResponse.class);
        BiliBiliHistoryResponse hotResponse = response.getBody();
        MovieList result = new MovieList();
        for (BiliBiliHistoryResult.Video info : hotResponse.getData().getList()) {
            MovieDetail movieDetail = getMovieDetail(info);
            result.getList().add(movieDetail);
        }
        cursors.add(hotResponse.getData().getCursor());

        result.setLimit(result.getList().size());
        result.setTotal(2000);
        result.setPagecount(50);
        result.setPage(page);
        log.debug("getHistory: {} {}", url, result);
        return result;
    }

    public MovieList getDetail(String bvid) throws IOException {
        if (bvid.startsWith(LIST)) {
            return getChannelPlaylist(bvid);
        }

        if (bvid.startsWith(COLLECTION)) {
            return getPlaylist(bvid);
        }

        if (bvid.startsWith("season:")) {
            return getBangumi(bvid);
        }

        BiliBiliInfo info = getInfo(bvid);
        MovieDetail movieDetail = getMovieDetail(info, true);
        MovieList result = new MovieList();
        result.getList().add(movieDetail);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        log.debug("detail: {}", result);
        return result;
    }

    private static final Pattern SCRIPT = Pattern.compile("<script\\s+id=\"__NEXT_DATA__\"\\s+type=\"application/json\"\\s*>(.*?)</script\\s*>");
    private static final Pattern EP_MAP = Pattern.compile("\"episodes\"\\s*:\\s*(.+?)\\s*,\\s*\"user_status\"");
    private static final Pattern DESC = Pattern.compile("\"evaluate\"\\s*:\\s*\"(.+?)\"\\s*,\\s*\"jp_title\"");
    private static final Pattern COVER = Pattern.compile("\"squareCover\"\\s*:\\s*\".+?\"\\s*,\\s*\"cover\"\\s*:\\s*\"(.+?)\"\\s*,\\s*\"publish\"");
    private static final Pattern MEDIA_INFO = Pattern.compile("\"mediaInfo\"\\s*:\\s*.+?\"title\":\"(.+?)\",\\s*.+?\\s*\"sectionsMap\"");
    private static final Pattern VIDEO_ID = Pattern.compile("\"videoId\"\\s*:\\s*\"(ep|ss)(\\d+)\"");

    private MovieList getBangumi(String tid) throws IOException {
        MovieList result = new MovieList();

        String[] parts = tid.split(":");
        String sid = parts[1];
        String url = "https://www.bilibili.com/bangumi/play/ss" + sid;
        log.debug("Bangumi: {}", url);
        HttpEntity<Void> entity = buildHttpEntity(null);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
        String html = response.getBody();
        Matcher m = SCRIPT.matcher(html);
        if (m.find()) {
            String json = m.group(1);
            String title = BILI_BILI;
            String desc = "";
            String cover = "";
            m = MEDIA_INFO.matcher(json);
            if (m.find()) {
                title = m.group(1);
            }
            m = DESC.matcher(json);
            if (m.find()) {
                desc = m.group(1);
            }
            m = COVER.matcher(json);
            if (m.find()) {
                cover = m.group(1);
            }
            log.debug("title: {} cover: {} desc: {}", title, cover, desc);
            m = EP_MAP.matcher(json);
            SortedMap<Integer, List<BiliBiliSeasonInfo>> sections = new TreeMap<>();
            if (m.find()) {
                String data = m.group(1);
                for (Object item : objectMapper.readValue(data, List.class)) {
                    data = objectMapper.writeValueAsString(item);
                    log.debug("EP: {}", data);
                    BiliBiliSeasonInfo info = objectMapper.readValue(data, BiliBiliSeasonInfo.class);
                    List<BiliBiliSeasonInfo> list = sections.getOrDefault(info.getSectionType(), new ArrayList<>());
                    list.add(info);
                    sections.put(info.getSectionType(), list);
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
                movieDetail.setVod_pic(cover.isEmpty() ? LIST_PIC : fixUrl(cover));
                movieDetail.setVod_content(desc);
                movieDetail.setVod_play_from(sections.keySet().stream().map(this::getSectionType).collect(Collectors.joining("$$$")));
                String playUrl = sections.values().stream()
                        .map(this::build)
                        .collect(Collectors.joining("$$$"));
                movieDetail.setVod_play_url(playUrl);
                result.getList().add(movieDetail);
            }
        }
        result.setHeader("{\"Referer\":\"https://www.bilibili.com\"}");
        log.debug("{}: {}", tid, result);
        return result;
    }

    private String getSectionType(int type) {
        switch (type) {
            case 0:
                return "正片";
            case 1:
                return "预告";
            case 2:
                return "看点";
            case 5:
                return "UP主";
            case 8:
                return "更多精彩";
            default:
                return BILI_BILI + type;
        }
    }

    private String build(List<BiliBiliSeasonInfo> list) {
        return list.stream().map(e -> getTitle(e) + "$" + buildPlayUrl(e)).collect(Collectors.joining("#"));
    }

    private String getTitle(BiliBiliSeasonInfo info) {
        String title = info.getTitle();
        if (StringUtils.isNotBlank(info.getLong_title())) {
            title = info.getLong_title().contains(info.getTitle()) ? info.getLong_title() : info.getTitle() + "." + info.getLong_title();
        } else if (StringUtils.isNotBlank(info.getTitleFormat())) {
            title = info.getTitleFormat().contains(info.getTitle()) ? info.getTitleFormat() : info.getTitle() + "." + info.getTitleFormat();
        }
        if (StringUtils.isNotBlank(info.getBadge())) {
            title += " " + info.getBadge();
        }
        return title;
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
        headers.add(HttpHeaders.REFERER, "https://api.bilibili.com/");
        headers.add(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7,ja;q=0.6,zh-TW;q=0.5");
        headers.add(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
        headers.add(HttpHeaders.USER_AGENT, "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36");
        String cookie = settingRepository.findById(BILIBILI_COOKIE).map(Setting::getValue).orElse("");
        if (StringUtils.isNotBlank(cookie)) {
            headers.add(HttpHeaders.COOKIE, cookie.trim());
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
        headers.add(HttpHeaders.REFERER, "https://api.bilibili.com/");
        headers.add(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7,ja;q=0.6,zh-TW;q=0.5");
        headers.add(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
        headers.add(HttpHeaders.USER_AGENT, "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36");
        headers.add(HttpHeaders.COOKIE, cookie);

        HttpEntity<Void> entity = new HttpEntity<>(null, headers);
        String url = String.format(TOKEN_API, aid, cid);
        log.debug("token url: {}", url);
        ResponseEntity<BiliBiliTokenResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, BiliBiliTokenResponse.class);
        return response.getBody().getData().getToken();
    }

    public Map<String, String> getPlayUrl(String bvid, boolean dash) {
        String url;
        String[] parts = bvid.split("-");
        Map<String, String> result;
        if (parts.length > 2) {
            String api = dash ? PLAY_API1 : PLAY_API2;
            url = String.format(api, parts[0], parts[1], parts[2]);
        } else {
            BiliBiliInfo info = getInfo(bvid);
            String api = dash ? PLAY_API : PLAY_API2;
            url = String.format(api, info.getAid(), info.getCid());
        }
        log.debug("bvid: {} dash: {}  url: {}", bvid, dash, url);

        HttpEntity<Void> entity = buildHttpEntity(null);
        if (dash) {
            ResponseEntity<Resp> response = restTemplate.exchange(url, HttpMethod.GET, entity, Resp.class);
            log.debug("url: {}  response: {}", url, response.getBody());
            if (response.getBody().getCode() != 0) {
                log.warn("获取失败: {} {}", response.getBody().getCode(), response.getBody().getMessage());
            }

            result = DashUtils.convert(response.getBody());
            String cookie = entity.getHeaders().getFirst("Cookie");
            result.put("header", "{\"Referer\":\"https://www.bilibili.com\",\"cookie\":\"" + cookie + "\",\"User-Agent\":\"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.127 Safari/537.36\"}");

            log.debug("{} {}", url, result);
            return result;
        } else {
            ResponseEntity<BiliBiliPlayResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, BiliBiliPlayResponse.class);
            BiliBiliPlayResponse res = response.getBody();
            log.debug("url: {}  response: {}", url, res);
            if (res.getCode() != 0) {
                log.warn("获取失败: {} {}", res.getCode(), res.getMessage());
            }

            Map<String, String> map = new HashMap<>();
            BiliBiliPlay data = res.getData() == null ? res.getResult() : res.getData();
            map.put("url", data.getDurl().get(0).getUrl());
            return map;
        }
    }

    private String buildPlayUrl(BiliBiliSeasonInfo info) {
        return info.getAid() + "-" + info.getCid() + "-" + info.getId();
    }

    private String buildPlayUrl(String bvid) {
        return bvid;
    }

    public MovieList getMovieList(String tid, String category, String type, String sort, int page) {
        if (tid.startsWith("search:")) {
            String[] parts = tid.split(":");
            return search(parts[1], sort, page);
        } else if (tid.startsWith("channel:")) {
            String[] parts = tid.split(":");
            return getChannel(parts[1], sort, page);
        }

        if (StringUtils.isNotBlank(category)) {
            return getMovieListByType(category, type, page);
        }

        String[] parts = tid.split("\\$");
        MovieList result = new MovieList();
        List<BiliBiliInfo> list;
        int total = 100;
        if (parts.length == 1) {
            int rid = Integer.parseInt(tid);
            if (rid > 0 && "".equals(type)) {
                return getRegion(tid, page);
            }
            list = getHotRank("all", rid, page);
        } else if ("season".equals(parts[0])) {
            return getSeasonRank(Integer.parseInt(parts[1]), page);
        } else if ("pop".equals(parts[0])) {
            total = 600;
            list = getPopular(page);
        } else if ("history".equals(parts[0])) {
            return getHistory(page);
        } else {
            int rid = Integer.parseInt(parts[1]);
            list = getHotRank(parts[0], rid, page);
        }

        for (BiliBiliInfo info : list) {
            MovieDetail movieDetail = getMovieDetail(info);
            result.getList().add(movieDetail);
        }

        result.setTotal(total);
        if ("pop".equals(parts[0])) {
            result.setPage(page);
            result.setPagecount(20);
        } else {
            result.setPagecount(2);
            result.setTotal(result.getList().size());
        }

        result.setLimit(result.getList().size());
        return result;
    }

    private MovieList getMovieListByType(String tid, String type, int page) {
        if ("".equals(type)) {
            return getRegion(tid, page);
        }
        MovieList result = new MovieList();
        BiliBiliVideoInfo rank = getRankList(tid, page);

//        {
//            MovieDetail movieDetail = new MovieDetail();
//            movieDetail.setVod_id(LIST + "$" + tid + "$" + type + "$" + page);
//            movieDetail.setVod_name("合集" + page);
//            movieDetail.setVod_tag(FILE);
//            movieDetail.setVod_pic(LIST_PIC);
//            movieDetail.setVod_play_from(BILI_BILI);
//            String playUrl = list.stream().map(e -> fixTitle(e.getVod_name()) + "$" + buildPlayUrl(e.getVod_id())).collect(Collectors.joining("#"));
//            movieDetail.setVod_play_url(playUrl);
//            movieDetail.setVod_content("共" + list.size() + "个视频");
//            result.getList().add(movieDetail);
//        }

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

    private List<String> offsets = new ArrayList<>();

    public MovieList getChannel(String id, String sort, int page) {
        if (page == 1) {
            offsets = new ArrayList<>();
            offsets.add("");
        }
        if (StringUtils.isBlank(sort)) {
            sort = "new";
        }
        MovieList result = new MovieList();
        HttpEntity<Void> entity = buildHttpEntity(null);
        String url = String.format(CHANNEL_API, id, sort, offsets.get(page - 1));
        ResponseEntity<BiliBiliChannelResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, BiliBiliChannelResponse.class);
        log.info("{}", url);

        List<MovieDetail> list = new ArrayList<>();
        for (BiliBiliChannelItem item : response.getBody().getData().getList()) {
            if (item.getItems().isEmpty()) {
                list.add(getMovieDetail(item));
            } else {
                for (BiliBiliChannelItem v : item.getItems()) {
                    list.add(getMovieDetail(v));
                }
            }
        }

        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(LIST + "$" + id + "$" + sort + "$" + page);
        movieDetail.setVod_name("合集" + page);
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_pic(LIST_PIC);  // TODO: cover
        movieDetail.setVod_play_from(BILI_BILI);
        String playUrl = list.stream().map(e -> fixTitle(e.getVod_name()) + "$" + buildPlayUrl(e.getVod_id())).collect(Collectors.joining("#"));
        movieDetail.setVod_play_url(playUrl);
        movieDetail.setVod_content("共" + list.size() + "个视频");
        result.getList().add(movieDetail);

        offsets.add(response.getBody().getData().getOffset());
        result.getList().addAll(list);
        result.setLimit(result.getList().size());
        result.setHeader("{\"Referer\":\"https://www.bilibili.com\"}");
        return result;
    }

    public MovieList getChannelPlaylist(String tid) {
        String[] parts = tid.split("\\$");
        String id = parts[1];
        String sort = parts[2];
        int page = Integer.parseInt(parts[3]);

        HttpEntity<Void> entity = buildHttpEntity(null);
        String url = String.format(CHANNEL_API, id, sort, offsets.get(page - 1));
        ResponseEntity<BiliBiliChannelResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, BiliBiliChannelResponse.class);
        List<BiliBiliChannelItem> list = new ArrayList<>();
        List<BiliBiliChannelItem> videos = response.getBody().getData().getList();
        list.addAll(videos);

        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(COLLECTION + "$" + id + "$0$" + page);
        movieDetail.setVod_name("合集" + page);
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_pic(LIST_PIC);
        movieDetail.setVod_play_from(BILI_BILI);
        String playUrl = list.stream().map(e -> fixTitle(e.getName()) + "$" + buildPlayUrl(e.getBvid())).collect(Collectors.joining("#"));
        movieDetail.setVod_play_url(playUrl);
        movieDetail.setVod_content("共" + list.size() + "个视频");
        MovieList result = new MovieList();
        result.getList().add(movieDetail);

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
        //movieDetail.setVod_director(info.getAuthor());
        movieDetail.setType_name(info.getTypename());
        //movieDetail.setVod_time(Instant.ofEpochSecond(info.getPubdate()).toString());
        movieDetail.setVod_pic(fixUrl(info.getPic()));
        //movieDetail.setVod_play_from(BILI_BILI);
        //movieDetail.setVod_play_url(buildPlayUrl(info.getBvid()));
        movieDetail.setVod_remarks(info.getDuration());
        //movieDetail.setVod_content(info.getDescription());
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
        if (url != null && url.startsWith("//")) {
            return "https:" + url;
        }
        return url;
    }
}
