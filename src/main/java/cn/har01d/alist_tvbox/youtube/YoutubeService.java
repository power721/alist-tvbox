package cn.har01d.alist_tvbox.youtube;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.model.FileNameInfo;
import cn.har01d.alist_tvbox.model.Filter;
import cn.har01d.alist_tvbox.model.FilterValue;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.util.DashUtils;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * YouTube 站点服务:搜索/频道/播放列表/直播的 TVBox VOD API,数据源为 yt-dlp 子进程。
 *
 * <p>端点契约与 2025-06 移除前(#760)保持一致:browse/wd/t 三入口、channel@/playlist@ 前缀、
 * /watch 播放、/youtube-proxy 中继。播放策略变化:手搓 MPD(initRange 已不可得)改为代理
 * 官方 HLS master 清单(URL 重写),EXO 等播放器原生自适应;老播放器走 muxed itag18 兜底。</p>
 */
@Slf4j
@Service
public class YoutubeService {
    private static final Pattern CHANNEL_URL = Pattern.compile("https://www\\.youtube\\.com/(channel/(UC[0-9A-Za-z_-]{22})|(@[0-9A-Za-z._-]+))[/\\s\"'<>]");
    private static final Pattern PLAYLIST_URL = Pattern.compile("[?&]list=([0-9A-Za-z_-]+)");
    private static final Pattern PLAYLIST_LINE_URL = Pattern.compile("https?://[^\\s\"'<>]+");
    private static final int PAGE_SIZE = 20;
    private static final int BROWSE_LIMIT = 200;
    /** 直播栏目候选拉取量:live 特征位纯度低,须多拉再按 is_live 过滤凑页。 */
    private static final int LIVE_CANDIDATES = 120;

    private final List<FilterValue> sorts = Arrays.asList(
            new FilterValue("原始顺序", ""),
            new FilterValue("相关性", "RELEVANCE"),
            new FilterValue("评价", "RATING"),
            new FilterValue("时间", "UPLOAD_DATE"),
            new FilterValue("播放量", "VIEW_COUNT")
    );

    private final List<FilterValue> times = Arrays.asList(
            new FilterValue("全部", ""),
            new FilterValue("最近", "HOUR"),
            new FilterValue("本日", "DAY"),
            new FilterValue("本周", "WEEK"),
            new FilterValue("本月", "MONTH"),
            new FilterValue("本年", "YEAR")
    );

    private final List<FilterValue> types = Arrays.asList(
            new FilterValue("全部", ""),
            new FilterValue("视频", "VIDEO"),
            new FilterValue("频道", "CHANNEL"),
            new FilterValue("播放列表", "PLAYLIST"),
            new FilterValue("电影", "MOVIE")
    );

    private final List<FilterValue> formats = Arrays.asList(
            new FilterValue("全部", ""),
            new FilterValue("HD", "HD"),
            new FilterValue("4K", "_4K"),
            new FilterValue("HDR", "HDR")
    );

    private final YoutubeDlpRuntime runtime;
    private final AppProperties appProperties;
    private final ProxyHostAllowlist proxyHostAllowlist;

    /** 中继客户端:连接池复用 TLS(切轨道/连续分片),gzip 透明解压;proxy.txt 热更走 ProxySelector。 */
    private final okhttp3.OkHttpClient relayClient = new okhttp3.OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(60))
            .proxySelector(new java.net.ProxySelector() {
                @Override
                public List<java.net.Proxy> select(URI uri) {
                    String proxyUrl = proxyForUpstream();
                    if (proxyUrl == null) {
                        return List.of(java.net.Proxy.NO_PROXY);
                    }
                    URI parsed = URI.create(proxyUrl);
                    return List.of(new java.net.Proxy(java.net.Proxy.Type.HTTP,
                            new java.net.InetSocketAddress(parsed.getHost(), parsed.getPort())));
                }

                @Override
                public void connectFailed(URI uri, java.net.SocketAddress sa, java.io.IOException ioe) {
                }
            })
            .build();

    /** 清单中继短缓存(重写后字节,按 url+token):切轨道回跳/seek 免整份清单重复回源。 */
    private final com.github.benmanes.caffeine.cache.Cache<String, byte[]> manifestCache = Caffeine.newBuilder()
            .maximumWeight(32L * 1024 * 1024)
            .weigher((String key, byte[] value) -> value.length)
            .expireAfterWrite(Duration.ofSeconds(10))
            .build();

    /** 字幕清洗缓存(聚合+去重后的 vtt,按 url):字幕内容不变,10 分钟避免切轨重拉分段。 */
    private final com.github.benmanes.caffeine.cache.Cache<String, byte[]> subtitleCache = Caffeine.newBuilder()
            .maximumWeight(64L * 1024 * 1024)
            .weigher((String key, byte[] value) -> value.length)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();

    private final LoadingCache<String, JsonNode> videos = Caffeine.newBuilder()
            .maximumSize(50)
            .expireAfterAccess(Duration.ofSeconds(900))
            .build(this::getVideoInfo);

    private final LoadingCache<String, JsonNode> playlists = Caffeine.newBuilder()
            .maximumSize(20)
            .expireAfterWrite(Duration.ofSeconds(900))
            .build(this::getPlaylistInfo);

    private final LoadingCache<String, JsonNode> searches = Caffeine.newBuilder()
            .maximumSize(50)
            .expireAfterWrite(Duration.ofSeconds(900))
            .build(this::getSearchInfo);

    public YoutubeService(YoutubeDlpRuntime runtime, AppProperties appProperties, ProxyHostAllowlist proxyHostAllowlist) {
        this.runtime = runtime;
        this.appProperties = appProperties;
        this.proxyHostAllowlist = proxyHostAllowlist;
    }

    public MovieList home() {
        return list("电影", "", "", "", "", 1);
    }

    public CategoryList category() throws IOException {
        CategoryList result = new CategoryList();

        Path path = Utils.getDataPath("youtube.txt");
        if (Files.exists(path)) {
            for (String line : Files.readAllLines(path)) {
                if (StringUtils.isBlank(line)) {
                    continue;
                }
                String[] parts = line.split(":");
                String id = parts[0];
                String name = id;
                if (parts.length > 1) {
                    name = parts[1];
                }
                Category category = new Category();
                category.setType_id(id);
                category.setType_name(name);
                category.setType_flag(0);
                category.setLand(1);
                category.setRatio(1.33);
                result.getCategories().add(category);
                if (!id.contains("@")) {
                    result.getFilters().put(category.getType_id(), List.of(new Filter("sort", "排序", sorts), new Filter("time", "时间", times), new Filter("type", "类型", types), new Filter("format", "格式", formats)));
                }
            }
            // 自定义栏目不剥夺直播入口;已自定义 live@ 栏目则不重复追加
            if (result.getCategories().stream().noneMatch(c -> c.getType_id().startsWith("live@"))) {
                addLiveCategory(result);
            }
        } else {
            List<String> keywords = List.of("电影", "电视剧", "动漫", "综艺", "纪录片", "音乐", "英语", "科技", "新闻", "游戏", "风景", "旅游", "美食", "健身", "运动", "体育", "搞笑", "短剧");
            for (var name : keywords) {
                Category category = new Category();
                category.setType_id(name);
                category.setType_name(name);
                category.setType_flag(0);
                category.setLand(1);
                category.setRatio(1.33);
                result.getCategories().add(category);
                result.getFilters().put(category.getType_id(), List.of(new Filter("sort", "排序", sorts), new Filter("time", "时间", times), new Filter("type", "类型", types), new Filter("format", "格式", formats)));
            }
            addLiveCategory(result);
        }

        result.setTotal(result.getCategories().size());
        result.setLimit(result.getCategories().size());
        return result;
    }

    /** 直播分类:id 形如 live@关键词,浏览=按关键词搜直播中(live 特征位)。 */
    private void addLiveCategory(CategoryList result) {
        Category category = new Category();
        category.setType_id("live@直播");
        category.setType_name("直播");
        category.setType_flag(0);
        category.setLand(1);
        category.setRatio(1.33);
        result.getCategories().add(category);
    }

    public MovieList list(String text, String sort, String time, String type, String format, int page) {
        if (text.startsWith("channel@")) {
            return buildListResult(playlists.get(channelUrl(text.substring(8))), text);
        }
        if (text.startsWith("@")) {
            return getHandleVideo(text.substring(1));
        }
        if (text.startsWith("playlist@")) {
            return getPlaylistVideo(text.substring(9));
        }
        if (text.startsWith("live@")) {
            return liveList(text.substring(5), page);
        }
        return search(text, sort, time, type, format, page);
    }

    /**
     * 直播栏目:按关键词拉足候选后按 is_live 二次过滤。
     * YouTube 的 live 特征位(sp)实测纯度低——不过滤时 20 条里仅 1~6 条在播,大量重播混入。
     */
    private MovieList liveList(String keyword, int page) {
        String url = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                + "&sp=" + buildSp(null, null, "VIDEO", null, true);
        JsonNode info = searches.get(url + "@live");

        List<MovieDetail> lives = new ArrayList<>();
        for (var item : info.path("entries")) {
            if ("is_live".equals(item.path("live_status").asText()) && item.hasNonNull("id")) {
                lives.add(buildMovieDetail(item));
            }
        }
        int from = (page - 1) * PAGE_SIZE;
        int end = from + PAGE_SIZE;

        MovieList result = new MovieList();
        result.setList(lives.subList(Math.min(from, lives.size()), Math.min(end, lives.size())));
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        result.setPagecount(lives.size() > end ? page + 1 : page);
        log.debug("live result: {}/{} raw", result.getList().size(), info.path("entries").size());
        return result;
    }

    /** channel@ 后可能是 UC id 也可能是 @handle(搜索结果/自定义分类两种来源)。 */
    private static String channelUrl(String key) {
        if (key.startsWith("UC")) {
            return "https://www.youtube.com/channel/" + key + "/videos";
        }
        return "https://www.youtube.com/@" + key + "/videos";
    }

    private JsonNode getPlaylistInfo(String url) {
        return runtime.execJson(List.of("-J", "--flat-playlist", "--playlist-end", String.valueOf(BROWSE_LIMIT), url),
                Duration.ofSeconds(60));
    }

    /** key = url + "@@" + page(数字翻页)或 "@live"(直播栏目拉足候选供 is_live 过滤)。 */
    private JsonNode getSearchInfo(String key) {
        String suffix = key.substring(key.lastIndexOf('@') + 1);
        int end;
        if (suffix.chars().allMatch(Character::isDigit)) {
            end = Math.max(PAGE_SIZE * 2, Integer.parseInt(suffix) * PAGE_SIZE);
        } else {
            end = LIVE_CANDIDATES;
        }
        String url = key.substring(0, key.lastIndexOf('@'));
        return runtime.execJson(List.of("-J", "--flat-playlist", "--playlist-end", String.valueOf(end), url),
                Duration.ofSeconds(60));
    }

    private JsonNode getVideoInfo(String id) {
        return runtime.execJson(List.of("-J", "--no-playlist", "https://www.youtube.com/watch?v=" + id),
                Duration.ofSeconds(45));
    }

    public MovieList getChannelVideo(String id) {
        return buildListResult(playlists.get(channelUrl(id)), "channel@" + id);
    }

    public MovieList getHandleVideo(String handle) {
        return buildListResult(playlists.get("https://www.youtube.com/@" + handle + "/videos"), "channel@" + handle);
    }

    public MovieList getPlaylistVideo(String id) {
        return buildListResult(playlists.get("https://www.youtube.com/playlist?list=" + id), "playlist@" + id);
    }

    private MovieList buildListResult(JsonNode info, String fallbackId) {
        var entries = info.path("entries");
        List<MovieDetail> list = new ArrayList<>();
        MovieDetail video = new MovieDetail();
        video.setVod_id(fallbackId);
        video.setVod_name("合集");
        video.setVod_pic(getListPic());
        int size = info.path("playlist_count").asInt(entries.size());
        video.setVod_remarks(size + "个视频");
        list.add(video);
        for (var item : entries) {
            if (item.has("id")) {
                list.add(buildMovieDetail(item));
            }
        }

        MovieList result = new MovieList();
        result.setList(list);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        log.debug("channel result: {}", result);

        return result;
    }

    private MovieDetail buildMovieDetail(JsonNode item) {
        MovieDetail video = new MovieDetail();
        video.setVod_id(item.path("id").asText());
        video.setVod_name(item.path("title").asText());
        JsonNode thumbnails = item.path("thumbnails");
        if (thumbnails.isArray() && !thumbnails.isEmpty()) {
            video.setVod_pic(fixCover(thumbnails.get(0).path("url").asText()));
        }
        if ("is_live".equals(item.path("live_status").asText())) {
            video.setVod_remarks("直播");
        } else {
            video.setVod_remarks(Utils.secondsToDuration(item.path("duration").asLong()));
        }
        return video;
    }

    private String getListPic() {
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .scheme(appProperties.isEnableHttps() && !Utils.isLocalAddress() ? "https" : "http") // nginx https
                .replacePath("/list.png")
                .replaceQuery(null)
                .build()
                .toUriString();
    }

    public MovieList search(String text, String sort, String time, String type, String format, int page) {
        return search(text, sort, time, type, format, page, false);
    }

    MovieList search(String text, String sort, String time, String type, String format, int page, boolean live) {
        String sp = buildSp(sort, time, type, format, live);
        String url = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
        if (!sp.isEmpty()) {
            url += "&sp=" + sp;
        }
        // 缓存键含页码,同页命中 900s;跨页由 playlist-end 扩窗重拉
        JsonNode info = searches.get(url + "@@" + page);
        var entries = info.path("entries");
        int from = (page - 1) * PAGE_SIZE;
        int end = from + PAGE_SIZE;

        List<MovieDetail> list = new ArrayList<>();
        for (int i = from; i < end && i < entries.size(); i++) {
            MovieDetail movie = buildSearchEntry(entries.get(i));
            if (movie != null) {
                list.add(movie);
            }
        }

        MovieList result = new MovieList();
        result.setList(list);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        result.setPagecount(entries.size() >= end ? page + 1 : page);

        log.debug("search result: {}", result);
        return result;
    }

    private MovieDetail buildSearchEntry(JsonNode item) {
        String channel = channelOrHandleOf(item);
        if (channel != null) {
            MovieDetail movie = new MovieDetail();
            movie.setVod_id("channel@" + channel);
            movie.setVod_name(item.path("title").asText());
            JsonNode thumbnails = item.path("thumbnails");
            if (thumbnails.isArray() && !thumbnails.isEmpty()) {
                movie.setVod_pic(fixCover(thumbnails.get(0).path("url").asText()));
            }
            movie.setVod_remarks("频道");
            return movie;
        }
        String playlist = playlistIdOf(item);
        if (playlist != null) {
            MovieDetail movie = new MovieDetail();
            movie.setVod_id("playlist@" + playlist);
            movie.setVod_name(item.path("title").asText());
            JsonNode thumbnails = item.path("thumbnails");
            if (thumbnails.isArray() && !thumbnails.isEmpty()) {
                movie.setVod_pic(fixCover(thumbnails.get(0).path("url").asText()));
            }
            JsonNode count = item.path("video_count");
            movie.setVod_remarks(count.isNumber() ? count.asInt() + "个视频" : "播放列表");
            return movie;
        }
        String id = item.path("id").asText("");
        if (id.isEmpty() || !item.has("title")) {
            return null;
        }
        return buildMovieDetail(item);
    }

    private String fixCover(String url) {
        if (url.startsWith("//")) {
            url = "https:" + url;
        }
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .scheme(appProperties.isEnableHttps() && !Utils.isLocalAddress() ? "https" : "http") // nginx https
                .replacePath("/images")
                .replaceQuery("url=" + URLEncoder.encode(url, StandardCharsets.UTF_8))
                .build()
                .toUriString();
    }

    /**
     * 构造 YouTube 搜索 sp 参数(protobuf base64url)。
     *
     * <p>字段布局经真机实证+官方筛选面板抓取对齐:外层 field1=排序(rating=1/upload_date=2/view_count=3),
     * field2=Filters{1:上载日期 hour=1..year=5, 2:类型 video=1..movie=4,
     * 特征 HD=field4:1, 4K=field14:1, HDR=field25:1, 直播=field8:1}。网上流传的标签表时间位错位,勿参考。</p>
     */
    static String buildSp(String sort, String time, String type, String format) {
        return buildSp(sort, time, type, format, false);
    }

    static String buildSp(String sort, String time, String type, String format, boolean live) {
        ByteArrayOutputStreamHolder filters = new ByteArrayOutputStreamHolder();
        if (StringUtils.isNotBlank(time)) {
            filters.varint(1, switch (time) {
                case "HOUR" -> 1;
                case "DAY" -> 2;
                case "WEEK" -> 3;
                case "MONTH" -> 4;
                case "YEAR" -> 5;
                default -> 0;
            });
        }
        if (StringUtils.isNotBlank(type)) {
            filters.varint(2, switch (type) {
                case "VIDEO" -> 1;
                case "CHANNEL" -> 2;
                case "PLAYLIST" -> 3;
                case "MOVIE" -> 4;
                default -> 0;
            });
        }
        if (StringUtils.isNotBlank(format)) {
            switch (format) {
                case "HD" -> filters.bytes(0x20, 0x01);
                case "_4K" -> filters.bytes(0x70, 0x01);
                case "HDR" -> filters.bytes(0xC8, 0x01, 0x01);
                default -> {
                }
            }
        }
        if (live) {
            // 官方筛选面板「直播」= EgJAAQ==(Filters field8:1)
            filters.bytes(0x40, 0x01);
        }

        ByteArrayOutputStreamHolder out = new ByteArrayOutputStreamHolder();
        if (StringUtils.isNotBlank(sort)) {
            out.varint(1, switch (sort) {
                case "RATING" -> 1;
                case "UPLOAD_DATE" -> 2;
                case "VIEW_COUNT" -> 3;
                default -> 0;
            });
        }
        if (filters.length() > 0) {
            out.bytes(0x12, filters.length());
            out.append(filters);
        }
        if (out.length() == 0) {
            return "";
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
    }

    private static final class ByteArrayOutputStreamHolder {
        private final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();

        void varint(int field, int value) {
            if (value <= 0) {
                return;
            }
            bytes(field << 3, value);
        }

        void bytes(int... values) {
            for (int value : values) {
                buf.write(value);
            }
        }

        void append(ByteArrayOutputStreamHolder other) {
            try {
                other.buf.writeTo(buf);
            } catch (IOException ignored) {
            }
        }

        int length() {
            return buf.size();
        }

        byte[] toByteArray() {
            return buf.toByteArray();
        }
    }

    public MovieList detail(String id) {
        if (id.startsWith("channel@") || id.startsWith("playlist@") || id.startsWith("@")) {
            JsonNode info;
            String prefix = "";
            if (id.startsWith("channel@")) {
                info = playlists.get(channelUrl(id.substring(8)));
                prefix = "频道";
            } else if (id.startsWith("playlist@")) {
                info = playlists.get("https://www.youtube.com/playlist?list=" + id.substring(9));
                prefix = "播放列表";
            } else {
                info = playlists.get("https://www.youtube.com/" + id + "/videos");
                prefix = "频道";
            }
            MovieList result = new MovieList();
            MovieDetail movieDetail = new MovieDetail();
            movieDetail.setVod_id(id);
            movieDetail.setVod_name(info.path("title").asText(info.path("channel").asText("合集")));
            movieDetail.setVod_director(info.path("channel").asText(""));
            movieDetail.setVod_play_from(prefix);
            List<String> list = new ArrayList<>();
            for (var item : info.path("entries")) {
                if (item.has("id")) {
                    list.add(item.path("title").asText().replace("#", "").replace("$", "") + "$" + item.path("id").asText());
                }
            }
            String common = Utils.getCommonPrefix(list);
            if (common.length() > 1) {
                log.debug("Sort: {}", common);
                list.sort(Comparator.comparing(FileNameInfo::new));
            }
            movieDetail.setVod_play_url(String.join("#", list));
            result.getList().add(movieDetail);

            result.setTotal(result.getList().size());
            result.setLimit(result.getList().size());
            log.debug("detail: {}", result);
            return result;
        }

        JsonNode video = this.videos.get(id);

        MovieList result = new MovieList();
        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(id);
        movieDetail.setVod_name(video.path("title").asText());
        movieDetail.setVod_content(video.path("description").asText(""));
        movieDetail.setVod_director(video.path("channel").asText(""));
        JsonNode thumbnails = video.path("thumbnails");
        if (thumbnails.isArray() && !thumbnails.isEmpty()) {
            movieDetail.setVod_pic(fixCover(thumbnails.get(thumbnails.size() - 1).path("url").asText()));
        }
        boolean live = "is_live".equals(video.path("live_status").asText()) || video.path("is_live").asBoolean();
        movieDetail.setVod_remarks(live ? "" : Utils.secondsToDuration(video.path("duration").asLong()));
        movieDetail.setVod_play_from(live ? "直播" : "视频");
        movieDetail.setVod_play_url(id);
        result.getList().add(movieDetail);

        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        log.debug("detail: {}", result);
        return result;
    }

    public Object play(String token, String id, String client) {
        JsonNode info = videos.get(id);
        boolean live = "is_live".equals(info.path("live_status").asText()) || info.path("is_live").asBoolean();
        String masterManifest = findMasterManifest(info);
        List<JsonNode> muxed = muxedFormats(info);

        Map<String, Object> result = new HashMap<>();
        result.put("parse", "0");
        if (!live) {
            List<Map<String, Object>> subs = buildSubtitles(token, info);
            if (!subs.isEmpty()) {
                result.put("subs", subs);
            }
        }
        if (live) {
            result.put("url", buildProxyUrl(token, masterManifest));
        } else if ("node".equals(client)) {
            List<String> urls = new ArrayList<>();
            videoOnlyFormats(info, 1080).forEach(format -> {
                urls.add(format.path("format_id").asText() + " " + format.path("ext").asText());
                urls.add(buildProxyUrl(token, format.path("url").asText()));
            });
            result.put("url", urls);
            List<Map<String, Object>> list = new ArrayList<>();
            for (var audio : audioFormats(info)) {
                Map<String, Object> map = new HashMap<>();
                map.put("bit", audio.path("abr").asInt(audio.path("tbr").asInt()));
                map.put("title", (int) audio.path("abr").asInt(audio.path("tbr").asInt()) + "Kbps");
                map.put("url", buildProxyUrl(token, audio.path("url").asText()));
                list.add(map);
            }
            result.put("extra", Map.of("audio", list));
        } else if (DashUtils.isClientSupport(client) && masterManifest != null) {
            // HLS master 自带全清晰度+音轨自适应,EXO 原生播放,不再手搓 MPD
            result.put("url", buildProxyUrl(token, masterManifest));
        } else if (!muxed.isEmpty()) {
            result.put("url", buildProxyUrl(token, muxed.get(0).path("url").asText()));
        } else if (masterManifest != null) {
            result.put("url", buildProxyUrl(token, masterManifest));
        } else {
            throw new BadRequestException("无法获取播放地址: " + id);
        }

        log.debug("play result: {}", result);
        return result;
    }

    static String findMasterManifest(JsonNode info) {
        for (var format : info.path("formats")) {
            String manifest = format.path("manifest_url").asText("");
            if (!manifest.isEmpty()) {
                return manifest;
            }
        }
        return null;
    }

    /** 字幕语言优先级:人工中文简/繁 → 人工英文;无人工中文时补自动翻译简/繁(YouTube auto 翻译有上百语言组合,只挑中文)。 */
    private static final List<String> MANUAL_SUB_LANGS = List.of("zh-Hans", "zh-CN", "zh", "zh-Hant", "zh-TW", "en");
    private static final List<String> AUTO_SUB_LANGS = List.of("zh-CN", "zh-Hans", "zh", "zh-TW", "zh-Hant");
    private static final int MAX_SUBS = 8;

    /**
     * 字幕轨(契约同 B站 subs:{name,lang,format,url,flag},FongMi 对 format 直用)。
     * timedtext 直链在 www.youtube.com 域,经 /youtube-proxy 中继;vtt → text/vtt
     * (ExoPlayer WebVTT 解析器,口径同 TvBoxService 字幕 mime 分支)。
     * 自动翻译轨 flag=4(同 B站 AI 字幕「默认不选中」标记)。
     */
    List<Map<String, Object>> buildSubtitles(String token, JsonNode info) {
        List<Map<String, Object>> subs = new ArrayList<>();
        for (SubPick pick : pickSubtitlePicks(info)) {
            Map<String, Object> map = new HashMap<>();
            map.put("name", subtitleName(pick.lang()) + (pick.auto() ? "(自动翻译)" : ""));
            map.put("lang", pick.lang());
            map.put("format", "text/vtt");
            map.put("flag", pick.auto() ? 4 : 0);
            map.put("url", buildProxyUrl(token, pick.url()));
            subs.add(map);
        }
        return subs;
    }

    record SubPick(String lang, boolean auto, String url) {
    }

    /** 选轨纯逻辑:人工中文简/繁 → 人工英文;无人工中文补一条自动翻译简中(YouTube 自动翻译上百语言组合,只挑中文)。 */
    static List<SubPick> pickSubtitlePicks(JsonNode info) {
        List<SubPick> picks = new ArrayList<>();
        boolean manualChinese = false;
        for (String lang : MANUAL_SUB_LANGS) {
            String url = pickSubtitleUrl(info.path("subtitles"), lang);
            if (url == null) {
                continue;
            }
            if (lang.startsWith("zh")) {
                manualChinese = true;
            }
            picks.add(new SubPick(lang, false, url));
            if (picks.size() >= MAX_SUBS) {
                return picks;
            }
        }
        if (!manualChinese) {
            for (String lang : AUTO_SUB_LANGS) {
                String url = pickSubtitleUrl(info.path("automatic_captions"), lang);
                if (url != null) {
                    picks.add(new SubPick(lang, true, url));
                    break;
                }
            }
        }
        return picks;
    }

    static String pickSubtitleUrl(JsonNode pool, String lang) {
        for (var entry : pool.path(lang)) {
            if ("vtt".equals(entry.path("ext").asText()) && !entry.path("url").asText("").isEmpty()) {
                return entry.path("url").asText();
            }
        }
        return null;
    }

    static String subtitleName(String lang) {
        return switch (lang) {
            case "zh-Hans", "zh-CN" -> "简体中文";
            case "zh-Hant", "zh-TW" -> "繁体中文";
            case "zh" -> "中文";
            case "en" -> "English";
            default -> lang;
        };
    }

    static List<JsonNode> muxedFormats(JsonNode info) {
        List<JsonNode> list = new ArrayList<>();
        for (var format : info.path("formats")) {
            if (format.path("has_drm").asBoolean(false)) {
                continue;
            }
            if (!"https".equals(format.path("protocol").asText())) {
                continue;
            }
            String vcodec = format.path("vcodec").asText("none");
            String acodec = format.path("acodec").asText("none");
            if (!"none".equals(vcodec) && !"none".equals(acodec)) {
                list.add(format);
            }
        }
        list.sort(Comparator.comparingInt((JsonNode f) -> f.path("height").asInt(0)).reversed());
        return list;
    }

    static List<JsonNode> videoOnlyFormats(JsonNode info, int minHeight) {
        Map<Integer, JsonNode> best = new LinkedHashMap<>();
        for (var format : info.path("formats")) {
            if (format.path("has_drm").asBoolean(false)) {
                continue;
            }
            if (!"https".equals(format.path("protocol").asText())) {
                continue;
            }
            String vcodec = format.path("vcodec").asText("none");
            String acodec = format.path("acodec").asText("none");
            int height = format.path("height").asInt(0);
            if ("none".equals(acodec) && !"none".equals(vcodec) && height >= minHeight && height > 0) {
                best.putIfAbsent(height, format);
            }
        }
        return best.values().stream()
                .sorted(Comparator.comparingInt((JsonNode f) -> f.path("height").asInt(0)).reversed())
                .collect(Collectors.toList());
    }

    static List<JsonNode> audioFormats(JsonNode info) {
        Map<Integer, JsonNode> best = new LinkedHashMap<>();
        for (var format : info.path("formats")) {
            if (format.path("has_drm").asBoolean(false)) {
                continue;
            }
            if (!"https".equals(format.path("protocol").asText())) {
                continue;
            }
            String vcodec = format.path("vcodec").asText("none");
            String acodec = format.path("acodec").asText("none");
            int abr = (int) format.path("abr").asDouble(format.path("tbr").asDouble(0));
            if ("none".equals(vcodec) && !"none".equals(acodec) && abr > 0) {
                best.putIfAbsent(abr, format);
            }
        }
        return best.values().stream()
                .sorted(Comparator.comparingInt((JsonNode f) -> (int) f.path("abr").asDouble(f.path("tbr").asDouble(0))).reversed())
                .collect(Collectors.toList());
    }

    public void proxy(String token, String u, HttpServletRequest request, HttpServletResponse response) throws IOException {
        String url = decodeProxyUrl(u);
        if (!isAllowedUpstream(url)) {
            throw new BadRequestException("Invalid proxy URL");
        }
        // 字幕:timedtext 需聚合分段+去重(见 relaySubtitle),先行拦截
        if (url.contains("/api/timedtext")) {
            relaySubtitle(url, response);
            return;
        }
        // 分片 URL 路径中段也含 "/playlist/index.m3u8" 再接 /file/seg.ts,必须按后缀判定
        boolean manifest = url.endsWith(".m3u8") || url.contains("/api/manifest/");
        if (manifest) {
            relayManifest(token, url, request, response);
        } else {
            relayStream(url, request, response);
        }
    }

    private static final Pattern TIMEDTEXT_URL = Pattern.compile("https?://[^\\s\"']+youtube\\.com/api/timedtext[^\\s\"']*");

    /**
     * 字幕中继与清洗。YouTube timedtext 导出的两个坑:
     * 1. 每个 cue 原样出现两遍(网页播放器自带去重,通用 WebVTT 播放器逐条渲染 → 每句重复两行);
     * 2. 自动字幕(长视频)的 vtt URL 返回 HLS 分段清单(m3u8 of vtt 段),播放器无法直接消费。
     * 处理:若是分段清单则逐段拉取(段内时间戳为绝对时间,直接拼接),相邻去重后输出单份 WEBVTT。
     */
    private void relaySubtitle(String url, HttpServletResponse response) throws IOException {
        byte[] cached = subtitleCache.getIfPresent(url);
        if (cached == null) {
            List<String> cues = new ArrayList<>();
            int segments = 0;
            int failed = 0;
            String body;
            try (okhttp3.Response upstream = fetchUpstream(url, null)) {
                if (!upstream.isSuccessful()) {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND);
                    return;
                }
                body = upstream.body().string();
            }
            if (body.startsWith("#EXTM3U")) {
                Matcher segment = TIMEDTEXT_URL.matcher(body);
                while (segment.find()) {
                    segments++;
                    try (okhttp3.Response seg = fetchUpstream(segment.group(), null)) {
                        if (seg.isSuccessful()) {
                            cues.addAll(extractCues(seg.body().string()));
                        } else {
                            failed++;
                        }
                    }
                }
            } else {
                cues.addAll(extractCues(body));
            }
            StringBuilder out = new StringBuilder("WEBVTT\n\n");
            String previous = null;
            for (String cue : cues) {
                if (cue.equals(previous)) {
                    continue; // 相邻重复 cue 只留一份
                }
                out.append(cue).append("\n\n");
                previous = cue;
            }
            cached = out.toString().getBytes(StandardCharsets.UTF_8);
            if (cues.isEmpty()) {
                // 空结果不入缓存:分段未匹配/上游暂态失败会把空字幕钉死 10 分钟
                log.warn("timedtext relay empty cues, skip caching: url={}, segments={}, failed={}",
                        url, segments, failed);
            } else {
                subtitleCache.put(url, cached);
            }
        }
        response.setContentType("text/vtt");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(cached.length);
        response.getOutputStream().write(cached);
    }

    /** 按空行分块取出含时间轴的 cue 块(原样保留 <c> 内联标签与多行文本)。 */
    static List<String> extractCues(String vtt) {
        List<String> cues = new ArrayList<>();
        for (String block : vtt.split("\\n\\s*\\n")) {
            if (block.contains("-->")) {
                cues.add(block.strip());
            }
        }
        return cues;
    }

    private void relayManifest(String token, String url, HttpServletRequest request, HttpServletResponse response) throws IOException {
        // 短缓存(重写后字节):切轨道回跳/seek 重拉清单免回源;key 带 token——重写前缀含 token 不可跨 token 复用
        byte[] cached = manifestCache.getIfPresent(url + "@" + token);
        if (cached != null) {
            response.setContentType("application/vnd.apple.mpegurl");
            response.setContentLength(cached.length);
            response.getOutputStream().write(cached);
            return;
        }
        String body;
        try (okhttp3.Response upstream = fetchUpstream(url, null)) {
            if (!upstream.isSuccessful()) {
                response.sendError(upstream.code() == 403 || upstream.code() == 404
                        ? HttpServletResponse.SC_NOT_FOUND : HttpServletResponse.SC_BAD_GATEWAY);
                return;
            }
            body = upstream.body().string();
        }
        String prefix = ServletUriComponentsBuilder.fromCurrentRequest()
                .scheme(appProperties.isEnableHttps() && !Utils.isLocalAddress() ? "https" : "http")
                .replacePath("/youtube-proxy" + (StringUtils.isBlank(token) ? "" : "/" + token))
                .replaceQuery(null)
                .build()
                .toUriString();
        Matcher matcher = PLAYLIST_LINE_URL.matcher(body);
        StringBuilder out = new StringBuilder(body.length() * 2);
        int last = 0;
        while (matcher.find()) {
            String original = matcher.group();
            out.append(body, last, matcher.start());
            if (isAllowedUpstream(original)) {
                out.append(prefix).append("?u=").append(encodeProxyUrl(original));
            } else {
                out.append(original);
            }
            last = matcher.end();
        }
        out.append(body.substring(last));
        byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
        manifestCache.put(url + "@" + token, bytes);
        response.setContentType("application/vnd.apple.mpegurl");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }

    private void relayStream(String url, HttpServletRequest request, HttpServletResponse response) throws IOException {
        String range = request.getHeader(HttpHeaders.RANGE);
        try (okhttp3.Response upstream = fetchUpstream(url, range)) {
            response.setStatus(upstream.code());
            for (String header : List.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.CONTENT_LENGTH, HttpHeaders.CONTENT_RANGE, HttpHeaders.ACCEPT_RANGES)) {
                String value = upstream.header(header);
                if (value != null) {
                    response.setHeader(header, value);
                }
            }
            if (upstream.code() >= 400) {
                response.setHeader(HttpHeaders.CONTENT_LENGTH, "0");
                return;
            }
            try (InputStream in = upstream.body().byteStream(); OutputStream out = response.getOutputStream()) {
                in.transferTo(out);
            }
        }
    }

    /**
     * 上游抓取:okhttp 连接池复用到同 CDN host 的 TLS 连接——切换轨道/连续播分片
     * 不再每请求重握手(此前 HttpURLConnection 逐请求新建,切轨道串行握手放大延迟);
     * gzip 由 okhttp 透明解压;proxy.txt 热更经 ProxySelector 每连接生效;
     * 通用解析站点(YtdlpParseService 登记)的防盗链头(Referer/UA)按 host 自动补。
     */
    private okhttp3.Response fetchUpstream(String url, String range) throws IOException {
        okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(url);
        if (range != null) {
            builder.header(HttpHeaders.RANGE, range);
        }
        applySiteHeaders(builder, url);
        return relayClient.newCall(builder.build()).execute();
    }

    private void applySiteHeaders(okhttp3.Request.Builder builder, String url) {
        String headersJson = proxyHostAllowlist.headersFor(hostOf(url));
        if (headersJson == null) {
            return;
        }
        try {
            JsonNode headers = new com.fasterxml.jackson.databind.ObjectMapper().readTree(headersJson);
            headers.fields().forEachRemaining(e -> {
                String value = e.getValue().asText(null);
                if (value != null && !HttpHeaders.RANGE.equalsIgnoreCase(e.getKey())) {
                    builder.header(e.getKey(), value);
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private String proxyForUpstream() {
        try {
            Path path = Utils.getDataPath("proxy.txt");
            if (Files.exists(path)) {
                String line = Files.readString(path).trim();
                URI uri = URI.create(line);
                if (uri.getHost() != null && uri.getPort() > 0) {
                    return line;
                }
            }
        } catch (Exception e) {
            log.warn("read proxy.txt failed", e);
        }
        return null;
    }

    /** 代理上游判定:静态基础表(googlevideo/youtube)+ 动态白名单(yt-dlp 通用解析登记的站点 CDN 域)。 */
    private boolean isAllowedUpstream(String url) {
        if (!Utils.isSafeExternalUrl(url)) {
            return false;
        }
        String host = URI.create(url).getHost();
        if (host == null) {
            return false;
        }
        host = host.toLowerCase();
        if (host.equals("manifest.googlevideo.com") || host.endsWith(".googlevideo.com")
                || host.equals("www.youtube.com") || host.equals("youtube.com")) {
            return true;
        }
        return proxyHostAllowlist.allows(host);
    }

    static String encodeProxyUrl(String url) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(url.getBytes(StandardCharsets.UTF_8));
    }

    static String decodeProxyUrl(String u) {
        try {
            return new String(Base64.getUrlDecoder().decode(u), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid proxy URL");
        }
    }

    private String buildProxyUrl(String token, String upstream) {
        String path = "/youtube-proxy";
        if (StringUtils.isNotBlank(token)) {
            path = path + "/" + token;
        }
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .scheme(appProperties.isEnableHttps() && !Utils.isLocalAddress() ? "https" : "http") // nginx https
                .replacePath(path)
                .replaceQuery("u=" + encodeProxyUrl(upstream))
                .build()
                .toUriString();
    }

    static String channelOrHandleOf(JsonNode item) {
        String url = item.path("url").asText("");
        Matcher matcher = CHANNEL_URL.matcher(url + "/");
        if (matcher.find()) {
            return matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
        }
        return null;
    }

    static String playlistIdOf(JsonNode item) {
        Matcher matcher = PLAYLIST_URL.matcher(item.path("url").asText(""));
        return matcher.find() ? matcher.group(1) : null;
    }
}
