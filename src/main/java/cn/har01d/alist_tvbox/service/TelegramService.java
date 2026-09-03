package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.DriveId;
import cn.har01d.alist_tvbox.domain.SearchTargets;
import cn.har01d.alist_tvbox.dto.ShareLink;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.dto.tg.SearchResponse;
import cn.har01d.alist_tvbox.dto.tg.SearchResult;
import cn.har01d.alist_tvbox.entity.Movie;
import cn.har01d.alist_tvbox.entity.MovieRepository;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.TelegramChannel;
import cn.har01d.alist_tvbox.entity.TelegramChannelRepository;
import cn.har01d.alist_tvbox.model.Filter;
import cn.har01d.alist_tvbox.model.FilterValue;
import cn.har01d.alist_tvbox.tvbox.Category;import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.util.TextUtils;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.regex.Matcher;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.ExampleMatcher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static cn.har01d.alist_tvbox.util.Constants.FOLDER;

@Slf4j
@Service
public class TelegramService {
    private final AppProperties appProperties;
    private final TelegramChannelRepository telegramChannelRepository;
    private final SettingRepository settingRepository;
    private final MovieRepository movieRepository;
    private final ShareService shareService;
    private final TvBoxService tvBoxService;
    private final RemoteSearchService remoteSearchService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService = Executors.newFixedThreadPool(Math.min(10, Runtime.getRuntime().availableProcessors() * 2));
    private final OkHttpClient httpClient = new OkHttpClient();
    private final LoadingCache<String, List<Message>> searchCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(15)).build(this::getFromChannel);
    private final Cache<String, MovieList> douban = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(1)).build();
    private final Cache<String, String> lastId = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(1)).build();
    private final Cache<String, MovieDetail> movies = Caffeine.newBuilder().maximumSize(200).expireAfterWrite(Duration.ofHours(2)).build();
    private final Cache<String, String> videoName = Caffeine.newBuilder().maximumSize(200).expireAfterWrite(Duration.ofHours(2)).build();
    private final List<String> fields = new ArrayList<>(List.of("id", "name", "genre", "description", "language", "country", "directors", "editors", "actors", "cover", "dbScore", "year"));
    private final List<FilterValue> filters = Arrays.asList(
            new FilterValue("原始顺序", ""),
            new FilterValue("评分⬇️", "dbScore,desc;year,desc"),
            new FilterValue("评分⬆️", "dbScore,asc;year,desc"),
            new FilterValue("年份⬇️", "year,desc;dbScore,desc"),
            new FilterValue("年份⬆️", "year,asc;dbScore,desc"),
            new FilterValue("名字⬇️", "name,desc;year,desc;dbScore,desc"),
            new FilterValue("名字⬆️", "name,asc;year,desc;dbScore,desc"),
            new FilterValue("类型⬇️", "genre,desc;year,desc;dbScore,desc"),
            new FilterValue("类型⬆️", "genre,asc;year,desc;dbScore,desc"),
            new FilterValue("地区⬇️", "country,desc;year,desc;dbScore,desc"),
            new FilterValue("地区⬆️", "country,asc;year,desc;dbScore,desc"),
            new FilterValue("语言⬇️", "language,desc;year,desc;dbScore,desc"),
            new FilterValue("语言⬆️", "language,asc;year,desc;dbScore,desc"),
            new FilterValue("ID⬇️", "id,desc"),
            new FilterValue("ID⬆️", "id,asc")
    );
    private final List<FilterValue> filters2 = Arrays.asList(
            new FilterValue("全部类型", ""),
            new FilterValue("喜剧", "喜剧"),
            new FilterValue("爱情", "爱情"),
            new FilterValue("动作", "动作"),
            new FilterValue("科幻", "科幻"),
            new FilterValue("动画", "动画"),
            new FilterValue("悬疑", "悬疑"),
            new FilterValue("冒险", "冒险"),
            new FilterValue("家庭", "家庭"),
            new FilterValue("剧情", "剧情"),
            new FilterValue("历史", "历史"),
            new FilterValue("奇幻", "奇幻"),
            new FilterValue("音乐", "音乐"),
            new FilterValue("歌舞", "歌舞"),
            new FilterValue("惊悚", "惊悚"),
            new FilterValue("恐怖", "恐怖"),
            new FilterValue("犯罪", "犯罪"),
            new FilterValue("灾难", "灾难"),
            new FilterValue("战争", "战争"),
            new FilterValue("传记", "传记"),
            new FilterValue("武侠", "武侠"),
            new FilterValue("情色", "情色"),
            new FilterValue("西部", "西部"),
            new FilterValue("真人秀", "真人秀"),
            new FilterValue("脱口秀", "脱口秀"),
            new FilterValue("纪录片", "纪录片"),
            new FilterValue("短片", "短片")
    );
    private final List<FilterValue> filters3 = Arrays.asList(
            new FilterValue("全部地区", ""),
            new FilterValue("中国", "中国"),
            new FilterValue("中国大陆", "中国大陆"),
            new FilterValue("中国香港", "中国香港"),
            new FilterValue("中国台湾", "中国台湾"),
            new FilterValue("美国", "美国"),
            new FilterValue("英国", "英国"),
            new FilterValue("韩国", "韩国"),
            new FilterValue("日本", "日本"),
            new FilterValue("法国", "法国"),
            new FilterValue("德国", "德国"),
            new FilterValue("意大利", "意大利"),
            new FilterValue("西班牙", "西班牙"),
            new FilterValue("印度", "印度"),
            new FilterValue("泰国", "泰国"),
            new FilterValue("俄罗斯", "俄罗斯"),
            new FilterValue("加拿大", "加拿大"),
            new FilterValue("澳大利亚", "澳大利亚"),
            new FilterValue("爱尔兰", "爱尔兰"),
            new FilterValue("瑞典", "瑞典"),
            new FilterValue("巴西", "巴西"),
            new FilterValue("丹麦", "丹麦")
    );

    public TelegramService(AppProperties appProperties,
                           TelegramChannelRepository telegramChannelRepository,
                           SettingRepository settingRepository,
                           MovieRepository movieRepository,
                           ShareService shareService,
                           TvBoxService tvBoxService,
                           RemoteSearchService remoteSearchService,
                           RestTemplateBuilder restTemplateBuilder,
                           ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        this.telegramChannelRepository = telegramChannelRepository;
        this.settingRepository = settingRepository;
        this.movieRepository = movieRepository;
        this.shareService = shareService;
        this.tvBoxService = tvBoxService;
        this.remoteSearchService = remoteSearchService;
        this.restTemplate = restTemplateBuilder.build();
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        if (telegramChannelRepository.count() == 0) {
            try {
                var channels = loadChannels();
                telegramChannelRepository.saveAll(channels);
            } catch (Exception e) {
                log.warn("read channels error", e);
            }
        } else {
            telegramChannelRepository.deleteById(1618421074L);
        }
    }

    public List<TelegramChannel> reloadChannels() throws IOException {
        telegramChannelRepository.deleteAll();
        var channels = loadChannels();
        for (var channel : channels) {
            validateWebAccess(channel);
        }
        telegramChannelRepository.saveAll(channels);
        log.info("reload channels success");
        return channels;
    }

    private List<TelegramChannel> loadChannels() throws IOException {
        var resource = new ClassPathResource("channels.json");
        return objectMapper.readValue(resource.getInputStream(), new TypeReference<List<TelegramChannel>>() {
        });
    }

    public List<TelegramChannel> validateChannels() {
        var channels = list();
        if (StringUtils.isNotBlank(appProperties.getTgSearch())) {
            try {
                getTgSearchHealth();
            } catch (Exception e) {
                log.warn("validate channels error", e);
            }
        }

        for (var channel : channels) {
            validateWebAccess(channel);
        }
        telegramChannelRepository.saveAll(channels);
        log.info("validate channels success");
        return channels;
    }

    private void validateWebAccess(TelegramChannel channel) {
        try {
            List<String> items = searchWeb(channel.getUsername(), "");
            channel.setWebAccess(!items.isEmpty());
        } catch (Exception e) {
            log.warn("Access channel by web failed: {} {}", channel.getTitle(), e.getMessage());
        }
    }

    public TelegramChannel create(TelegramChannel channel) {
        if (channel.getUsername().startsWith("https://t.me/")) {
            channel.setUsername(channel.getUsername().substring(13));
        }
        var chat = getChannelByName(channel.getUsername());
        if (chat != null) {
            chat.setEnabled(channel.isEnabled());
            chat.setSortOrder(channel.getSortOrder());
            chat.setType(channel.getType());
            validateWebAccess(chat);
            telegramChannelRepository.save(chat);
        }
        return chat;
    }

    public List<TelegramChannel> list() {
        return telegramChannelRepository.findAll(Sort.by("sortOrder"));
    }

    public ObjectNode getTgSearchHealth() {
        if (StringUtils.isBlank(appProperties.getTgSearch())) {
            ObjectNode result = objectMapper.createObjectNode();
            result.put("service", "unconfigured");
            result.put("message", "tg-search api url is blank");
            return result;
        }
        String api = normalizeTgSearchUrl("/api/health");
        HttpEntity<Void> entity = new HttpEntity<>(null, buildTgSearchHeaders());
        return restTemplate.exchange(api, HttpMethod.GET, entity, ObjectNode.class).getBody();
    }

    public Map<String, Object> searchZx(String keyword, String username) {
        String[] channels = username.split(",");
        List<Future<List<Message>>> futures = new ArrayList<>();
        for (String channel : channels) {
            Future<List<Message>> future = executorService.submit(() -> searchFromChannel(channel, keyword, false, 100));
            futures.add(future);
        }
        long startTime = System.currentTimeMillis();

        int total = 0;
        List<String> result = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            long remaining = Math.max(1, appProperties.getTgTimeout() - (System.currentTimeMillis() - startTime));
            Future<List<Message>> future = futures.get(i);
            String channel = channels[i];
            try {
                List<Message> list = future.get(remaining, TimeUnit.MILLISECONDS);
                total += list.size();
                result.add(channel + "$$$" + list.stream().filter(e -> e.getContent().contains("http")).map(Message::toZxString).collect(Collectors.joining("##")));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Search interrupted for channel: {}", channel);
                break;
            } catch (ExecutionException | TimeoutException e) {
                log.warn("", e);
            }
        }

        log.info("Search TG zx get {} results.", total);
        return Map.of("results", result);
    }

    public String searchPg(String keyword, String username, String encode) {
        if (StringUtils.isNotBlank(appProperties.getPanSouUrl())) {
            List<String> channels = list().stream()
                    .filter(TelegramChannel::isValid)
                    .filter(TelegramChannel::isEnabled)
                    .map(TelegramChannel::getUsername)
                    .toList();
            return remoteSearchService.searchPg(keyword, channels, encode);
        }

        log.info("search {} from channels {}", keyword, username);
        List<Message> results = List.of();
        if (StringUtils.isNotBlank(appProperties.getTgSearch())) {
            results = searchRemote(username, keyword, 100);
        }

        if (results.isEmpty()) {
            String[] channels = username.split(",");
            List<Future<List<Message>>> futures = new ArrayList<>();
            for (String channel : channels) {
                String name = channel.split("\\|")[0];
                Future<List<Message>> future = executorService.submit(() -> searchFromChannel(name, keyword, false, 100));
                futures.add(future);
            }

            results = getResult(futures);
        }

        log.info("Search TG pg get {} results.", results.size());
        return results.stream()
                .map(Message::toPgString)
                .map(e -> {
                    if ("1".equals(encode)) {
                        return Base64.getEncoder().encodeToString(e.getBytes());
                    }
                    return e;
                })
                .collect(Collectors.joining("\n"));
    }

    public MovieList detail(String tid, String ac, String title) {
        return detail(tid, ac, title, null);
    }

    public MovieList detail(String tid, String ac, String title, String keyword) {
        if (tid.startsWith("%2Fv%2F")) {
            tid = StringUtils.trimToEmpty(URLDecoder.decode(tid, StandardCharsets.UTF_8));
        }
        if (tid.startsWith("/v/")) {
            MovieList list = new MovieList();
            MovieDetail detail = new MovieDetail();
            detail.setVod_id(getVid(tid));
            detail.setVod_name("视频");
            String name = videoName.getIfPresent(getVid(tid));
            if (name != null) {
                detail.setVod_name(name);
            }
            detail.setVod_play_from("电报");
            detail.setVod_play_url(resolveTgSearchMediaUrl(tid));
            list.getList().add(detail);
            log.debug("{}", list);
            return list;
        }

        ShareLink share = new ShareLink();
        share.setLink(tid);
        String path = shareService.add(share);

        if (StringUtils.isBlank(title)) {
            MovieDetail movie = movies.getIfPresent(tid);
            if (movie != null) {
                title = movie.getVod_name();
            }
        }

        MovieList movieList = tvBoxService.getDetail(ac, "1$" + path + "/~playlist", title, keyword, 0);
        log.debug("{}", movieList);
        return movieList;
    }

    private String encodeUrl(String url) {
        return URLEncoder.encode(url, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public CategoryList category(boolean web) {
        CategoryList result = new CategoryList();
        List<Category> list = new ArrayList<>();

        for (String type : appProperties.getTgDrivers()) {
            var category = new Category();
            category.setType_id("type:" + type);
            category.setType_name(getTypeName(type));
            category.setType_flag(0);
            list.add(category);
        }

        List<TelegramChannel> channels;
        if (web || (StringUtils.isBlank(appProperties.getTgSearch()))) {
            channels = telegramChannelRepository.findByWebAccessTrue(Sort.by("sortOrder"));
        } else {
            channels = list();
        }

        for (var channel : channels) {
            if (!channel.isValid()) {
                continue;
            }
            var category = new Category();
            category.setType_id(channel.getUsername());
            category.setType_name(channel.getTitle());
            category.setType_flag(0);
            list.add(category);
        }

        result.setCategories(list);
        result.setTotal(result.getCategories().size());
        result.setLimit(result.getCategories().size());

        log.debug("category result: {}", result);
        return result;
    }

    public MovieList list(String channel, boolean web, int pg) throws IOException {
        if (channel.startsWith("type:")) {
            return loadMovies(channel.substring(5), web);
        }

        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        List<Message> messages;
        if (web) {
            messages = loadFromWeb(channel, pg);
        } else if (StringUtils.isNotBlank(appProperties.getTgSearch())) {
            messages = searchRemote(channel, "", 100);
        } else {
            messages = searchFromChannel(channel, "", false, 100);
        }

        for (Message message : messages) {
            if (appProperties.getTgDrivers().isEmpty() || appProperties.getTgDrivers().contains(message.getType())) {
                list.add(toMovieDetail(message));
            }
        }

        result.setList(list);
        if (web) {
            result.setTotal(999);
            result.setPagecount(100);
        } else {
            result.setTotal(list.size());
        }
        result.setLimit(list.size());

        log.debug("list result: {}", result);
        return result;
    }

    public MovieList loadMovies(String type, boolean web) {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        List<Message> messages = search("", 100, web, true);
        for (Message message : messages) {
            if (type.equals(message.getType())) {
                list.add(toMovieDetail(message));
            }
        }

        log.debug("Search results: {}", list.subList(0, Math.min(list.size(), 30)));

        result.setList(list);
        result.setTotal(list.size());
        result.setLimit(list.size());

        return result;
    }

    public MovieList searchMovies(String keyword, boolean web, int size) {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        List<Message> messages = search(keyword, size, web, false);
        for (Message message : messages) {
            list.add(toMovieDetail(message));
        }

        log.debug("Search results: {} {}", keyword, list.subList(0, Math.min(list.size(), 30)));

        result.setList(list);
        result.setTotal(list.size());
        result.setLimit(list.size());

        return result;
    }

    public CategoryList categoryTgSearch() {
        CategoryList result = new CategoryList();
        List<Category> list = new ArrayList<>();

        for (String type : appProperties.getTgDrivers()) {
            var category = new Category();
            category.setType_id("type:" + type);
            category.setType_name(getTypeName(type));
            category.setType_flag(0);
            list.add(category);
        }

        result.setCategories(list);
        result.setTotal(list.size());
        result.setLimit(list.size());
        return result;
    }

    public MovieList listTgSearch(String type, int page, int size) {
        String keyword = "";
        String filterType = StringUtils.isNotBlank(type) && type.startsWith("type:") ? type.substring(5) : type;
        TgSearchResult searchResult = searchTgSearchApi(keyword, getCloudType(filterType), page, size);
        List<Message> messages = searchResult.messages();
        if (StringUtils.isNotBlank(filterType)) {
            messages = messages.stream().filter(message -> filterType.equals(message.getType())).toList();
        }
        return toMovieList(new TgSearchResult(messages, searchResult.total()), page, size);
    }

    public MovieList searchTgSearchMovies(String keyword, int page, int size) {
        return toMovieList(searchTgSearchApi(keyword, null, page, size), page, size);
    }

    private MovieList toMovieList(TgSearchResult searchResult, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, size);
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();
        List<String> tgDrivers = appProperties.getTgDrivers();
        for (Message message : remoteSearchService.filterInvalidPanSouLinks(searchResult.messages())) {
            if (tgDrivers.isEmpty() || tgDrivers.contains(message.getType())) {
                list.add(toMovieDetail(message));
            }
        }
        result.setList(list);
        result.setPage(safePage);
        result.setTotal(searchResult.total());
        result.setLimit(safeSize);
        result.setPagecount(Math.max(1, (searchResult.total() + safeSize - 1) / safeSize));
        if (log.isDebugEnabled()) {
            log.debug("list result: {}", Utils.toJsonString(result));
        }
        return result;
    }

    private String getVid(String link) {
        int index = link.indexOf("?");
        if (index != -1) {
            return link.substring(0, index);
        }
        return link;
    }

    public CategoryList categoryDouban() {
        CategoryList result = new CategoryList();
        List<Category> list = new ArrayList<>();

        {
            var category = new Category();
            category.setType_id("hot_tv");
            category.setType_name("热门电视剧");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("hot_movie");
            category.setType_name("热门电影");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("tv_domestic");
            category.setType_name("国产剧");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("tv_american");
            category.setType_name("欧美剧");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("tv_animation");
            category.setType_name("动漫");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("tv_variety_show");
            category.setType_name("综艺");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("tv_korean");
            category.setType_name("韩剧");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("tv_japanese");
            category.setType_name("日剧");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("suggestion_movie");
            category.setType_name("电影推荐");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("suggestion_tv");
            category.setType_name("电视剧推荐");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("movie_top250");
            category.setType_name("电影Top250");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("movie_real_time_hotest");
            category.setType_name("实时热门电影");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("movie_weekly_best");
            category.setType_name("一周口碑电影榜");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("tv_real_time_hotest");
            category.setType_name("实时热门电视");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("tv_chinese_best_weekly");
            category.setType_name("华语口碑剧集榜");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("tv_global_best_weekly");
            category.setType_name("全球口碑剧集榜");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("show_chinese_best_weekly");
            category.setType_name("国内口碑综艺榜");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("show_global_best_weekly");
            category.setType_name("国外口碑综艺榜");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("local");
            category.setType_name("浏览");
            category.setType_flag(0);
            list.add(category);
            List<FilterValue> years = new ArrayList<>();
            years.add(new FilterValue("全部", ""));
            int year = LocalDate.now().getYear();
            for (int i = 0; i < 20; ++i) {
                years.add(new FilterValue(String.valueOf(year - i), String.valueOf(year - i)));
            }
            result.getFilters().put("local", List.of(new Filter("sort", "排序", filters), new Filter("genre", "类型", filters2), new Filter("region", "地区", filters3), new Filter("year", "年份", years)));
        }

        {
            var category = new Category();
            category.setType_id("random");
            category.setType_name("随便看看");
            category.setType_flag(0);
            list.add(category);
        }

        result.setCategories(list);
        result.setTotal(result.getCategories().size());
        result.setLimit(result.getCategories().size());

        log.debug("category result: {}", result);
        return result;
    }

    public MovieList listDouban(String type, String ac, String sort, Integer year, String genre, String region, int page, int size) {
        if (type.startsWith("s:")) {
            return searchMovies(type.substring(2), false, size);
        }

        return getDoubanList(type, ac, sort, year, genre, region, page, size);
    }

    private MovieList getDoubanList(String type, String ac, String sort, Integer year, String genre, String region, int page, int size) {
        String key = ac + "-" + type + "-" + page + "-" + StringUtils.defaultString(sort) + "-" + year
                + "-" + StringUtils.defaultString(genre) + "-" + StringUtils.defaultString(region);
        MovieList result = douban.getIfPresent(key);
        if (result != null) {
            return result;
        }

        if (type.equals("local")) {
            return getLocalMovieList(ac, sort, year, genre, region, page, size);
        }

        if (type.equals("random")) {
            return getRandomMovie(ac, size);
        }

        if (type.startsWith("suggestion_")) {
            return getDoubanItems(type, ac, page, size, region);
        }

        if (type.startsWith("hot_")) {
            return getDoubanItems(type, ac, page, size, region);
        }

        result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        int start = (page - 1) * size;
        String url = "https://m.douban.com/rexxar/api/v2/subject_collection/" + type + "/items?os=linux&for_mobile=1&callback=&start=" + start + "&count=" + size + "&loc_id=108288&_=0";
        HttpEntity<Void> httpEntity = buildHttpEntity();

        var response = restTemplate.exchange(url, HttpMethod.GET, httpEntity, JsonNode.class);
        int total = response.getBody().get("total").asInt();
        ArrayNode items = (ArrayNode) response.getBody().get("subject_collection_items");
        for (JsonNode item : items) {
            MovieDetail movieDetail = getMovieDetail(item);
            if ("web".equals(ac)) {
                fixCover(movieDetail);
                movieDetail.setCate(null);
            }
            list.add(movieDetail);
        }

        result.setList(list);
        result.setLimit(list.size());
        result.setTotal(total);
        result.setPagecount((total + size - 1) / size);

        douban.put(key, result);
        log.debug("list result: {}", result);
        return result;
    }

    private void fixCover(MovieDetail movie) {
        if (StringUtils.isEmpty(movie.getVod_pic())) {
            return;
        }
        // 相对地址交给浏览器按页面源补全:fromCurrentRequest 在 https 反代未开 enable_https 时会拼出
        // http:// 封面被按混合内容拦截(TMDB 直链不受影响),换域名/端口也不会再拼错
        movie.setVod_pic("/images?url=" + movie.getVod_pic());
    }

    private MovieList getLocalMovieList(String ac, String sort, Integer year, String genre, String region, int page, int size) {
        MovieList result;
        result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        Pageable pageable;
        if (StringUtils.isNotBlank(sort)) {
            List<Sort.Order> orders = new ArrayList<>();
            for (String item : sort.split(";")) {
                String[] parts = item.split(",");
                Sort.Order order = parts[1].equals("asc") ? Sort.Order.asc(parts[0]) : Sort.Order.desc(parts[0]);
                orders.add(order);
            }
            pageable = PageRequest.of(page - 1, size, Sort.by(orders));
        } else {
            pageable = PageRequest.of(page - 1, size);
        }

        Page<Movie> res = searchMovies(year, genre, region, pageable);
        int total = (int) res.getTotalElements();

        for (Movie movie : res) {
            MovieDetail movieDetail = new MovieDetail();
            movieDetail.setVod_id(PianDanService.subjectId(movie.getName(), movie.getYear()));
            movieDetail.setVod_name(movie.getName());
            movieDetail.setVod_pic(movie.getCover());
            movieDetail.setVod_remarks(movie.getDbScore());
            if (movie.getYear() != null) {
                movieDetail.setVod_year(String.valueOf(movie.getYear()));
            }
            movieDetail.setVod_tag(FOLDER);
            if ("web".equals(ac)) {
                fixCover(movieDetail);
            } else {
                movieDetail.setCate(new CategoryList());
            }
            list.add(movieDetail);
        }

        result.setList(list);
        result.setLimit(list.size());
        result.setTotal(total);
        result.setPagecount(res.getTotalPages());

        log.debug("list result: {}", result);
        return result;
    }

    public Page<Movie> searchMovies(Integer year, String genre, String region, Pageable pageable) {
        ExampleMatcher matcher = ExampleMatcher.matching()
                .withStringMatcher(ExampleMatcher.StringMatcher.CONTAINING)
                .withIgnoreNullValues();

        Movie movie = new Movie();
        if (year != null) {
            movie.setYear(year);
        }
        if (StringUtils.isNotBlank(genre)) {
            movie.setGenre(genre);
        }
        if (StringUtils.isNotBlank(region)) {
            movie.setCountry(region);
        }
        Example<Movie> example = Example.of(movie, matcher);
        return movieRepository.findAll(example, pageable);
    }

    private MovieList getRandomMovie(String ac, int size) {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        int total = (int) movieRepository.count();
        int count = size + size / 2;
        int page = ThreadLocalRandom.current().nextInt(total / count);
        Collections.shuffle(fields);
        List<Sort.Order> orders = fields.stream().limit(3).map(e -> ThreadLocalRandom.current().nextBoolean() ? Sort.Order.asc(e) : Sort.Order.desc(e)).toList();
        Sort sort = Sort.by(orders);
        Pageable pageable = PageRequest.of(page, count, sort);
        Page<Movie> res = movieRepository.findAll(pageable);

        List<Movie> movies = new ArrayList<>(res.getContent());
        Collections.shuffle(movies);

        for (Movie movie : movies.subList(0, size)) {
            MovieDetail movieDetail = new MovieDetail();
            movieDetail.setVod_id(PianDanService.subjectId(movie.getName(), movie.getYear()));
            movieDetail.setVod_name(movie.getName());
            movieDetail.setVod_pic(movie.getCover());
            movieDetail.setVod_remarks(movie.getDbScore());
            if (movie.getYear() != null) {
                movieDetail.setVod_year(String.valueOf(movie.getYear()));
            }
            movieDetail.setVod_tag(FOLDER);
            if ("web".equals(ac)) {
                fixCover(movieDetail);
            } else {
                movieDetail.setCate(new CategoryList());
            }
            list.add(movieDetail);
        }

        result.setList(list);
        result.setLimit(list.size());
        result.setTotal(total);
        result.setPagecount((total + size - 1) / size);

        log.debug("list result: {}", result);
        return result;
    }

    private MovieList getDoubanItems(String type, String ac, int page, int size, String region) {
        String key = ac + "-" + type + "-" + page + "-" + StringUtils.defaultString(region);
        int start = (page - 1) * size;
        String url = "https://m.douban.com/rexxar/api/v2/subject/recent_hot/movie?limit=" + size + "&start=" + start;
        if (type.equals("hot_tv")) {
            url = "https://m.douban.com/rexxar/api/v2/subject/recent_hot/tv?limit=" + size + "&start=" + start;
        } else if (type.equals("suggestion_movie")) {
            url = "https://m.douban.com/rexxar/api/v2/movie/suggestion?start=" + start + "&count=" + size + "&new_struct=1&with_review=1&for_mobile=1";
        } else if (type.equals("suggestion_tv")) {
            url = "https://m.douban.com/rexxar/api/v2/tv/suggestion?start=" + start + "&count=" + size + "&new_struct=1&with_review=1&for_mobile=1";
        }
        // 近期热播接口不支持地区参数;带地区改走 discover 式 recommend(tags 语法,单国家粒度)
        if (StringUtils.isNotBlank(region) && (type.equals("hot_tv") || type.equals("hot_movie"))) {
            String tags = URLEncoder.encode((type.equals("hot_tv") ? "电视剧" : "电影") + "," + region, StandardCharsets.UTF_8);
            // sort=U 近期热度:recommend 默认综合排序全是经典老剧,与热门榜单语义不符
            url = "https://m.douban.com/rexxar/api/v2/" + (type.equals("hot_tv") ? "tv" : "movie")
                    + "/recommend?refresh=0&start=" + start + "&limit=" + size + "&uncollect=false&sort=U&tags=" + tags;
        }

        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        HttpEntity<Void> httpEntity = buildHttpEntity();

        // tags 已 URLEncoder 预编码:传 String 会经 uriTemplateHandler 二次编码(%→%25)使 tags 变乱码,
        // 豆瓣按乱码 tag 匹配 total=0(地区筛选全空);传 URI 跳过模板编码
        var response = restTemplate.exchange(URI.create(url), HttpMethod.GET, httpEntity, JsonNode.class);
        int total = response.getBody().get("total").asInt();
        ArrayNode items = (ArrayNode) response.getBody().get("items");
        for (JsonNode item : items) {
            MovieDetail movieDetail = getMovieDetail(item);
            if ("web".equals(ac)) {
                fixCover(movieDetail);
                movieDetail.setCate(null);
            }
            list.add(movieDetail);
        }

        result.setList(list);
        result.setLimit(list.size());
        result.setTotal(total);
        result.setPagecount((total + size - 1) / size);

        douban.put(key, result);
        log.debug("list result: {}", result);
        return result;
    }

    private static HttpEntity<Void> buildHttpEntity() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7,ja;q=0.6,zh-TW;q=0.5");
        headers.set(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
        headers.set(HttpHeaders.REFERER, "https://movie.douban.com/");
        headers.set(HttpHeaders.USER_AGENT, Utils.getUserAgent());
        return new HttpEntity<>(null, headers);
    }

    private static MovieDetail getMovieDetail(JsonNode item) {
        double score = item.get("rating").get("value").asDouble();
        MovieDetail movieDetail = new MovieDetail();
        String title = item.get("title").asText();
        Integer year = parseYear(item.path("year").asText(item.path("card_subtitle").asText("")));
        movieDetail.setVod_id(PianDanService.subjectId(title, year));
        movieDetail.setVod_name(title);
        movieDetail.setVod_pic(item.get("pic").get("normal").asText());
        if (score > 0) {
            movieDetail.setVod_remarks(String.valueOf(score));
        }
        if (year != null) {
            movieDetail.setVod_year(String.valueOf(year));
        }
        movieDetail.setVod_tag(FOLDER);
        movieDetail.setCate(new CategoryList());
        return movieDetail;
    }

    /** 年份字段可能是纯 "2023" 或 card_subtitle 形如 "2023 · 中国大陆 · ..." 的前缀,取不到返 null。 */
    private static Integer parseYear(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        Matcher matcher = java.util.regex.Pattern.compile("(\\d{4})").matcher(text);        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    public MovieList searchDouban(String keyword, int size) {
        MovieList result = new MovieList();
        return result;
    }

    private String getTypeName(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "0" -> "阿里";
            case "1" -> "PikPak";
            case "2" -> "迅雷";
            case "3" -> "123";
            case "5" -> "夸克";
            case "6" -> "移动";
            case "7" -> "UC";
            case "8" -> "115";
            case "9" -> "天翼";
            case "10" -> "百度";
            case "12" -> "光鸭";
            case "magnet" -> "磁力";
            case "ed2k" -> "ED2K";
            case "video" -> "视频";
            default -> null;
        };
    }

    private String getPic(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "0" -> getUrl("/ali.jpg");
            case "1" -> getUrl("/pikpak.jpg");
            case "2" -> getUrl("/thunder.png");
            case "3" -> getUrl("/123.png");
            case "5" -> getUrl("/quark.png");
            case "7" -> getUrl("/uc.png");
            case "8" -> getUrl("/115.jpg");
            case "9" -> getUrl("/189.png");
            case "6" -> getUrl("/139.jpg");
            case "10" -> getUrl("/baidu.jpg");
            case "12" -> getUrl("/guangya.webp");
            case "magnet" -> getUrl("/magnet.png");
            case "ed2k" -> getUrl("/ed2k.jpg");
            default -> null;
        };
    }

    private String getUrl(String path) {
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .scheme(appProperties.isEnableHttps() && !Utils.isLocalAddress() ? "https" : "http") // nginx https
                .replacePath(path)
                .replaceQuery(null)
                .build()
                .toUriString();
    }

    private MovieDetail toMovieDetail(Message message) {
        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(encodeUrl(message.getLink()));
        movieDetail.setVod_name(StringUtils.defaultIfBlank(message.getName(), message.getContent()));
        if (StringUtils.isBlank(message.getCover())) {
            movieDetail.setVod_pic(getPic(message.getType()));
        } else {
            movieDetail.setVod_pic(resolveTgSearchMediaUrl(message.getCover()));
        }
        movieDetail.setVod_remarks(getTypeName(message.getType()));
        movieDetail.setVod_play_from(message.getChannel());
        if (message.getTime() != null) {
            movieDetail.setVod_time(message.getTime().toString());
        }
        applyMedia(message, movieDetail);
        if ("video".equals(message.getType())) {
            videoName.put(getVid(message.getLink()), movieDetail.getVod_name());
            if (message.getSize() != null) {
                movieDetail.setVod_remarks(Utils.byte2size(message.getSize()));
            }
        }
        // cache every search result (not only media-bearing ones, which applyMedia
        // handles) so detail() can backfill vod_name when the resolved storage
        // folder name is an obfuscated share token instead of the real title.
        if (StringUtils.isNotBlank(message.getLink())) {
            movies.put(message.getLink(), movieDetail);
        }
        return movieDetail;
    }

    private void applyMedia(Message message, MovieDetail movieDetail) {
        Map<String, Object> media = message.getMedia();
        if (media == null || media.isEmpty()) {
            return;
        }
        Object title = media.get("title");
        if (title != null && StringUtils.isNotBlank(String.valueOf(title))) {
            movieDetail.setVod_name(TextUtils.fixName(TextUtils.stripLeadingNoise(String.valueOf(title))));
        }
        Object year = media.get("year");
        if (year != null && StringUtils.isNotBlank(String.valueOf(year))) {
            movieDetail.setVod_year(String.valueOf(year));
        }
        List<String> remarks = Stream.of(movieDetail.getVod_remarks(), media.get("episode"), media.get("quality"), media.get("size"))
                .filter(e -> e != null && StringUtils.isNotBlank(String.valueOf(e)))
                .map(String::valueOf)
                .toList();
        if (!remarks.isEmpty()) {
            movieDetail.setVod_remarks(String.join(" ", remarks));
        }
        List<String> content = Stream.of(message.getContent(), media.get("tags"))
                .filter(e -> e != null && StringUtils.isNotBlank(String.valueOf(e)))
                .map(String::valueOf)
                .toList();
        if (!content.isEmpty()) {
            movieDetail.setVod_content(String.join("\n", content));
        }
//        movieDetail.setExt(media);
        log.debug("cache put: {} {}", message.getLink(), media);
        movies.put(message.getLink(), movieDetail);
    }

    private String resolveTgSearchMediaUrl(String url) {
        if (StringUtils.isBlank(url) || url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        if (url.startsWith("/") && StringUtils.isNotBlank(appProperties.getTgSearch())) {
            return appProperties.getTgSearch() + url;
        }
        return url;
    }

    public List<Message> search(String keyword, int size, boolean web, boolean cached) {
        return doSearch(keyword, size, web, cached, null);
    }

    /** 追剧搜索入口:盘搜/TG-Search 按订阅定向集({@link SearchTargets})定向,离线类型按磁力兜底开关;null = 观影全局口径。 */
    public List<Message> search(String keyword, int size, boolean web, boolean cached, SearchTargets targets) {
        return doSearch(keyword, size, web, cached, targets);
    }

    private List<Message> doSearch(String keyword, int size, boolean web, boolean cached, SearchTargets targets) {
        List<Message> results = List.of();
        List<TelegramChannel> channels = list().stream().filter(TelegramChannel::isValid).filter(TelegramChannel::isEnabled).toList();
        int searchedChannelCount = channels.size();
        if (web) {
            channels = channels.stream().filter(TelegramChannel::isWebAccess).toList();
            searchedChannelCount = channels.size();
        } else {
            if (StringUtils.isNotBlank(appProperties.getPanSouUrl())) {
                List<String> ids = channels.stream()
                        .map(TelegramChannel::getUsername)
                        .toList();
                searchedChannelCount = remoteSearchService.getSearchChannels(ids).size();
                results = remoteSearchService.search(keyword, ids, targets);
            }
            if (results.isEmpty() && StringUtils.isNotBlank(appProperties.getTgSearch())) {
                results = searchTgSearchApi(keyword, null, 1, size, tgSearchCloudTypes(targets)).messages();
            }
        }

        if (results.isEmpty()) {
            channels = channels.stream().filter(TelegramChannel::isWebAccess).toList();
            searchedChannelCount = channels.size();

            List<Future<List<Message>>> futures = new ArrayList<>();
            for (var channel : channels) {
                String name = channel.getUsername();
                Future<List<Message>> future = executorService.submit(() -> cached ? searchCache.get(name + "-" + web) : searchFromChannel(name, keyword, web, size));
                futures.add(future);
            }

            results = getResult(futures);
        }

        List<Message> list = filterAndSort(results, targets);
        log.info("Search {} get {} results from {} channels.", keyword, list.size(), searchedChannelCount);
        return list;
    }

    /**
     * 聚合搜索:盘搜 / TG-Search / 电报网页**同时**跑,按 link 去重合并。
     * <p>
     * 与 {@link #search(String, int, boolean, boolean)} 的回退链相反 —— 那条链"任一来源结果够用即停",
     * 于是配了盘搜的部署里 TG-Search 与电报网页永远不会被调用,而电报网页恰恰是唯一不依赖外部实例的
     * 内置来源。追更场景需要的是**最大召回**(资源不够时重复搜同一个源没有意义,结果不会变),
     * 所以三路全开。任一路失败只记日志,不影响其它路。
     */
    /**
     * 磁力专项搜索(追剧磁力兜底用):tg-search API 只请求 magnet/ed2k 离线类型
     * (离线消费两种链接同权),不过 filterAndSort(其 tgDrivers 过滤会按用户配置剔除磁力)。
     */
    public List<Message> searchMagnets(String keyword, int size) {
        if (StringUtils.isBlank(appProperties.getTgSearch())) {
            return List.of();
        }
        List<Message> messages = searchTgSearchApi(keyword, null, 1, size, List.of("magnet", "ed2k")).messages();
        log.info("magnet search {} get {} results", keyword, messages.size());
        return messages;
    }

    /**
     * 追剧聚合搜索:盘搜 / TG-Search / 电报网页**同时**跑,按 link 去重合并。
     * <p>
     * 与 {@link #search(String, int, boolean, boolean)} 的回退链相反 —— 那条链"任一来源结果够用即停",
     * 于是配了盘搜的部署里 TG-Search 与电报网页永远不会被调用,而电报网页恰恰是唯一不依赖外部实例的
     * 内置来源。追更场景需要的是**最大召回**(资源不够时重复搜同一个源没有意义,结果不会变),
     * 所以三路全开。任一路失败只记日志,不影响其它路。
     * <p>
     * 定向口径({@link SearchTargets}):盘搜与 TG-Search 服务端 cloud_types 只请求生效盘
     * (白名单空 = 全局口径不限);聚合出口的盘门禁在白名单非空时替换全局 tg.drivers,
     * magnet/ed2k 按磁力兜底开关放行。
     */
    public List<Message> searchAggregated(String keyword, int size, boolean cached, SearchTargets targets) {
        List<TelegramChannel> channels = list().stream()
                .filter(TelegramChannel::isValid).filter(TelegramChannel::isEnabled).toList();
        List<Future<List<Message>>> futures = new ArrayList<>();

        if (StringUtils.isNotBlank(appProperties.getPanSouUrl())) {
            List<String> ids = channels.stream().map(TelegramChannel::getUsername).toList();
            futures.add(executorService.submit(() -> remoteSearchService.search(keyword, ids, targets)));
        }
        if (StringUtils.isNotBlank(appProperties.getTgSearch())) {
            List<String> cloudTypes = tgSearchCloudTypes(targets);
            futures.add(executorService.submit(() -> searchTgSearchApi(keyword, null, 1, size, cloudTypes).messages()));
        }
        for (var channel : channels.stream().filter(TelegramChannel::isWebAccess).toList()) {
            String name = channel.getUsername();
            futures.add(executorService.submit(
                    () -> cached ? searchCache.get(name + "-false") : searchFromChannel(name, keyword, false, size)));
        }

        Map<String, Message> merged = new LinkedHashMap<>();
        for (Message message : getResult(futures)) {
            if (StringUtils.isNotBlank(message.getLink())) {
                merged.putIfAbsent(message.getLink(), message);
            }
        }
        List<Message> list = filterAndSort(merged.values().stream().toList(), targets);
        log.info("Aggregated search {} get {} results ({} sources).", keyword, list.size(), futures.size());
        return list;
    }

    /** 内容过滤(电子书/软件等非影视)与排序,回退链与聚合模式共用。 */
    private List<Message> filterAndSort(List<Message> results) {
        return filterAndSort(results, null);
    }

    /**
     * 定向版盘门禁:追剧搜索传入 {@link SearchTargets} 时,盘白名单非空以白名单替换全局
     * tg.drivers 门禁(订阅生效盘优先,防全局配置误杀扩展盘);白名单空时网盘维持全局口径,
     * magnet/ed2k 按磁力兜底开关放行(未并入时保留全局口径既有放行,不收窄现状)。
     */
    private List<Message> filterAndSort(List<Message> results, SearchTargets targets) {
        List<String> tgDrivers = appProperties.getTgDrivers();
        return results.stream()
                .filter(e -> typeAllowedBySearch(e.getType(), tgDrivers, targets))
                .filter(e -> !e.getContent().toLowerCase().contains("pdf"))
                .filter(e -> !e.getContent().toLowerCase().contains("epub"))
                .filter(e -> !e.getContent().toLowerCase().contains("azw3"))
                .filter(e -> !e.getContent().toLowerCase().contains("mobi"))
                .filter(e -> !e.getContent().toLowerCase().contains("ppt"))
                .filter(e -> !e.getContent().contains("软件"))
                .filter(e -> !e.getContent().contains("图书"))
                .filter(e -> !e.getContent().contains("电子书"))
                .filter(e -> !e.getContent().contains("分享文件："))
                .sorted(comparator())
                .distinct()
                .toList();
    }

    private boolean typeAllowedBySearch(String type, List<String> tgDrivers, SearchTargets targets) {
        boolean globalAllowed = tgDrivers.isEmpty() || tgDrivers.contains(type);
        return targets == null ? globalAllowed : targets.allowsType(type, globalAllowed);
    }

    /**
     * tg-search cloud_types 定向覆盖:盘白名单非空按白名单映射,否则全局 tg.drivers(现状);
     * 磁力兜底生效追加 magnet/ed2k。pan 部分为空返回 null(不覆盖 —— 不限模式服务端本就
     * 返回离线类型,单发离线列表会把网盘结果裁光)。
     */
    private List<String> tgSearchCloudTypes(SearchTargets targets) {
        if (targets == null) {
            return null;
        }
        List<String> base;
        if (targets.drives().isEmpty()) {
            base = getTgSearchCloudTypes(null);
        } else {
            base = targets.drives().stream()
                    .map(DriveId::toTypeLeniently)
                    .filter(Objects::nonNull)
                    .map(type -> getCloudType(String.valueOf(type)))
                    .filter(StringUtils::isNotBlank)
                    .distinct()
                    .toList();
        }
        if (base.isEmpty()) {
            return null;
        }
        if (!targets.offlineIncluded()) {
            return base;
        }
        List<String> withOffline = new ArrayList<>(base);
        if (!withOffline.contains("magnet")) {
            withOffline.add("magnet");
        }
        if (!withOffline.contains("ed2k")) {
            withOffline.add("ed2k");
        }
        return withOffline;
    }

    private Comparator<Message> comparator() {        Comparator<Message> type = Comparator.comparing(a -> appProperties.getTgDriverOrder().indexOf(a.getType()));
        return switch (appProperties.getTgSortField()) {
            case "type" -> type.thenComparing(Comparator.comparing(Message::getTime).reversed());
            case "name" -> Comparator.comparing(Message::getName);
            case "channel" ->
                    Comparator.comparing(Message::getChannel).thenComparing(Comparator.comparing(Message::getTime).reversed());
            default -> Comparator.comparing(Message::getTime).reversed();
        };
    }

    private List<Message> searchRemote(String channels, String keyword, int size) {
        String api = appProperties.getTgSearch();
        if (!api.endsWith("/search")) {
            api = api + "/search";
        }
        String url = api + "?channels=" + channels + "&query=" + keyword + "&size=" + size + "&timeout=" + appProperties.getTgTimeout();
        try {
            var response = restTemplate.getForObject(url, SearchResponse.class);
            return response.getMessages().stream().flatMap(this::parseMessage).toList();
        } catch (Exception e) {
            log.warn("", e);
        }
        return List.of();
    }

    private TgSearchResult searchTgSearchApi(String keyword, String cloudType, int page, int size) {
        return searchTgSearchApi(keyword, cloudType, page, size, null);
    }

    /** cloudTypesOverride 非空(含空表)时替换默认 cloud_types 推导(null = 按 cloudType/全局 tg.drivers)。 */
    private TgSearchResult searchTgSearchApi(String keyword, String cloudType, int page, int size,
                                             List<String> cloudTypesOverride) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, size);
        if (StringUtils.isBlank(appProperties.getTgSearch())) {
            return new TgSearchResult(List.of(), 0);
        }
        int offset = (safePage - 1) * safeSize;
        ObjectNode body = objectMapper.createObjectNode()
                .put("kw", StringUtils.defaultString(keyword))
                .put("res", "merge")
                .put("include_image", true)
                .put("include_media_metadata", true)
                .put("limit", safeSize)
                .put("offset", offset);
        List<String> cloudTypes = cloudTypesOverride != null ? cloudTypesOverride : getTgSearchCloudTypes(cloudType);
        if (!cloudTypes.isEmpty()) {
            ArrayNode cloudTypesNode = objectMapper.createArrayNode();
            cloudTypes.forEach(cloudTypesNode::add);
            body.set("cloud_types", cloudTypesNode);
        }
        String url = normalizeTgSearchUrl("/api/search");
        try {
            log.debug("search TG-Search url: {}", url);
            HttpHeaders headers = buildTgSearchHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<ObjectNode> entity = new HttpEntity<>(body, headers);
            var response = restTemplate.exchange(url, HttpMethod.POST, entity, ObjectNode.class).getBody();
            return parseTgSearchResponse(response);
        } catch (Exception e) {
            log.warn("", e);
        }
        return new TgSearchResult(List.of(), 0);
    }

    private List<String> getTgSearchCloudTypes(String cloudType) {
        if (StringUtils.isNotBlank(cloudType)) {
            return List.of(cloudType);
        }
        List<String> tgDrivers = appProperties.getTgDrivers();
        if (tgDrivers == null || tgDrivers.isEmpty()) {
            return List.of();
        }
        return tgDrivers.stream()
                .map(this::getCloudType)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
    }

    private HttpHeaders buildTgSearchHeaders() {
        HttpHeaders headers = new HttpHeaders();
        if (StringUtils.isNotBlank(appProperties.getTgSearchApiKey())) {
            headers.set(HttpHeaders.AUTHORIZATION, appProperties.getTgSearchApiKey());
        }
        return headers;
    }

    private String normalizeTgSearchUrl(String path) {
        String api = StringUtils.defaultString(appProperties.getTgSearch());
        while (api.endsWith("/")) {
            api = api.substring(0, api.length() - 1);
        }
        return api + path;
    }

    private TgSearchResult parseTgSearchResponse(ObjectNode response) {
        if (response == null) {
            return new TgSearchResult(List.of(), 0);
        }
        int total = response.path("data").path("total").asInt(0);
        JsonNode mergedByType = response.path("data").path("merged_by_type");
        if (!mergedByType.isObject()) {
            return new TgSearchResult(List.of(), total);
        }
        List<Message> messages = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = mergedByType.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String type = getMessageType(entry.getKey());
            if (type == null || !entry.getValue().isArray()) {
                continue;
            }
            for (JsonNode link : entry.getValue()) {
                String url = appendTgSearchPassword(link.path("url").asText(""), link.path("password").asText(""));
                if (StringUtils.isBlank(url)) {
                    continue;
                }
                List<String> images = new ArrayList<>();
                link.path("images").forEach(image -> {
                    if (image.isTextual()) {
                        images.add(image.asText());
                    }
                });
                Map<String, Object> media = objectMapper.convertValue(link.path("media"), new TypeReference<>() {
                });
                Long size = link.path("size").asLong();
                messages.add(new Message(
                        type,
                        url,
                        size,
                        link.path("note").asText(""),
                        parseInstant(link.path("datetime").asText(null)),
                        images,
                        media
                ));
            }
        }
        return new TgSearchResult(messages, total);
    }

    private String appendTgSearchPassword(String url, String password) {
        if (StringUtils.isBlank(url) || StringUtils.isBlank(password) || ShareService.PASSWORD.matcher(url).find()) {
            return url;
        }
        String encodedPassword = URLEncoder.encode(password.trim(), StandardCharsets.UTF_8);
        int fragmentIndex = url.indexOf('#');
        String base = fragmentIndex >= 0 ? url.substring(0, fragmentIndex) : url;
        String fragment = fragmentIndex >= 0 ? url.substring(fragmentIndex) : "";
        String separator = base.contains("?") ? "&" : "?";
        return base + separator + "password=" + encodedPassword + fragment;
    }

    private Instant parseInstant(String value) {
        if (StringUtils.isBlank(value)) {
            return Instant.now();
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return Instant.now();
        }
    }

    private String getMessageType(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "aliyun", "ali" -> "0";
            case "pikpak" -> "1";
            case "xunlei" -> "2";
            case "123" -> "3";
            case "quark" -> "5";
            case "mobile" -> "6";
            case "uc" -> "7";
            case "115" -> "8";
            case "tianyi" -> "9";
            case "baidu" -> "10";
            case "guangya" -> "12";
            case "magnet" -> "magnet";
            case "ed2k" -> "ed2k";
            case "video" -> "video";
            default -> null;
        };
    }

    private String getCloudType(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "0" -> "aliyun";
            case "1" -> "pikpak";
            case "2" -> "xunlei";
            case "3" -> "123";
            case "5" -> "quark";
            case "6" -> "mobile";
            case "7" -> "uc";
            case "8" -> "115";
            case "9" -> "tianyi";
            case "10" -> "baidu";
            case "12" -> "guangya";
            case "magnet" -> "magnet";
            case "ed2k" -> "ed2k";
            case "video" -> "video";
            default -> null;
        };
    }

    private record TgSearchResult(List<Message> messages, int total) {
    }

    private List<Message> getResult(List<Future<List<Message>>> futures) {
        long startTime = System.currentTimeMillis();
        List<Message> results = new ArrayList<>();
        List<Future<List<Message>>> incompleteFutures = new ArrayList<>();

        for (Future<List<Message>> future : futures) {
            long remaining = Math.max(1, appProperties.getTgTimeout() - (System.currentTimeMillis() - startTime));

            try {
                List<Message> result = future.get(remaining, TimeUnit.MILLISECONDS);
                results.addAll(result);
            } catch (TimeoutException e) {
                incompleteFutures.add(future);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for search results", e);
                incompleteFutures.add(future);
            } catch (ExecutionException e) {
                log.warn("", e);
            }
        }

        Iterator<Future<List<Message>>> iterator = incompleteFutures.iterator();
        while (iterator.hasNext()) {
            Future<List<Message>> future = iterator.next();
            if (future.isDone()) {
                try {
                    results.addAll(future.get());
                    iterator.remove();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted while retrieving completed future", e);
                } catch (ExecutionException e) {
                    log.warn("", e);
                }
            }
        }

        incompleteFutures.forEach(f -> f.cancel(true));

        return results;
    }

    private List<Message> getFromChannel(String key) throws IOException {
        String[] parts = key.split("-");
        String username = parts[0];
        return searchFromChannel(username, "", "true".equals(parts[1]), 100);
    }

    public List<Message> loadFromWeb(String username, int page) throws IOException {
        String before = "";
        if (page > 1) {
            before = lastId.get(username + "-" + (page - 1), key -> "");
        }
        List<Message> list = searchFromWeb(username, "", before);
        if (!list.isEmpty()) {
            int id = list.get(0).getId();
            lastId.put(username + "-" + page, String.valueOf(id));
        }
        List<Message> result = list.stream().filter(e -> e.getType() != null).sorted(Comparator.comparingInt(Message::getId).reversed()).toList();
        log.info("Load from web {} get {} results.", username, result.size());
        return result;
    }

    public List<Message> searchFromChannel(String username, String keyword, boolean web, int size) throws IOException {
        List<Message> list = searchFromWeb(username, keyword, "");
        List<Message> result = list.stream().filter(e -> e.getType() != null).toList();
        log.info("Search {} from web {} get {} results.", keyword, username, result.size());
        return result;
    }

    public TelegramChannel getChannelByName(String username) {
        TelegramChannel channel = new TelegramChannel();
        channel.setId(telegramChannelRepository.count());
        channel.setUsername(username);
        channel.setTitle(username);
        return channel;
    }

    private Stream<Message> parseMessage(SearchResult result) {
        List<Message> list = new ArrayList<>();
        for (String link : Message.parseLinks(result.getContent())) {
            list.add(new Message(result, link));
        }
        return list.stream();
    }

    public List<Message> searchFromWeb(String username, String keyword, String before) throws IOException {
        String url = "https://t.me/s/" + username + "?q=" + keyword + "&before=" + before;

        String html = getHtml(url);

        return parseWebMessages(Jsoup.parse(html), username);
    }

    List<Message> parseWebMessages(Document doc, String username) {
        List<Message> list = new ArrayList<>();
        Elements elements = doc.select("div.tgme_container div.tgme_widget_message_wrap");
        for (Element element : elements) {
            Element photo = element.selectFirst("a.tgme_widget_message_photo_wrap");
            String cover = "";
            if (photo != null) {
                String style = photo.attr("style");
                cover = style.replaceAll(".*background-image:url\\('(.*?)'\\).*", "$1");
            }
            Element message = element.selectFirst(".tgme_widget_message");
            String post = message != null ? message.attr("data-post") : "";
            String[] parts = post.split("/");
            if (parts.length < 2 || !parts[1].matches("\\d+")) {
                log.debug("Skip message with invalid data-post '{}'", post);
                continue;
            }
            String id = parts[1];
            Element elTime = element.selectFirst("time");
            String time = elTime != null ? elTime.attr("datetime") : null;
            list.add(new Message(Integer.parseInt(id), username, getTextWithNewlines(element.select(".tgme_widget_message_text").first()), time, cover));
        }
        return list;
    }

    public static String getTextWithNewlines(Element element) {
        if (element == null) {
            return "";
        }
        Element clone = element.clone();
        clone.select("br").before("\\n");
        clone.select("br").remove();
        clone.select("p, div, li").before("\\n");
        String text = clone.text().replace("\\n", "\n");
        return text.trim();
    }

    public String searchWeb(String keyword, String username, String encode) {
        log.info("search {} from web channels {}", keyword, username);
        String[] channels = username.split(",");
        List<Future<List<String>>> futures = new ArrayList<>();
        for (String channel : channels) {
            Future<List<String>> future = executorService.submit(() -> searchWeb(channel, keyword));
            futures.add(future);
        }

        int total = 0;
        List<String> result = new ArrayList<>();
        int index = 0;
        for (Future<List<String>> future : futures) {
            String currentChannel = channels[index++];
            try {
                List<String> list = future.get(appProperties.getTgTimeout(), TimeUnit.MILLISECONDS);
                total += list.size();
                for (String line : list) {
                    if ("1".equals(encode)) {
                        result.add(Base64.getEncoder().encodeToString(line.getBytes()));
                    } else {
                        result.add(line);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Search interrupted for channel: {}", currentChannel);
                break;
            } catch (ExecutionException | TimeoutException e) {
                log.warn("", e);
            }
        }

        log.info("Search TG web get {} results.", total);
        return String.join("\n", result);
    }

    public List<String> searchWeb(String username, String keyword) throws IOException {
        String url = "https://t.me/s/" + username + "?q=" + keyword;

        String html = getHtml(url);

        List<String> list = new ArrayList<>();
        Document doc = Jsoup.parse(html);
        Elements elements = doc.select("div.tgme_container div.tgme_widget_message_wrap");
        for (Element element : elements) {
            Element elTime = element.selectFirst("time");
            String time = elTime != null ? elTime.attr("datetime") : Instant.now().atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
            list.add(time + "\t" + username + "\t" + element.html().replace("\n", " ") + "\t");
        }
        Collections.reverse(list);
        log.info("Search TG web {} get {} results.", username, list.size());
        return list;
    }

    private String getHtml(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                .addHeader("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7,ja;q=0.6,zh-TW;q=0.5")
                .addHeader("User-Agent", appProperties.getUserAgent())
                .addHeader("Referer", "https://t.me/")
                .build();

        // Use try-with-resources to ensure response is always closed
        Call call = httpClient.newCall(request);
        try (Response response = call.execute()) {
            if (response.body() == null) {
                throw new IOException("Response body is null for URL: " + url);
            }
            return response.body().string();
        }
    }

    public List<TelegramChannel> updateAll(List<TelegramChannel> channels) {
        int order = 1;
        for (var channel : channels) {
            channel.setSortOrder(order++);
        }
        return telegramChannelRepository.saveAll(channels);
    }

    @PreDestroy
    public void cleanup() {
        log.info("Shutting down TelegramService executor service");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                log.warn("Executor service did not terminate in time, forcing shutdown");
                executorService.shutdownNow();
                if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                    log.error("Executor service did not terminate after forced shutdown");
                }
            }
        } catch (InterruptedException e) {
            log.warn("Interrupted while waiting for executor service to terminate", e);
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
