package cn.har01d.alist_tvbox.youtube;

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
import com.github.kiulian.downloader.downloader.request.RequestSearchContinuation;
import com.github.kiulian.downloader.downloader.request.RequestSearchResult;
import com.github.kiulian.downloader.downloader.request.RequestVideoInfo;
import com.github.kiulian.downloader.downloader.request.RequestVideoStreamDownload;
import com.github.kiulian.downloader.downloader.response.Response;
import com.github.kiulian.downloader.model.search.SearchResult;
import com.github.kiulian.downloader.model.search.SearchResultVideoDetails;
import com.github.kiulian.downloader.model.search.field.FormatField;
import com.github.kiulian.downloader.model.search.field.SortField;
import com.github.kiulian.downloader.model.search.field.TypeField;
import com.github.kiulian.downloader.model.videos.VideoInfo;
import com.github.kiulian.downloader.model.videos.formats.Format;
import com.github.kiulian.downloader.model.videos.formats.VideoWithAudioFormat;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private final Config config = new Config.Builder().header("User-Agent", Constants.USER_AGENT).build();
    private final MyDownloader myDownloader = new MyDownloader(config);
    private final YoutubeDownloader downloader = new YoutubeDownloader(config, myDownloader);
    private final LoadingCache<String, VideoInfo> cache = Caffeine.newBuilder()
            .maximumSize(10)
            .expireAfterWrite(Duration.ofSeconds(900))
            .build(this::getVideoInfo);

    public MovieList home() {
        return list("电影", "", 1);
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
                result.getFilters().put(category.getType_id(), List.of(new Filter("sort", "排序", sorts)));
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
                result.getFilters().put(category.getType_id(), List.of(new Filter("sort", "排序", sorts)));
            }
        }

        result.setTotal(result.getCategories().size());
        result.setLimit(result.getCategories().size());
        return result;
    }

    public MovieList list(String text, String sort, int page) {
        return search(text, sort, page);
    }

    private final Map<String, RequestSearchContinuation> continuations = new HashMap<>();

    public MovieList search(String text, String sort, int page) {
        SearchResult searchResult;
        String query = text + "@@@" + sort;
        if (page > 1 && continuations.containsKey(query)) {
            searchResult = downloader.searchContinuation(continuations.get(query)).data();
        } else {
            var request = new RequestSearchResult(text).filter(TypeField.VIDEO, FormatField.HD);
            if (sort != null && !sort.isEmpty()) {
                request.sortBy(SortField.valueOf(sort));
            }
            searchResult = downloader.search(request).data();
        }

        List<MovieDetail> list = new ArrayList<>();
        List<SearchResultVideoDetails> videos = searchResult.videos();
        for (var item : videos) {
            MovieDetail video = new MovieDetail();
            video.setVod_id(item.videoId());
            video.setVod_name(item.title());
            if (item.richThumbnails() != null && !item.richThumbnails().isEmpty()) {
                video.setVod_pic(item.richThumbnails().get(0));
            }
            video.setVod_remarks(Utils.secondsToDuration(item.lengthSeconds()));
            list.add(video);
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

    private VideoInfo getVideoInfo(String id) {
        RequestVideoInfo request = new RequestVideoInfo(id);
        Response<VideoInfo> response = downloader.getVideoInfo(request);
        return response.data();
    }

    public MovieList detail(String id) {
        VideoInfo video = cache.get(id);

        MovieList result = new MovieList();
        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(id);
        movieDetail.setVod_name(video.details().title());
        movieDetail.setVod_content(video.details().description());
        movieDetail.setVod_director(video.details().author());
        movieDetail.setVod_tag("file");
        if (video.details().thumbnails() != null && !video.details().thumbnails().isEmpty()) {
            movieDetail.setVod_pic(video.details().thumbnails().get(0));
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
        VideoInfo video = cache.get(id);
        List<String> urls = new ArrayList<>();
        List<VideoWithAudioFormat> formats = new ArrayList<>(video.videoWithAudioFormats());
        Collections.reverse(formats);
        for (var format : formats) {
            urls.add(format.qualityLabel() + " " + format.extension().value());
            urls.add(buildProxyUrl(id, format.itag().id()));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("parse", "0");
        if ("com.fongmi.android.tv".equals(client)) {
            result.put("url", urls);
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

        try {
            Path path = Path.of("/data/proxy.txt");
            if (Files.exists(path)) {
                String line = Files.readString(path).trim();
                URI uri = URI.create(line);
                log.debug("use http proxy: {} {}", uri.getHost(), uri.getPort());
                download.proxy(uri.getHost(), uri.getPort());
            }
        } catch (Exception e) {
            log.warn("set http proxy failed", e);
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

}
