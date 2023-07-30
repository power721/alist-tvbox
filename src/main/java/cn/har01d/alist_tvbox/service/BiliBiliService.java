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
import cn.har01d.alist_tvbox.util.BiliBiliUtils;
import cn.har01d.alist_tvbox.util.Constants;
import cn.har01d.alist_tvbox.util.DashUtils;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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
    private static final int FN_VAL = VIDEO_DASH + VIDEO_HDR + VIDEO_4K + DOLBY_AUDIO + VIDEO_AV1;
    private static final String INFO_API = "https://api.bilibili.com/x/web-interface/view?bvid=";
    private static final String HOT_API = "https://api.bilibili.com/x/web-interface/ranking/v2?type=%s&rid=%d";
    private static final String LIST_API = "https://api.bilibili.com/x/web-interface/newlist_rank?main_ver=v3&search_type=video&view_type=hot_rank&copy_right=-1&new_web_tag=1&order=click&cate_id=%s&page=%d&pagesize=30&time_from=%s&time_to=%s";
    private static final String SEASON_API = "https://api.bilibili.com/pgc/season/rank/web/list?day=3&season_type=%d";
    private static final String HISTORY_API = "https://api.bilibili.com/x/web-interface/history/cursor?ps=30&type=archive&business=archive&max=%s&view_at=%s";
    private static final String PLAY_API1 = "https://api.bilibili.com/pgc/player/web/playurl?avid=%s&cid=%s&ep_id=%s&qn=127&type=&otype=json&fourk=1&fnver=0&fnval=%d"; //dash
    private static final String PLAY_API = "https://api.bilibili.com/x/player/playurl?avid=%s&cid=%s&qn=127&type=&otype=json&fourk=1&fnver=0&fnval=%d"; //dash
    private static final String PLAY_API2 = "https://api.bilibili.com/x/player/playurl?avid=%s&cid=%s&qn=127&platform=html5&high_quality=1"; // mp4
    private static final String TOKEN_API = "https://api.bilibili.com/x/player/playurl/token?%said=%d&cid=%d";
    private static final String POPULAR_API = "https://api.bilibili.com/x/web-interface/popular?ps=30&pn=";
    private static final String SEARCH_API = "https://api.bilibili.com/x/web-interface/search/type?search_type=video&page_size=50&keyword=%s&order=%s&duration=%s&page=%d";
    private static final String NEW_SEARCH_API = "https://api.bilibili.com/x/space/wbi/arc/search";
    private static final String TOP_FEED_API = "https://api.bilibili.com/x/web-interface/wbi/index/top/feed/rcmd";
    private static final String FEED_API = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all?timezone_offset=-480&type=video&offset=%s&page=%d&features=itemOpusStyle";
    private static final String CHAN_API = "https://api.bilibili.com/x/web-interface/web/channel/category/channel_arc/list?id=%s&offset=%s";
    public static final String NAV_API = "https://api.bilibili.com/x/web-interface/nav";
    public static final String HEARTBEAT_API = "https://api.bilibili.com/x/click-interface/web/heartbeat";
    public static final String RELATED_API = "https://api.bilibili.com/x/web-interface/archive/related?bvid=%s";
    public static final String REGION_API = "https://api.bilibili.com/x/web-interface/dynamic/region?ps=%d&rid=%s&pn=%d";
    public static final String CHANNEL_API = "https://api.bilibili.com/x/web-interface/web/channel/multiple/list?channel_id=%s&sort_type=%s&offset=%s&page_size=30";
    public static final String FAV_API = "https://api.bilibili.com/x/v3/fav/resource/list?media_id=%s&keyword=&order=%s&type=0&tid=0&platform=web&pn=%d&ps=20";

    private final List<FilterValue> filters1 = Arrays.asList(
            new FilterValue("综合排序", ""),
            new FilterValue("最多播放", "click"),
            new FilterValue("最新发布", "pubdate"),
            new FilterValue("最多弹幕", "dm"),
            new FilterValue("最多收藏️", "stow"),
            new FilterValue("最多评论", "scores")
    );

    private final List<FilterValue> filters3 = Arrays.asList(
            new FilterValue("全部", ""),
            new FilterValue("60分钟以上", "4"),
            new FilterValue("30~60分钟", "3"),
            new FilterValue("10~30分钟", "2"),
            new FilterValue("10分钟以下", "1")
    );

    private final List<FilterValue> filters2 = Arrays.asList(
            new FilterValue("最多播放", "hot"),
            new FilterValue("最新发布", "new")
    );

    private final List<FilterValue> filters4 = Arrays.asList(
            new FilterValue("最近收藏", "mtime"),
            new FilterValue("最多播放", "view"),
            new FilterValue("最新投稿", "pubtime")
    );

    private final List<FilterValue> filters5 = Arrays.asList(
            new FilterValue("全部", "0"),
            new FilterValue("动漫", "1"),
            new FilterValue("游戏", "2"),
            new FilterValue("电竞", "3"),
            new FilterValue("鬼畜", "4"),
            new FilterValue("时尚", "5"),
            new FilterValue("音乐", "6"),
            new FilterValue("科技", "7"),
            new FilterValue("数码", "8"),
            new FilterValue("知识", "9"),
            new FilterValue("动物圈", "10"),
            new FilterValue("美食", "11"),
            new FilterValue("虚拟UP主", "12"),
            new FilterValue("明星", "13"),
            new FilterValue("舞蹈", "14"),
            new FilterValue("生活", "15"),
            new FilterValue("综艺", "16"),
            new FilterValue("电影", "17"),
            new FilterValue("电视剧", "18"),
            new FilterValue("相声", "19"),
            new FilterValue("特摄", "20"),
            new FilterValue("运动", "21"),
            new FilterValue("星海", "22")
    );

    private final List<FilterValue> filters6 = Arrays.asList(
            new FilterValue("最多播放", "click"),
            new FilterValue("最新发布", "pubdate"),
            new FilterValue("最多收藏️", "stow")
    );

    private final SettingRepository settingRepository;
    private final NavigationService navigationService;
    private final AppProperties appProperties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private MovieDetail searchPlaylist;
    private String keyword = "";
    private int searchPage;
    private String favId = "";
    private Integer mid;

    private List<String> feedOffsets = new ArrayList<>(); // 动态列表
    private List<String> chanOffsets = new ArrayList<>(); // 频道列表
    private String imgKey;
    private String subKey;
    private LocalDate keyTime;

    public BiliBiliService(SettingRepository settingRepository,
                           NavigationService navigationService,
                           AppProperties appProperties,
                           RestTemplateBuilder builder,
                           ObjectMapper objectMapper) {
        this.settingRepository = settingRepository;
        this.navigationService = navigationService;
        this.appProperties = appProperties;
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

    public Map<String, Object> updateCookie(CookieData cookieData) {
        settingRepository.save(new Setting(BILIBILI_COOKIE, cookieData.getCookie()));
        return getLoginStatus();
    }

    public Map<String, Object> getLoginStatus() {
        HttpEntity<Void> entity = buildHttpEntity(null);
        Map<String, Object> json = restTemplate.exchange(NAV_API, HttpMethod.GET, entity, Map.class).getBody();
        Map<String, Object> data = (Map<String, Object>) json.get("data");
        if (data != null) {
            if (data.get("mid") instanceof Long) {
                mid = ((Long) data.get("mid")).intValue();
            } else {
                mid = (Integer) data.get("mid");
            }
            log.info("user: {} {} isLogin: {} vip: {}", data.get("uname"), data.get("mid"), data.get("isLogin"), data.get("vipType"));
        }
        return data;
    }

    public QrCode scanLogin() throws IOException, WriterException {
        QrCode qrCode = restTemplate.getForObject("https://passport.bilibili.com/x/passport-login/web/qrcode/generate", BiliBiliQrCodeResponse.class).getData();
        qrCode.setImage(getQrCode(qrCode.getUrl()));
        log.debug("{}", qrCode);
        return qrCode;
    }

    private String getQrCode(String text) throws IOException, WriterException {
        log.info("getQrCode: {}", text);
        Map<EncodeHintType, String> charcter = new HashMap<>();
        charcter.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        BitMatrix bitMatrix = new MultiFormatWriter()
                .encode(text, BarcodeFormat.QR_CODE, 500, 500, charcter);
        int width = bitMatrix.getWidth();
        int height = bitMatrix.getHeight();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, bitMatrix.get(x, y) ? BLACK : WHITE);
            }
        }

        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", stream);
        return Base64.getEncoder().encodeToString(stream.toByteArray());
    }

    public int checkLogin(String key) {
        String url = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=" + key;
        log.debug("{}", url);
        ResponseEntity<BiliBiliLoginResponse> response = restTemplate.getForEntity(url, BiliBiliLoginResponse.class);
        if (response.getBody() != null && response.getBody().getData() != null) {
            QrCodeResult result = response.getBody().getData();
            log.debug("checkLogin: {}", result);
            int code = result.getCode();
            if (code == 0) {
                if (StringUtils.isNotBlank(result.getRefresh_token())) {
                    log.info("扫码登录成功");
                    String cookie = response.getHeaders().get("set-cookie").stream().map(e -> e.split(";")[0]).collect(Collectors.joining(";"));
                    settingRepository.save(new Setting(BILIBILI_COOKIE, cookie));
                    return code;
                }
            } else if (code == 86038) {
                log.warn(result.getMessage());
                return code;
            }
        }
        return 1;
    }

    public CategoryList getCategoryList() {
        getLoginStatus();
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
                    if (item.getType() == 5) {
                        value = "up:" + value;
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
                        result.getFilters().put(category.getType_id(), List.of(new Filter("sort", "排序", filters1), new Filter("duration", "时长", filters3)));
                    }
                    if (item.getType() == 5) {
                        result.getFilters().put(category.getType_id(), List.of(new Filter("sort", "排序", filters6)));
                    }
                    try {
                        if (value.equals("fav$0")) {
                            List<Filter> filters = getFavFilters();
                            category.setType_id(value + "$" + favId);
                            result.getFilters().put(category.getType_id(), filters);
                        }
                    } catch (Exception e) {
                        log.warn("", e);
                        return;
                    }
                    if (value.equals("channel$0")) {
                        List<Filter> filters = List.of(new Filter("type", "分类", filters5));
                        result.getFilters().put(category.getType_id(), filters);
                    }
                    result.getCategories().add(category);
                });

        result.setTotal(result.getCategories().size());
        result.setLimit(result.getCategories().size());

        log.debug("getCategoryList: {}", result);
        return result;
    }

    private List<Filter> getFavFilters() {
        String url = "https://api.bilibili.com/x/v3/fav/folder/created/list-all?up_mid=" + mid;
        HttpEntity<Void> entity = buildHttpEntity(null);
        ResponseEntity<BiliBiliFavListResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, BiliBiliFavListResponse.class);
        log.debug("getFavlist: {}", response.getBody());
        List<FilterValue> filters = response.getBody().getData().getList().stream().map(e -> new FilterValue(e.getTitle(), String.valueOf(e.getId()))).collect(Collectors.toList());
        favId = filters.get(0).getV();
        return List.of(new Filter("sort", "排序", filters4), new Filter("type", "分类", filters));
    }

    public MovieList recommend() {
        List<BiliBiliInfo> list = getTopFeed();
        MovieList result = new MovieList();
        for (BiliBiliInfo info : list) {
            if (info.getCid() != 0) {
                MovieDetail movieDetail = getMovieDetail(info);
                result.getList().add(movieDetail);
            }
        }

        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        return result;
    }

    private MovieDetail getMovieDetail(BiliBiliSeasonInfo info) {
        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id("season$" + info.getSeason_id());
        movieDetail.setVod_name(info.getTitle());
        movieDetail.setVod_tag(FILE);
        movieDetail.setType_name(info.getBadge());
        movieDetail.setVod_pic(fixCover(info.getCover()));
        movieDetail.setVod_remarks(info.getRating());
        return movieDetail;
    }

    private MovieDetail getMovieDetail(BiliBiliVideoInfo.Video info) {
        String id = info.getBvid();
        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(id);
        movieDetail.setVod_name(info.getTitle());
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_pic(fixCover(info.getPic()));
        movieDetail.setVod_remarks(seconds2String(info.getDuration()));
        return movieDetail;
    }

    private MovieDetail getMovieDetail(FavItem info) {
        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(info.getBvid());
        movieDetail.setVod_name(info.getTitle());
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_pic(fixCover(info.getCover()));
        movieDetail.setVod_remarks(seconds2String(info.getDuration()));
        return movieDetail;
    }

    private MovieDetail getMovieDetail(BiliBiliHistoryResult.Video info) {
        String id = info.getHistory().getBvid();
        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(id);
        movieDetail.setVod_name(info.getTitle());
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_pic(fixCover(info.getCover()));
        movieDetail.setVod_play_from(BILI_BILI);
        movieDetail.setVod_play_url("视频$" + buildPlayUrl(id));
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
        movieDetail.setVod_pic(fixCover(info.getCover()));
        movieDetail.setVod_play_from(BILI_BILI);
        movieDetail.setVod_play_url("视频$" + buildPlayUrl(id));
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
        movieDetail.setVod_pic(fixCover(info.getPic()));
        if (full) {
            movieDetail.setVod_time(Instant.ofEpochSecond(info.getPubdate()).toString());
            movieDetail.setVod_play_from(BILI_BILI);
            if (info.getPages().size() <= 1) {
                movieDetail.setVod_play_url("视频$" + buildPlayUrl(id));
            } else {
                movieDetail.setVod_play_url(info.getPages().stream().map(e -> fixTitle(e.getPart()) + "$" + info.getAid() + "-" + e.getCid()).collect(Collectors.joining("#")));
            }
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

    public MovieList getPopular(int page) {
        MovieList result = new MovieList();
        BiliBiliHotResponse hotResponse = restTemplate.getForObject(POPULAR_API + page, BiliBiliHotResponse.class);
        List<MovieDetail> list = new ArrayList<>();

        for (BiliBiliInfo info : hotResponse.getData().getList()) {
            MovieDetail movieDetail = getMovieDetail(info);
            list.add(movieDetail);
        }

        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id("popular$0$" + 0 + "$" + page);
        movieDetail.setVod_name("合集" + page);
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_pic(LIST_PIC);
        movieDetail.setVod_play_from(BILI_BILI);
        String playUrl = list.stream().map(e -> fixTitle(e.getVod_name()) + "$" + buildPlayUrl(e.getVod_id())).collect(Collectors.joining("#"));
        movieDetail.setVod_play_url(playUrl);
        movieDetail.setVod_content("共" + list.size() + "个视频");
        result.getList().add(movieDetail);

        result.getList().addAll(list);

        result.setTotal(600);
        result.setPage(page);
        result.setPagecount(20);

        result.setLimit(result.getList().size());
        return result;
    }

    public MovieList getPopularPlaylist(String tid) {
        String[] parts = tid.split("\\$");
        int page = Integer.parseInt(parts[3]);
        String url = POPULAR_API + page;
        log.debug("getPopularPlaylist: {}", url);
        BiliBiliHotResponse hotResponse = restTemplate.getForObject(url, BiliBiliHotResponse.class);
        log.debug("{}", hotResponse);
        List<BiliBiliInfo> list = hotResponse.getData().getList();

        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id("popular$0$0$" + page);
        movieDetail.setVod_name("合集" + page);
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_pic(LIST_PIC);
        movieDetail.setVod_play_from(BILI_BILI);
        String playUrl = list.stream().map(e -> fixTitle(e.getTitle()) + "$" + buildPlayUrl(e)).collect(Collectors.joining("#"));
        movieDetail.setVod_play_url(playUrl);
        movieDetail.setVod_content("共" + list.size() + "个视频");
        MovieList result = new MovieList();
        result.getList().add(movieDetail);

        return result;
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

    public MovieList getUpMedia(String mid, String sort, int page) {
        if (StringUtils.isBlank(sort)) {
            sort = "pubdate";
        }
        Map<String, Object> map = new HashMap<>();
        map.put("mid", mid);
        map.put("ps", "30");
        map.put("tid", "0");
        map.put("keyword", "");
        map.put("order", sort);
        map.put("platform", "web");
        map.put("order_avoided", "true");
        map.put("pn", String.valueOf(page));

        getKeys(buildHttpEntity(null));
        String url = NEW_SEARCH_API + "?" + Utils.encryptWbi(map, imgKey, subKey);
        log.debug("getUpMedia: {}", url);

        BiliBiliSearchInfoResponse response = restTemplate.getForObject(url, BiliBiliSearchInfoResponse.class);
        log.debug("{}", response);
        BiliBiliSearchInfo searchInfo = response.getData();
        List<MovieDetail> list = new ArrayList<>();
        MovieList result = new MovieList();
        for (BiliBiliSearchInfo.Video info : searchInfo.getList().getVlist()) {
            MovieDetail movieDetail = new MovieDetail();
            movieDetail.setVod_id(info.getBvid());
            movieDetail.setVod_name(info.getTitle());
            movieDetail.setVod_tag(FILE);
            movieDetail.setVod_pic(fixCover(info.getPic()));
            movieDetail.setVod_remarks(info.getLength());
            list.add(movieDetail);
        }

        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id("up$" + mid + "$" + sort + "$" + page);
        movieDetail.setVod_name("合集" + page);
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_pic(LIST_PIC);
        movieDetail.setVod_play_from(BILI_BILI);
        String playUrl = list.stream().map(e -> fixTitle(e.getVod_name()) + "$" + buildPlayUrl(e.getVod_id())).collect(Collectors.joining("#"));
        movieDetail.setVod_play_url(playUrl);
        movieDetail.setVod_content("共" + list.size() + "个视频");
        result.getList().add(movieDetail);

        result.getList().addAll(list);
        result.setLimit(result.getList().size());
        result.setTotal(searchInfo.getPage().getCount());
        result.setPagecount((searchInfo.getPage().getCount() + 29) / 30);
        log.debug("getUpMedia: {}", result);
        return result;
    }

    public MovieList getUpPlaylist(String tid) {
        String[] parts = tid.split("\\$");
        String id = parts[1];
        String sort = "new";
        if (parts.length > 2) {
            sort = parts[2];
        }
        String page = "1";
        if (parts.length > 3) {
            page = parts[3];
        }
        Map<String, Object> map = new HashMap<>();
        map.put("mid", id);
        map.put("ps", "30");
        map.put("tid", "0");
        map.put("keyword", "");
        map.put("order", sort);
        map.put("platform", "web");
        map.put("order_avoided", "true");
        map.put("pn", page);

        getKeys(buildHttpEntity(null));
        String url = NEW_SEARCH_API + "?" + Utils.encryptWbi(map, imgKey, subKey);
        log.debug("getUpMedia: {}", url);

        BiliBiliSearchInfoResponse response = restTemplate.getForObject(url, BiliBiliSearchInfoResponse.class);
        log.debug("getUpPlaylist: url {}", url, response);
        List<BiliBiliSearchInfo.Video> list = new ArrayList<>();
        List<BiliBiliSearchInfo.Video> videos = response.getData().getList().getVlist();
        list.addAll(videos);

        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id("up$" + id + "$0$" + page);
        movieDetail.setVod_name("合集" + page);
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_pic(LIST_PIC);
        movieDetail.setVod_play_from(BILI_BILI);
        String playUrl = list.stream().map(e -> fixTitle(e.getTitle()) + "$" + buildPlayUrl(e.getBvid())).collect(Collectors.joining("#"));
        movieDetail.setVod_play_url(playUrl);
        movieDetail.setVod_content("共" + list.size() + "个视频");
        MovieList result = new MovieList();
        result.getList().add(movieDetail);

        log.debug("getUpPlaylist: {}", result);
        return result;
    }

    private void getKeys(HttpEntity<Void> entity) {
        LocalDate now = LocalDate.now();
        if (keyTime == null || now.getDayOfYear() != keyTime.getDayOfYear()) {
            Map<String, Object> json = restTemplate.exchange(NAV_API, HttpMethod.GET, entity, Map.class).getBody();
            Map<String, Object> data = (Map<String, Object>) json.get("data");
            Map<String, Object> wbi = (Map<String, Object>) data.get("wbi_img");
            imgKey = getKey((String) wbi.get("img_url"));
            subKey = getKey((String) wbi.get("sub_url"));
            keyTime = LocalDate.now();
            log.info("get WBI key: {} {}", imgKey, subKey);
        }
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
        getKeys(entity);
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
        result.setTotal(1000);
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
        List<MovieDetail> list = new ArrayList<>();
        for (BiliBiliInfo info : hotResponse.getData().getArchives()) {
            MovieDetail movieDetail = getMovieDetail(info);
            list.add(movieDetail);
        }

        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id("region$" + tid + "$" + 0 + "$" + page);
        movieDetail.setVod_name("合集" + page);
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_pic(LIST_PIC);
        movieDetail.setVod_play_from(BILI_BILI);
        String playUrl = list.stream().map(e -> fixTitle(e.getVod_name()) + "$" + buildPlayUrl(e.getVod_id())).collect(Collectors.joining("#"));
        movieDetail.setVod_play_url(playUrl);
        movieDetail.setVod_content("共" + list.size() + "个视频");
        result.getList().add(movieDetail);

        result.getList().addAll(list);

        int total = hotResponse.getData().getPage().getCount();
        result.setLimit(result.getList().size());
        result.setTotal(total + total / size);
        result.setPagecount((result.getTotal() + size - 1 + total / size) / size);
        log.debug("getRegion: {} {}", url, result);
        return result;
    }

    public MovieList getRegionPlaylist(String tid) {
        String[] parts = tid.split("\\$");
        String id = parts[1];
        int page = Integer.parseInt(parts[3]);
        int size = 30;
        String url = String.format(REGION_API, size, id, page);
        log.debug("getRegionPlaylist: {}", url);
        HttpEntity<Void> entity = buildHttpEntity(null);
        ResponseEntity<BiliBiliListResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, BiliBiliListResponse.class);
        log.debug("{}", response.getBody());
        List<BiliBiliInfo> list = response.getBody().getData().getArchives();

        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id("region$" + id + "$0$" + page);
        movieDetail.setVod_name("合集" + page);
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_pic(LIST_PIC);
        movieDetail.setVod_play_from(BILI_BILI);
        String playUrl = list.stream().map(e -> fixTitle(e.getTitle()) + "$" + buildPlayUrl(e)).collect(Collectors.joining("#"));
        movieDetail.setVod_play_url(playUrl);
        movieDetail.setVod_content("共" + list.size() + "个视频");
        MovieList result = new MovieList();
        result.getList().add(movieDetail);

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
        log.debug("getHistory: {} {}", url, hotResponse);
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
        return result;
    }

    public MovieList getDetail(String bvid) throws IOException {
        log.debug("--- getDetail --- {}", bvid);
        if (bvid.startsWith("channel$")) {
            return getChannelPlaylist(bvid);
        }

        if (bvid.startsWith("search$")) {
            return getSearchPlaylist(bvid);
        }

        if (bvid.startsWith("up$")) {
            return getUpPlaylist(bvid);
        }

        if (bvid.startsWith("popular$")) {
            return getPopularPlaylist(bvid);
        }

        if (bvid.startsWith("region$")) {
            return getRegionPlaylist(bvid);
        }

        if (bvid.startsWith("type$")) {
            return getTypePlaylist(bvid);
        }

        if (bvid.startsWith("ss")) {
            return getBangumi(bvid.substring(2));
        }

        if (bvid.startsWith("season$")) {
            return getBangumi(bvid);
        }

        BiliBiliInfo info = getInfo(bvid);
        MovieDetail movieDetail = getMovieDetail(info, true);

        try {
            String url = String.format(RELATED_API, bvid);
            HttpEntity<Void> entity = buildHttpEntity(null);
            ResponseEntity<BiliBiliRelatedResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, BiliBiliRelatedResponse.class);
            List<BiliBiliInfo> list = response.getBody().getData();
            log.debug("related videos: {} {}", url, list);
            if (!list.isEmpty()) {
                movieDetail.setVod_play_from(BILI_BILI + "$$$相关视频");
                String related = list.stream().map(e -> fixTitle(e.getTitle()) + "$" + e.getAid() + "-" + e.getCid()).collect(Collectors.joining("#"));
                movieDetail.setVod_play_url(movieDetail.getVod_play_url() + "$$$" + related);
            }
        } catch (Exception e) {
            log.warn("", e);
        }

        MovieList result = new MovieList();
        result.getList().add(movieDetail);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        log.debug("--- detail --- {}", result);
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

        String[] parts = tid.split("\\$");
        String sid = parts.length == 1 ? tid : parts[1];
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
                movieDetail.setVod_pic(cover.isEmpty() ? LIST_PIC : fixCover(cover));
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

    public MovieList getSearchPlaylist(String tid) {
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
            String url = String.format(SEARCH_API, wd, getSort(type), "0", i + 1);
            ResponseEntity<BiliBiliSearchResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, BiliBiliSearchResponse.class);
            List<BiliBiliSearchResult.Video> videos = response.getBody().getData().getResult();
            list.addAll(videos);
        }

        searchPlaylist = new MovieDetail();
        searchPlaylist.setVod_id("search$" + wd + "$0$" + page);
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

    private <T> HttpEntity<T> buildHttpEntity(T data, boolean urlencoded) {
        return buildHttpEntity(data, urlencoded, new HashMap<>());
    }

    private <T> HttpEntity<T> buildHttpEntity(T data, Map<String, String> customHeaders) {
        return buildHttpEntity(data, false, customHeaders);
    }

    private <T> HttpEntity<T> buildHttpEntity(T data, boolean urlencoded, Map<String, String> customHeaders) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.REFERER, "https://api.bilibili.com/");
        headers.add(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7,ja;q=0.6,zh-TW;q=0.5");
        headers.add(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
        headers.add(HttpHeaders.USER_AGENT, "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36");
        if (urlencoded) {
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        }
        for (Map.Entry<String, String> entry : customHeaders.entrySet()) {
            headers.add(entry.getKey(), entry.getValue());
        }
        String defaultCookie = BiliBiliUtils.getCookie();
        String cookie = settingRepository.findById(BILIBILI_COOKIE).map(Setting::getValue).orElse("");
        if (StringUtils.isBlank(cookie)) {
            cookie = defaultCookie;
        }
        headers.add(HttpHeaders.COOKIE, cookie.trim());
        return new HttpEntity<>(data, headers);
    }

    private <T> HttpEntity<T> buildHttpEntity(T data) {
        return buildHttpEntity(data, false);
    }

    private BiliBiliInfo getInfo(String bvid) {
        BiliBiliInfoResponse infoResponse = restTemplate.getForObject(INFO_API + bvid, BiliBiliInfoResponse.class);
        log.debug("get info {} : {}", INFO_API + bvid, infoResponse);
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

    public Map<String, Object> getPlayUrl(String bvid, boolean dash) {
        String url;
        String aid;
        String cid;
        String[] parts = bvid.split("-");
        int fnval = 16;
        Map<String, Object> result = new HashMap<>();
        dash = dash || appProperties.isSupportDash();
        if (dash) {
            fnval = settingRepository.findById("bilibili_fnval").map(Setting::getValue).map(Integer::parseInt).orElse(FN_VAL);
        }
        if (parts.length > 2) {
            aid = parts[0];
            cid = parts[1];
            if (dash) {
                url = String.format(PLAY_API1, aid, cid, parts[2], fnval);
            } else {
                url = String.format(PLAY_API2, aid, cid, parts[2]);
            }
        } else if (parts.length == 2) {
            aid = parts[0];
            cid = parts[1];
            if (dash) {
                url = String.format(PLAY_API, aid, cid, fnval);
            } else {
                url = String.format(PLAY_API2, aid, cid);
            }
        } else {
            BiliBiliInfo info = getInfo(bvid);
            aid = String.valueOf(info.getAid());
            cid = String.valueOf(info.getCid());
            if (dash) {
                url = String.format(PLAY_API, aid, cid, fnval);
            } else {
                url = String.format(PLAY_API2, aid, cid);
            }
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
        } else {
            ResponseEntity<BiliBiliPlayResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, BiliBiliPlayResponse.class);
            BiliBiliPlayResponse res = response.getBody();
            log.debug("getPlayUrl url: {}  response: {}", url, res);
            if (res.getCode() != 0) {
                log.warn("获取失败: {} {}", res.getCode(), res.getMessage());
            }

            BiliBiliPlay data = res.getData() == null ? res.getResult() : res.getData();
            result.put("url", data.getDurl().get(0).getUrl());
        }

        result.put("subs", getSubtitles(aid, cid));

        if (appProperties.isHeartbeat()) {
            heartbeat(aid, cid);
        }

        log.debug("getPlayUrl: {} {}", url, result);
        return result;
    }

    private List<Sub> getSubtitles(String aid, String cid) {
        List<Sub> list = new ArrayList<>();
        try {
            String url = String.format("https://api.bilibili.com/x/player/v2?aid=%s&cid=%s", aid, cid);
            log.debug("getSubtitles {}", url);
            HttpEntity<Void> entity = buildHttpEntity(null);
            ResponseEntity<BiliBiliV2InfoResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, BiliBiliV2InfoResponse.class);
            for (BiliBiliV2Info.Subtitle subtitle : response.getBody().getData().getSubtitle().getSubtitles()) {
                Sub sub = new Sub();
                sub.setName(subtitle.getLan_doc());
                sub.setLang(subtitle.getLan());
                sub.setFormat("application/x-subrip");
                sub.setUrl(fixSubtitleUrl(subtitle.getSubtitle_url()));
                list.add(sub);
            }
        } catch (Exception e) {
            log.warn("", e);
        }
        log.debug("subtitles: {}", list);
        return list;
    }

    public String getSubtitle(String url) {
        StringBuilder text = new StringBuilder();
        try {
            HttpEntity<Void> entity = buildHttpEntity(null);
            ResponseEntity<SubtitleDataResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, SubtitleDataResponse.class);
            log.trace("subtitle: {}", response.getBody());
            int index = 1;
            for (SubtitleData subtitle : response.getBody().getBody()) {
                text
                        .append(index++)
                        .append("\n")
                        .append(secondsToTime(subtitle.getFrom()))
                        .append(" --> ")
                        .append(secondsToTime(subtitle.getTo()))
                        .append("\n")
                        .append(subtitle.getContent())
                        .append("\n\n");
            }
        } catch (Exception e) {
            log.warn("", e);
        }
        log.trace("subtitle result: {}", text);
        return text.toString();
    }

    private static String secondsToTime(String text) {
        int milliseconds = (int) (Double.parseDouble(text) * 1000);
        int hour = milliseconds / 3600000;
        int minute = (milliseconds - hour * 3600000) / 60000;
        int second = milliseconds / 1000 % 60;
        int milis = milliseconds % 1000;
        return String.format("%02d:%02d:%02d,%03d", hour, minute, second, milis);
    }

    private void heartbeat(String aid, String cid) {
        try {
            String csrf = "";
            String defaultCookie = BiliBiliUtils.getCookie();
            String cookie = settingRepository.findById(BILIBILI_COOKIE).map(Setting::getValue).orElse("");
            if (StringUtils.isBlank(cookie)) {
                cookie = defaultCookie;
            }
            String[] parts = cookie.split(";");
            for (String text : parts) {
                if (text.contains("bili_jct")) {
                    csrf = text.split("=")[1].trim();
                }
            }
            MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
            map.add("aid", aid);
            map.add("cid", cid);
            map.add("dt", "2");
            map.add("play_type", "1");
            map.add("played_time", "0");
            map.add("realtime", "0");
            map.add("refer_url", "https://www.bilibili.com/");
            map.add("csrf", csrf);
            map.add("start_ts", String.valueOf(Instant.now().getEpochSecond()));
            HttpEntity<MultiValueMap<String, String>> entity = buildHttpEntity(map, true);
            log.debug("request: {}", entity);
            String url = HEARTBEAT_API;
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            log.debug("heartbeat {}: {}", url, response.getBody());
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    private String buildPlayUrl(BiliBiliSeasonInfo info) {
        return info.getAid() + "-" + info.getCid() + "-" + info.getId();
    }

    private String buildPlayUrl(BiliBiliInfo info) {
        return info.getAid() + "-" + info.getCid();
    }

    private String buildPlayUrl(String bvid) {
        return bvid;
    }

    public MovieList getMovieList(String tid, String category, String type, String sort, String duration, int page) {
        if (tid.startsWith("search:")) {
            String[] parts = tid.split(":");
            return search(parts[1], sort, duration, page);
        } else if (tid.startsWith("channel:")) {
            String[] parts = tid.split(":");
            return getChannel(parts[1], sort, page);
        } else if (tid.startsWith("up:")) {
            String[] parts = tid.split(":");
            return getUpMedia(parts[1], sort, page);
        }

        if (StringUtils.isNotBlank(category)) {
            return getMovieListByType(category, type, page);
        }

        String[] parts = tid.split("\\$");
        MovieList result = new MovieList();
        List<BiliBiliInfo> list;
        if (parts.length == 1) {
            int rid = Integer.parseInt(tid);
            if (rid > 0 && "".equals(type)) {
                return getRegion(tid, page);
            }
            list = getHotRank("all", rid, page);
        } else if ("season".equals(parts[0])) {
            return getSeasonRank(Integer.parseInt(parts[1]), page);
        } else if ("pop".equals(parts[0])) {
            return getPopular(page);
        } else if ("history".equals(parts[0])) {
            return getHistory(page);
        } else if ("fav".equals(parts[0])) {
            return getFavList(tid, type, sort, page);
        } else if ("channel".equals(parts[0])) {
            return getChannels(type, page);
        } else if ("feed".equals(parts[0])) {
            return getFeeds(page);
        } else {
            int rid = Integer.parseInt(parts[1]);
            list = getHotRank(parts[0], rid, page);
        }

        for (BiliBiliInfo info : list) {
            MovieDetail movieDetail = getMovieDetail(info);
            result.getList().add(movieDetail);
        }

        result.setTotal(1000);
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

    private MovieList getFavList(String tid, String type, String sort, int page) {
        MovieList result = new MovieList();
        if (StringUtils.isBlank(sort)) {
            sort = "mtime";
        }

        if (StringUtils.isBlank(type)) {
            type = favId;
        }

        if (StringUtils.isBlank(type)) {
            String[] parts = tid.split("\\$");
            if (parts.length == 3) {
                type = parts[2];
            }
        }

        String url = String.format(FAV_API, type, sort, page);
        log.debug("getFavList: {}", url);
        HttpEntity<Void> entity = buildHttpEntity(null);
        ResponseEntity<BiliBiliFavItemsResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, BiliBiliFavItemsResponse.class);
        log.debug("{}", response.getBody());
        List<FavItem> items = response.getBody().getData().getMedias();
        if (items == null) {
            return result;
        }
        List<MovieDetail> list = new ArrayList<>();

        for (FavItem video : items) {
            MovieDetail movieDetail = getMovieDetail(video);
            list.add(movieDetail);
        }

        result.getList().addAll(list);

        int pages = page;
        if (response.getBody().getData().isHas_more()) {
            pages++;
        }

        int total = pages * 20;
        result.setTotal(total);
        result.setPage(page);
        result.setPagecount(pages);
        result.setLimit(result.getList().size());

        log.debug("{}", result);
        return result;
    }

    private MovieList getChannels(String tid, int page) {
        if (page == 1) {
            chanOffsets = new ArrayList<>();
            chanOffsets.add("");
        }
        MovieList result = new MovieList();
        if (chanOffsets.get(page - 1) == null) {
            return result;
        }
        String url = String.format(CHAN_API, tid, chanOffsets.get(page - 1));
        HttpEntity<Void> entity = buildHttpEntity(null);
        log.debug("getFeeds: {}", url);
        ResponseEntity<BiliBiliChannelListResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, BiliBiliChannelListResponse.class);
        ChannelList channelList = response.getBody().getData();
        for (ChannelArchives archives : channelList.getArchive_channels()) {
            for (ChannelArchive archive : archives.getArchives()) {
                MovieDetail movieDetail = new MovieDetail();
                movieDetail.setVod_id(archive.getBvid());
                movieDetail.setVod_director(archive.getAuthor_name());
                movieDetail.setVod_pic(archive.getCover());
                movieDetail.setVod_name(archive.getName());
                movieDetail.setVod_remarks(archive.getDuration());
                result.getList().add(movieDetail);
            }
        }
        if (channelList.isHas_more()) {
            chanOffsets.add(channelList.getOffset());
        } else {
            chanOffsets.add(null);
        }
        result.setPage(page);
        result.setLimit(result.getList().size());
        result.setTotal(channelList.getTotal());
        log.debug("{}", result);
        return result;
    }

    private MovieList getFeeds(int page) {
        if (page == 1) {
            feedOffsets = new ArrayList<>();
            feedOffsets.add("");
        }
        MovieList result = new MovieList();
        if (feedOffsets.get(page - 1) == null) {
            return result;
        }
        String url = String.format(FEED_API, feedOffsets.get(page - 1), page);
        HttpEntity<Void> entity = buildHttpEntity(null);
        log.debug("getFeeds: {}", url);
        ResponseEntity<JsonNode> response = restTemplate.exchange(url, HttpMethod.GET, entity, JsonNode.class);
        JsonNode node = response.getBody();
        log.trace("{}", node);
        ObjectNode data = (ObjectNode) node.get("data");
        if (data.get("has_more").asBoolean()) {
            feedOffsets.add(data.get("offset").asText());
        } else {
            feedOffsets.add(null);
        }
        ArrayNode items = (ArrayNode) data.get("items");
        for (int i = 0; i < items.size(); ++i) {
            JsonNode item = items.get(i);
            ObjectNode archive = (ObjectNode) getNodeByPath(item, "modules.module_dynamic.major.archive");
            MovieDetail movieDetail = new MovieDetail();
            movieDetail.setVod_id(archive.get("bvid").asText());
            movieDetail.setVod_pic(archive.get("cover").asText());
            movieDetail.setVod_name(archive.get("title").asText());
            movieDetail.setVod_remarks(archive.get("duration_text").asText());
            result.getList().add(movieDetail);
        }
        result.setTotal(1000);
        result.setLimit(result.getList().size());
        result.setPage(page);
        log.debug("{}", result);
        return result;
    }

    private JsonNode getNodeByPath(JsonNode root, String path) {
        JsonNode node = root;
        for (String key : path.split("\\.")) {
            node = node.get(key);
        }
        return node;
    }

    private MovieList getMovieListByType(String tid, String type, int page) {
        if ("".equals(type)) {
            return getRegion(tid, page);
        }
        MovieList result = new MovieList();
        BiliBiliVideoInfo rank = getRankList(tid, page);
        List<MovieDetail> list = new ArrayList<>();

        for (BiliBiliVideoInfo.Video video : rank.getResult()) {
            MovieDetail movieDetail = getMovieDetail(video);
            list.add(movieDetail);
        }

        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id("type$" + tid + "$" + type + "$" + page);
        movieDetail.setVod_name("合集" + page);
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_pic(LIST_PIC);
        movieDetail.setVod_play_from(BILI_BILI);
        String playUrl = list.stream().map(e -> fixTitle(e.getVod_name()) + "$" + buildPlayUrl(e.getVod_id())).collect(Collectors.joining("#"));
        movieDetail.setVod_play_url(playUrl);
        movieDetail.setVod_content("共" + list.size() + "个视频");
        result.getList().add(movieDetail);

        result.getList().addAll(list);

        int total = rank.getNumResults();
        result.setTotal(total + total / 30);
        result.setPage(page);
        result.setPagecount(rank.getNumPages() + total / 30);
        result.setLimit(result.getList().size());
        return result;
    }

    public MovieList getTypePlaylist(String tid) {
        String[] parts = tid.split("\\$");
        String id = parts[1];
        int page = Integer.parseInt(parts[3]);
        BiliBiliVideoInfo rank = getRankList(id, page);
        List<BiliBiliVideoInfo.Video> list = rank.getResult();

        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id("type$" + id + "$0$" + page);
        movieDetail.setVod_name("合集" + page);
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_pic(LIST_PIC);
        movieDetail.setVod_play_from(BILI_BILI);
        String playUrl = list.stream().map(e -> fixTitle(e.getTitle()) + "$" + buildPlayUrl(e.getBvid())).collect(Collectors.joining("#"));
        movieDetail.setVod_play_url(playUrl);
        movieDetail.setVod_content("共" + list.size() + "个视频");
        MovieList result = new MovieList();
        result.getList().add(movieDetail);

        return result;
    }

    private List<String> channelOffsets = new ArrayList<>();

    public MovieList getChannel(String id, String sort, int page) {
        if (page == 1) {
            channelOffsets = new ArrayList<>();
            channelOffsets.add("");
        }
        if (StringUtils.isBlank(sort)) {
            sort = "new";
        }
        MovieList result = new MovieList();
        if (channelOffsets.get(page - 1) == null) {
            return result;
        }
        HttpEntity<Void> entity = buildHttpEntity(null);
        String url = String.format(CHANNEL_API, id, sort, channelOffsets.get(page - 1));
        ResponseEntity<BiliBiliChannelResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, BiliBiliChannelResponse.class);
        log.debug("getChannel {} {}", url, response.getBody());

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
        movieDetail.setVod_id("channel$" + id + "$" + sort + "$" + page);
        movieDetail.setVod_name("合集" + page);
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_pic(LIST_PIC);  // TODO: cover
        movieDetail.setVod_play_from(BILI_BILI);
        String playUrl = list.stream().map(e -> fixTitle(e.getVod_name()) + "$" + buildPlayUrl(e.getVod_id())).collect(Collectors.joining("#"));
        movieDetail.setVod_play_url(playUrl);
        movieDetail.setVod_content("共" + list.size() + "个视频");
        result.getList().add(movieDetail);

        if (list.size() < 30) {
            channelOffsets.add(null);
        } else {
            channelOffsets.add(response.getBody().getData().getOffset());
        }
        result.getList().addAll(list);
        result.setTotal(1020);
        result.setLimit(result.getList().size());
        result.setHeader("{\"Referer\":\"https://www.bilibili.com\"}");
        log.debug("{}", result);
        return result;
    }

    public MovieList getChannelPlaylist(String tid) {
        String[] parts = tid.split("\\$");
        String id = parts[1];
        String sort = "new";
        if (parts.length > 2) {
            sort = parts[2];
        }
        int page = 1;
        if (parts.length > 3) {
            page = Integer.parseInt(parts[3]);
        }

        HttpEntity<Void> entity = buildHttpEntity(null);
        String url = String.format(CHANNEL_API, id, sort, channelOffsets.get(page - 1));
        ResponseEntity<BiliBiliChannelResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, BiliBiliChannelResponse.class);
        log.debug("getChannelPlaylist: url {}", url, response.getBody());
        List<BiliBiliChannelItem> list = new ArrayList<>();
        List<BiliBiliChannelItem> videos = response.getBody().getData().getList();
        list.addAll(videos);

        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id("channel$" + id + "$0$" + page);
        movieDetail.setVod_name("合集" + page);
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_pic(LIST_PIC);
        movieDetail.setVod_play_from(BILI_BILI);
        String playUrl = list.stream().map(e -> fixTitle(e.getName()) + "$" + buildPlayUrl(e.getBvid())).collect(Collectors.joining("#"));
        movieDetail.setVod_play_url(playUrl);
        movieDetail.setVod_content("共" + list.size() + "个视频");
        MovieList result = new MovieList();
        result.getList().add(movieDetail);

        log.debug("getChannelPlaylist: {}", result);
        return result;
    }

    public MovieList search(String wd, String sort, String duration, int pg) {
        MovieList result = new MovieList();
        if (!appProperties.isSearchable()) {
            return result;
        }
        HttpEntity<Void> entity = buildHttpEntity(null);

        List<BiliBiliSearchResult.Video> list = new ArrayList<>();

        int pages = 1;
        if (pg > 0) {
            String url = String.format(SEARCH_API, wd, sort, duration, pg);
            log.debug("{}", url);
            ResponseEntity<BiliBiliSearchResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, BiliBiliSearchResponse.class);
            List<BiliBiliSearchResult.Video> videos = response.getBody().getData().getResult();

            keyword = wd;
            searchPage = pg - 1;
            MovieDetail movieDetail = new MovieDetail();
            movieDetail.setVod_id("search$" + wd + "$" + getType(sort) + "$" + (pg - 1));
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
            result.setPagecount(response.getBody().getData().getNumPages());
        } else {
            for (int i = 1; i <= 2; i++) {
                String url = String.format(SEARCH_API, wd, sort, duration, i);
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
                movieDetail.setVod_id("search$" + wd + "$0$" + i);
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
        }

        for (BiliBiliSearchResult.Video info : list) {
            MovieDetail movieDetail = getSearchMovieDetail(info);
            result.getList().add(movieDetail);
        }

        result.setTotal(1020);
        result.setLimit(result.getList().size());
        log.debug("result: {}", result);
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
            case "scores":
                return 6;
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
            case 6:
                return "scores";
            default:
                return "";
        }
    }

    private MovieDetail getSearchMovieDetail(BiliBiliSearchResult.Video info) {
        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(info.getBvid());
        movieDetail.setVod_name(fixTitle(info.getTitle()));
        movieDetail.setVod_tag(FILE);
        movieDetail.setType_name(info.getTypename());
        movieDetail.setVod_pic(fixCover(info.getPic()));
        movieDetail.setVod_remarks(info.getDuration());
        return movieDetail;
    }

    private static String fixTitle(String title) {
        return title
                .replace("#", " ")
                .replace("$", "")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("<em class=\"keyword\">", "")
                .replace("</em>", "");
    }

    private static String fixCover(String cover) {
        String url = fixUrl(cover);
        if (url != null && url.endsWith(".jpg")) {
            url += "@150h";
        }
        return url;
    }

    private static String fixSubtitleUrl(String url) {
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .replacePath("/subtitles")
                .query("url=" + fixUrl(url))
                .build()
                .toUriString();
    }

    private static String fixUrl(String url) {
        if (url != null && url.startsWith("//")) {
            return "https:" + url;
        }
        return url;
    }
}
