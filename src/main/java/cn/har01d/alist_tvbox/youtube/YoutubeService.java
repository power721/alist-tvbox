package cn.har01d.alist_tvbox.youtube;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.model.Filter;
import cn.har01d.alist_tvbox.model.FilterValue;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.util.Constants;
import cn.har01d.alist_tvbox.util.Utils;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.kiulian.downloader.Config;
import com.github.kiulian.downloader.YoutubeDownloader;
import com.github.kiulian.downloader.downloader.request.RequestChannelUploads;
import com.github.kiulian.downloader.downloader.request.RequestPlaylistInfo;
import com.github.kiulian.downloader.downloader.request.RequestSearchContinuation;
import com.github.kiulian.downloader.downloader.request.RequestSearchResult;
import com.github.kiulian.downloader.downloader.request.RequestVideoInfo;
import com.github.kiulian.downloader.downloader.request.RequestVideoStreamDownload;
import com.github.kiulian.downloader.downloader.response.Response;
import com.github.kiulian.downloader.model.Extension;
import com.github.kiulian.downloader.model.playlist.PlaylistInfo;
import com.github.kiulian.downloader.model.playlist.PlaylistVideoDetails;
import com.github.kiulian.downloader.model.search.SearchResult;
import com.github.kiulian.downloader.model.search.SearchResultItemType;
import com.github.kiulian.downloader.model.search.field.FormatField;
import com.github.kiulian.downloader.model.search.field.SortField;
import com.github.kiulian.downloader.model.search.field.TypeField;
import com.github.kiulian.downloader.model.search.field.UploadDateField;
import com.github.kiulian.downloader.model.videos.VideoInfo;
import com.github.kiulian.downloader.model.videos.formats.AudioFormat;
import com.github.kiulian.downloader.model.videos.formats.Format;
import com.github.kiulian.downloader.model.videos.formats.VideoFormat;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class YoutubeService {
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

    private final MyDownloader myDownloader;
    private final YoutubeDownloader downloader;
    private final LoadingCache<String, VideoInfo> cache = Caffeine.newBuilder()
            .maximumSize(10)
            .expireAfterWrite(Duration.ofSeconds(900))
            .build(this::getVideoInfo);

    private final AppProperties appProperties;

    public YoutubeService(AppProperties appProperties) {
        this.appProperties = appProperties;
        Config config = new Config.Builder().header("User-Agent", Constants.USER_AGENT).build();

        try {
            Path path = Path.of("/data/proxy.txt");
            if (Files.exists(path)) {
                String line = Files.readString(path).trim();
                URI uri = URI.create(line);
                log.debug("use http proxy: {} {}", uri.getHost(), uri.getPort());
                config.setProxy(uri.getHost(), uri.getPort());
            }
        } catch (Exception e) {
            log.warn("set http proxy failed", e);
        }

        myDownloader = new MyDownloader(config);
        downloader = new YoutubeDownloader(config, myDownloader);
    }

    public MovieList home() {
        return list("电影", "", "", 1);
    }

    public CategoryList category() throws IOException {
        CategoryList result = new CategoryList();

        Path path = Path.of("/data/youtube.txt");
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
                    result.getFilters().put(category.getType_id(), List.of(new Filter("sort", "排序", sorts), new Filter("time", "时间", times)));
                }
            }
        } else {
            List<String> keywords = List.of("电影", "电视剧", "动漫", "综艺", "纪录片", "音乐", "英语", "科技", "新闻", "游戏", "风景", "旅游", "美食", "健身", "运动", "体育");
            for (var name : keywords) {
                Category category = new Category();
                category.setType_id(name);
                category.setType_name(name);
                category.setType_flag(0);
                category.setLand(1);
                category.setRatio(1.33);
                result.getCategories().add(category);
                result.getFilters().put(category.getType_id(), List.of(new Filter("sort", "排序", sorts), new Filter("time", "时间", times)));
            }
        }

        result.setTotal(result.getCategories().size());
        result.setLimit(result.getCategories().size());
        return result;
    }

    public MovieList list(String text, String sort, String time, int page) {
        if (text.startsWith("channel@")) {
            return getChannelVideo(text.substring(8));
        }
        if (text.startsWith("playlist@")) {
            return getPlaylistVideo(text.substring(9));
        }
        return search(text, sort, time, page);
    }

    public MovieList getChannelVideo(String id) {
        var info = downloader.getChannelUploads(new RequestChannelUploads(id)).data();
        var videos = info.videos();
        List<MovieDetail> list = new ArrayList<>();
        MovieDetail video = new MovieDetail();
        video.setVod_id("channel@" + id);
        video.setVod_name("合集");
        video.setVod_pic(getListPic());
        video.setVod_remarks(videos.size() + "个视频");
        list.add(video);
        for (var item : videos) {
            list.add(buildMovieDetail(item));
        }

        MovieList result = new MovieList();
        result.setList(list);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        log.debug("channel result: {}", result);

        return result;
    }

    public MovieList getPlaylistVideo(String id) {
        var info = downloader.getPlaylistInfo(new RequestPlaylistInfo(id)).data();
        var videos = info.videos();
        List<MovieDetail> list = new ArrayList<>();
        MovieDetail video = new MovieDetail();
        video.setVod_id("playlist@" + id);
        video.setVod_name("合集");
        video.setVod_pic(getListPic());
        video.setVod_remarks(videos.size() + "个视频");
        list.add(video);
        for (var item : videos) {
            list.add(buildMovieDetail(item));
        }

        MovieList result = new MovieList();
        result.setList(list);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        log.debug("Playlist result: {}", result);

        return result;
    }

    private MovieDetail buildMovieDetail(PlaylistVideoDetails item) {
        MovieDetail video = new MovieDetail();
        video.setVod_id(item.videoId());
        video.setVod_name(item.title());
        if (item.thumbnails() != null && !item.thumbnails().isEmpty()) {
            video.setVod_pic(fixCover(item.thumbnails().get(0)));
        }
        video.setVod_remarks(Utils.secondsToDuration(item.lengthSeconds()));
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

    private final Map<String, RequestSearchContinuation> continuations = new HashMap<>();

    public MovieList search(String text, String sort, String time, int page) {
        SearchResult searchResult;
        String query = text + "@@@" + sort;
        if (page > 1 && continuations.containsKey(query)) {
            searchResult = downloader.searchContinuation(continuations.get(query)).data();
        } else {
            var request = new RequestSearchResult(text);
            request.filter(TypeField.VIDEO, FormatField.HD);
            if (sort != null && !sort.isEmpty()) {
                request.sortBy(SortField.valueOf(sort));
            }
            if (time != null && !time.isEmpty()) {
                request.filter(UploadDateField.valueOf(time));
            }
            searchResult = downloader.search(request).data();
        }

        List<MovieDetail> list = new ArrayList<>();
        for (var item : searchResult.items()) {
            MovieDetail movie = new MovieDetail();
            if (item.type() == SearchResultItemType.VIDEO) {
                var video = item.asVideo();
                movie.setVod_id(video.videoId());
                movie.setVod_name(item.title());
                if (video.richThumbnails() != null && !video.richThumbnails().isEmpty()) {
                    movie.setVod_pic(fixCover(video.richThumbnails().get(0)));
                }
                movie.setVod_remarks(Utils.secondsToDuration(video.lengthSeconds()));
                list.add(movie);
            } else if (item.type() == SearchResultItemType.CHANNEL) {
                var video = item.asChannel();
                movie.setVod_id("channel@" + video.channelId());
                movie.setVod_name(item.title());
                if (video.thumbnails() != null && !video.thumbnails().isEmpty()) {
                    movie.setVod_pic(fixCover(video.thumbnails().get(0)));
                }
                movie.setVod_remarks(Objects.toString(video.videoCountText(), "频道"));
                list.add(movie);
            } else if (item.type() == SearchResultItemType.PLAYLIST) {
                var video = item.asPlaylist();
                movie.setVod_id("playlist@" + video.playlistId());
                movie.setVod_name(item.title());
                if (video.thumbnails() != null && !video.thumbnails().isEmpty()) {
                    movie.setVod_pic(fixCover(video.thumbnails().get(0)));
                }
                movie.setVod_remarks(video.videoCount() + "个视频");
                list.add(movie);
            }
        }

        MovieList result = new MovieList();
        result.setList(list);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        result.setPagecount(page + 1);

        log.debug("search result: {}", result);

        if (searchResult.hasContinuation()) {
            continuations.put(query, new RequestSearchContinuation(searchResult));
        } else {
            continuations.remove(query);
        }

        return result;
    }

    private String fixCover(String url) {
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        return url;
    }

    private VideoInfo getVideoInfo(String id) {
        RequestVideoInfo request = new RequestVideoInfo(id);
        Response<VideoInfo> response = downloader.getVideoInfo(request);
        return response.data();
    }

    public MovieList detail(String id) {
        if (id.startsWith("channel@") || id.startsWith("playlist@")) {
            PlaylistInfo playlistInfo;
            if (id.startsWith("channel@")) {
                playlistInfo = downloader.getChannelUploads(new RequestChannelUploads(id.substring(8))).data();
            } else {
                playlistInfo = downloader.getPlaylistInfo(new RequestPlaylistInfo(id.substring(9))).data();
            }
            MovieList result = new MovieList();
            MovieDetail movieDetail = new MovieDetail();
            movieDetail.setVod_id(id);
            movieDetail.setVod_name(playlistInfo.details().title());
            movieDetail.setVod_director(playlistInfo.details().author());
            movieDetail.setVod_play_from(id.startsWith("channel@") ? "频道" : "播放列表");
            movieDetail.setVod_play_url(playlistInfo.videos().stream().map(e -> e.title().replace("#", "").replace("$", "") + "$" + e.videoId()).collect(Collectors.joining("#")));
            result.getList().add(movieDetail);

            result.setTotal(result.getList().size());
            result.setLimit(result.getList().size());
            log.debug("detail: {}", result);
            return result;
        }

        VideoInfo video = cache.get(id);

        MovieList result = new MovieList();
        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(id);
        movieDetail.setVod_name(video.details().title());
        movieDetail.setVod_content(video.details().description());
        movieDetail.setVod_director(video.details().author());
        if (video.details().thumbnails() != null && !video.details().thumbnails().isEmpty()) {
            movieDetail.setVod_pic(fixCover(video.details().thumbnails().get(0)));
        }
        movieDetail.setVod_remarks(Utils.secondsToDuration(video.details().lengthSeconds()));
        movieDetail.setVod_play_from("视频");
        movieDetail.setVod_play_url(id);
        result.getList().add(movieDetail);

        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        log.debug("detail: {}", result);
        return result;
    }

    public Object play(String id, String client) {
        VideoInfo info = cache.get(id);
        List<String> urls = new ArrayList<>();
        if ("node".equals(client)) {
            info.videoFormats()
                    .stream()
                    .filter(e -> e.videoQuality().ordinal() > 5)
                    .sorted(Comparator.comparing(VideoFormat::videoQuality).reversed())
                    .forEach(format -> {
                        urls.add(format.qualityLabel() + " " + format.extension().value());
                        urls.add(buildProxyUrl(id, format.itag().id()));
                    });
        } else {
            info.videoWithAudioFormats()
                    .stream()
                    .sorted(Comparator.comparing(VideoFormat::videoQuality).reversed())
                    .forEach(format -> {
                        urls.add(format.qualityLabel() + " " + format.extension().value());
                        urls.add(buildProxyUrl(id, format.itag().id()));
                    });
        }

        List<Format> audios = info.audioFormats()
                .stream()
                .filter(Format::isAdaptive)
                .filter(e -> e.extension() == Extension.M4A)
                .collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("parse", "0");
        if ("com.fongmi.android.tv".equals(client)) {
            List<Format> videos = new ArrayList<>();
            info.videoFormats()
                    .stream()
                    .filter(Format::isAdaptive)
                    .filter(e -> e.extension() == Extension.MPEG4)
                    .filter(e -> e.videoQuality().ordinal() > 5)
                    .sorted(Comparator.comparing(VideoFormat::videoQuality).reversed())
                    .forEach(videos::add);
            String mpd = getMpd(info, videos, audios);
            log.debug("{}", mpd);
            String encoded = Base64.getMimeEncoder().encodeToString(mpd.getBytes());
            String url = "data:application/dash+xml;base64," + encoded.replaceAll("\\r\\n", "\n") + "\n";
            urls.add("Dash");
            urls.add(url);
            result.put("url", urls);
        } else if ("node".equals(client)) {
            result.put("url", urls);
            List<Map<String, Object>> list = new ArrayList<>();
            for (var audio : audios) {
                Map<String, Object> map = new HashMap<>();
                map.put("bit", audio.bitrate());
                map.put("title", (audio.bitrate() / 1024) + "Kbps");
                map.put("url", buildProxyUrl(id, audio.itag().id()));
                list.add(map);
            }
            result.put("extra", Map.of("audio", list));
        } else {
            result.put("url", urls.get(1));
        }

        log.debug("play result: {}", result);
        return result;
    }

    public void proxy(String id, int tag, HttpServletRequest request, HttpServletResponse response) throws IOException {
        VideoInfo video = cache.get(id);
        Format format = video.findFormatByItag(tag);
        if (format == null) {
            format = video.bestVideoWithAudioFormat();
        }

        String range = request.getHeader("range");
        log.debug("format {} {} {}", format.itag().id(), format.extension().value(), range);
        var download = new RequestVideoStreamDownload(format, response.getOutputStream());
        if (range != null) {
            download.header("range", range);
        }

        myDownloader.setHttpServletResponse(response);
        downloader.downloadVideoStream(download);
    }

    private String buildProxyUrl(String id, int tag) {
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .replacePath("/youtube-proxy")
                .replaceQuery("id=" + id + "&q=" + tag)
                .build()
                .toUriString();
    }

    private String getMedia(String id, Format media) {
        if (media.itag().isVideo()) {
            VideoFormat video = (VideoFormat) media;
            return getAdaptationSet(id, media, String.format("height=\"%s\" width=\"%s\" frameRate=\"%d\" sar=\"1:1\"", video.height(), video.width(), video.fps()));
        } else if (media.itag().isAudio()) {
            AudioFormat audio = (AudioFormat) media;
            return getAdaptationSet(id, media, String.format("numChannels=\"2\" sampleRate=\"%s\"", audio.audioSampleRate()));
        } else {
            return "";
        }
    }

    private static final Pattern CODECS = Pattern.compile("codecs=\"(.+)\"");

    private String getAdaptationSet(String id, Format media, String params) {
        int tag = media.itag().id();
        String type = media.mimeType().split("/")[0];
        String mimeType = media.mimeType().split(";")[0];
        var m = CODECS.matcher(media.mimeType());
        String codecs = "";
        if (m.find()) {
            codecs = m.group(1);
        }
        String url = buildProxyUrl(id, tag).replace("&", "&amp;");
        return String.format(
                "<AdaptationSet>\n" +
                        "<ContentComponent contentType=\"%s\"/>\n" +
                        "<Representation id=\"%d\" bandwidth=\"%s\" codecs=\"%s\" mimeType=\"%s\" %s startWithSAP=\"%d\">\n" +
                        "<BaseURL>%s</BaseURL>\n" +
                        "</Representation>\n" +
                        "</AdaptationSet>\n",
                type,
                tag, media.bitrate(), codecs, mimeType, params, media.itag().isVideo() ? 1 : 0,
                url
        );
    }

    private String getMpd(VideoInfo info, List<Format> videos, List<Format> audios) {
        String id = info.details().videoId();
        return String.format(
                "<MPD xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns=\"urn:mpeg:dash:schema:mpd:2011\" xsi:schemaLocation=\"urn:mpeg:dash:schema:mpd:2011 DASH-MPD.xsd\" type=\"static\" mediaPresentationDuration=\"PT%sS\" minBufferTime=\"PT1.5S\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\">\n" +
                        "<Period duration=\"PT%sS\" start=\"PT0S\">\n" +
                        "%s\n" +
                        "%s\n" +
                        "</Period>\n" +
                        "</MPD>",
                info.details().lengthSeconds(),
                info.details().lengthSeconds(),
                videos.stream().map(e -> getMedia(id, e)).collect(Collectors.joining()),
                audios.stream().map(e -> getMedia(id, e)).collect(Collectors.joining()));
    }
}
