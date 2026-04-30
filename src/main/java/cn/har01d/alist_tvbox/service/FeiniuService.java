package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.bili.Sub;
import cn.har01d.alist_tvbox.entity.Feiniu;
import cn.har01d.alist_tvbox.entity.FeiniuRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import cn.har01d.alist_tvbox.model.Filter;
import cn.har01d.alist_tvbox.model.FilterValue;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.util.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Scanner;

import static cn.har01d.alist_tvbox.util.Constants.FOLDER;

@Slf4j
@Service
public class FeiniuService {
    private final FeiniuRepository feiniuRepository;
    private final FeiniuApiClient apiClient;

    private final List<FilterValue> filters = Arrays.asList(
            new FilterValue("加入日期⬇️", "create_time:DESC"),
            new FilterValue("加入日期⬆️", "create_time:ASC"),
            new FilterValue("上映日期⬇️", "release_date:DESC"),
            new FilterValue("上映日期⬆️", "release_date:ASC"),
            new FilterValue("名称⬆️", "sort_title:ASC"),
            new FilterValue("名称⬇️", "sort_title:DESC")
    );

    private volatile LastPlayState lastPlayState;

    public FeiniuService(FeiniuRepository feiniuRepository, FeiniuApiClient apiClient) {
        this.feiniuRepository = feiniuRepository;
        this.apiClient = apiClient;
    }

    public List<Feiniu> findAll() {
        List<Feiniu> list = new ArrayList<>(feiniuRepository.findAll());
        list.sort(Comparator.comparing(item -> Objects.requireNonNullElse(item.getOrder(), 0)));
        return list;
    }

    public Feiniu getById(Integer id) {
        return feiniuRepository.findById(id).orElseThrow(() -> new NotFoundException("站点不存在"));
    }

    public Feiniu create(Feiniu dto) {
        validate(dto);
        if (feiniuRepository.existsByName(dto.getName())) {
            throw new BadRequestException("站点名字重复");
        }
        dto.setId(null);
        return feiniuRepository.save(dto);
    }

    public Feiniu update(int id, Feiniu dto) {
        validate(dto);
        Optional<Feiniu> other = feiniuRepository.findByName(dto.getName());
        if (other.isPresent() && other.get().getId() != id) {
            throw new BadRequestException("站点名字重复");
        }
        dto.setId(id);
        return feiniuRepository.save(dto);
    }

    public void delete(int id) {
        feiniuRepository.deleteById(id);
    }

    public CategoryList category() {
        CategoryList result = new CategoryList();
        List<Category> categories = new ArrayList<>();
        List<Feiniu> sites = findAll();

        if (sites.size() > 1) {
            for (Feiniu site : sites) {
                Category category = new Category();
                category.setType_id(String.valueOf(site.getId()));
                category.setType_name(site.getName());
                category.setType_flag(0);
                categories.add(category);
            }
        } else {
            for (Feiniu site : sites) {
                String token = ensureToken(site);
                JsonNode libs = apiClient.getMediaDbList(site, token);
                for (JsonNode lib : libs) {
                    Category category = new Category();
                    category.setType_id(site.getId() + ":lib:" + lib.path("guid").asText());
                    category.setType_name(lib.path("title").asText());
                    category.setType_flag(0);
                    category.setCover(imageUrl(site, firstText(lib, "poster", "posters")));
                    categories.add(category);
                    result.getFilters().put(category.getType_id(), List.of(new Filter("sort", "排序", filters)));
                }
            }
        }

        result.setCategories(categories);
        result.setTotal(categories.size());
        result.setLimit(categories.size());
        return result;
    }

    public MovieList home() {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();
        for (Feiniu site : findAll()) {
            String token = ensureToken(site);
            JsonNode items = apiClient.getPlayList(site, token);
            for (JsonNode item : items) {
                list.add(toItemDetail(site, item, false));
            }
        }
        result.setList(list);
        result.setTotal(list.size());
        result.setLimit(list.size());
        return result;
    }

    public MovieList list(String id, String sort, Integer pg) {
        MovieList result = new MovieList();
        if (!id.contains(":")) {
            Feiniu site = getById(Integer.parseInt(id));
            String token = ensureToken(site);
            List<MovieDetail> list = new ArrayList<>();
            JsonNode libs = apiClient.getMediaDbList(site, token);
            for (JsonNode lib : libs) {
                MovieDetail movie = new MovieDetail();
                movie.setVod_id(site.getId() + ":lib:" + lib.path("guid").asText());
                movie.setVod_name(lib.path("title").asText());
                movie.setVod_pic(imageUrl(site, firstText(lib, "poster", "posters")));
                movie.setVod_tag(FOLDER);
                list.add(movie);
            }
            result.setList(list);
            result.setTotal(list.size());
            result.setLimit(list.size());
            return result;
        }

        String[] parts = id.split(":");
        Feiniu site = getById(Integer.parseInt(parts[0]));
        String token = ensureToken(site);
        Map<String, Object> body = new LinkedHashMap<>();
        if ("lib".equals(parts[1])) {
            String[] sorts = StringUtils.defaultIfBlank(sort, "create_time:DESC").split(":");
            body.put("tags", Map.of("type", List.of("Movie", "TV", "Directory", "Video")));
            body.put("exclude_grouped_video", 1);
            body.put("sort_type", sorts.length > 1 ? sorts[1] : "DESC");
            body.put("sort_column", sorts[0]);
            body.put("ancestor_guid", parts[2]);
        } else if ("dir".equals(parts[1])) {
            String[] sorts = StringUtils.defaultIfBlank(sort, "sort_title:ASC").split(":");
            body.put("tags", Map.of());
            body.put("sort_type", sorts.length > 1 ? sorts[1] : "ASC");
            body.put("sort_column", sorts[0]);
            body.put("parent_guid", parts[3]);
        } else {
            throw new BadRequestException("不支持的飞牛目录");
        }
        body.put("page", pg == null ? 1 : pg);
        body.put("page_size", 50);

        JsonNode data = apiClient.getItemList(site, token, body);
        List<MovieDetail> list = new ArrayList<>();
        for (JsonNode item : data.path("list")) {
            list.add(toItemDetail(site, item, true));
        }
        result.setList(list);
        result.setTotal(data.path("total").asInt(list.size()));
        result.setLimit(list.size());
        result.setPage(pg == null ? 1 : pg);
        result.setPagecount(Math.max(1, (result.getTotal() + 49) / 50));
        return result;
    }

    public MovieList search(String wd) {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();
        List<Feiniu> sites = findAll();
        boolean multiple = sites.size() > 1;
        for (Feiniu site : sites) {
            String token = ensureToken(site);
            JsonNode items = apiClient.search(site, token, wd);
            for (JsonNode item : items) {
                MovieDetail movie = toItemDetail(site, item, false);
                if (multiple) {
                    movie.setVod_remarks(site.getName() + " " + StringUtils.defaultString(movie.getVod_remarks()));
                }
                list.add(movie);
            }
        }
        result.setList(list);
        result.setTotal(list.size());
        result.setLimit(list.size());
        return result;
    }

    public MovieList detail(String tid) {
        IdParts id = parseItemId(tid);
        Feiniu site = getById(id.siteId());
        String token = ensureToken(site);
        JsonNode item = apiClient.getItem(site, token, id.guid());

        MovieDetail movie = toItemDetail(site, item, false);
        movie.setVod_content(item.path("overview").asText(""));
        movie.setVod_play_from(site.getName());
        movie.setVod_play_url("播放$" + buildItemId(site.getId(), id.guid()));

        if ("TV".equals(item.path("type").asText())) {
            JsonNode seasons = apiClient.getSeasonList(site, token, id.guid());
            List<String> from = new ArrayList<>();
            List<String> urls = new ArrayList<>();
            for (JsonNode season : seasons) {
                JsonNode episodes = apiClient.getEpisodeList(site, token, season.path("guid").asText());
                List<String> episodeUrls = new ArrayList<>();
                for (JsonNode episode : episodes) {
                    episodeUrls.add(episodeLabel(episode) + "$" + buildItemId(site.getId(), episode.path("guid").asText()));
                }
                if (!episodeUrls.isEmpty()) {
                    from.add(season.path("title").asText("剧集"));
                    urls.add(String.join("#", episodeUrls));
                }
            }
            if (!urls.isEmpty()) {
                movie.setVod_play_from(String.join("$$$", from));
                movie.setVod_play_url(String.join("$$$", urls));
            }
        }

        MovieList result = new MovieList();
        result.getList().add(movie);
        result.setTotal(1);
        result.setLimit(1);
        return result;
    }

    public Object play(String id) {
        return play(id, "", "");
    }

    public Object play(String id, String subToken, String baseUrl) {
        IdParts idParts = parseItemId(id);
        Feiniu site = getById(idParts.siteId());
        String token = ensureToken(site);

        JsonNode playInfo = apiClient.getPlayInfo(site, token, idParts.guid());
        JsonNode streamList = apiClient.getStreamList(site, token, idParts.guid());
        JsonNode streamInfo = loadStreamInfo(site, token, playInfo);
        Object url = buildPlayUrl(site, token, playInfo, streamList, streamInfo, subToken, baseUrl);

        lastPlayState = new LastPlayState(
                site,
                token,
                buildItemId(site.getId(), idParts.guid()),
                playInfo.path("media_guid").asText(""),
                playInfo.path("video_guid").asText(""),
                playInfo.path("audio_guid").asText(""),
                playInfo.path("subtitle_guid").asText(""),
                playInfo.path("item").path("duration").asLong(playInfo.path("duration").asLong(0))
        );

        Map<String, Object> result = new HashMap<>();
        result.put("url", url);
        result.put("subs", getSubtitles(site, token, streamList));
        if (isProxyPlayResult(url)) {
            result.put("header", "{\"User-Agent\":\"" + StringUtils.defaultIfBlank(site.getUserAgent(), Constants.USER_AGENT) + "\"}");
        } else {
            result.put("header", "{\"Authorization\":\"" + token + "\",\"Cookie\":\"mode=relay; Trim-MC-token=" + token + "\",\"User-Agent\":\"" + StringUtils.defaultIfBlank(site.getUserAgent(), Constants.USER_AGENT) + "\"}");
        }
        result.put("parse", 0);
        log.debug("result: {}", result);
        return result;
    }

    public void proxy(int siteId, String path, String subToken, String baseUrl,
                      HttpServletRequest request, HttpServletResponse response) throws IOException {
        Feiniu site = getById(siteId);
        String token = ensureToken(site);
        String targetUrl = path.startsWith("http://") || path.startsWith("https://") ? path : site.getUrl() + path;

        HttpURLConnection connection = (HttpURLConnection) new URL(targetUrl).openConnection();
        connection.setRequestMethod(request.getMethod());
        connection.setRequestProperty("Authorization", token);
        connection.setRequestProperty("Cookie", "mode=relay; Trim-MC-token=" + token);
        connection.setRequestProperty("User-Agent", StringUtils.defaultIfBlank(site.getUserAgent(), Constants.USER_AGENT));
        if (StringUtils.isNotBlank(request.getHeader("Range"))) {
            connection.setRequestProperty("Range", request.getHeader("Range"));
        }
        if (StringUtils.isNotBlank(request.getHeader("If-Range"))) {
            connection.setRequestProperty("If-Range", request.getHeader("If-Range"));
        }

        int status = connection.getResponseCode();
        response.setStatus(status);
        if (connection.getContentType() != null) {
            response.setContentType(connection.getContentType());
        }
        if (isPlaylist(targetUrl, connection.getContentType())) {
            String playlist = readText(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            response.getWriter().write(rewritePlaylist(playlist, siteId, subToken, baseUrl, targetUrl));
            return;
        }
        copy(status >= 400 ? connection.getErrorStream() : connection.getInputStream(), response.getOutputStream());
    }

    public void updateProgress(String id, long progress) {
        LastPlayState state = lastPlayState;
        if (state == null || !state.itemId().equals(id)) {
            return;
        }
        long ts = normalizeProgress(progress, state.duration());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("item_guid", state.guid());
        body.put("media_guid", state.mediaGuid());
        body.put("video_guid", state.videoGuid());
        body.put("audio_guid", state.audioGuid());
        body.put("subtitle_guid", state.subtitleGuid());
        body.put("play_link", state.playLink());
        body.put("ts", ts);
        body.put("duration", state.duration());
        apiClient.recordPlay(state.site(), state.token(), body);
    }

    private void validate(Feiniu dto) {
        if (StringUtils.isBlank(dto.getName())) {
            throw new BadRequestException("站点名称不能为空");
        }
        if (StringUtils.isBlank(dto.getUrl())) {
            throw new BadRequestException("站点Url不能为空");
        }
        try {
            new URL(dto.getUrl());
        } catch (Exception e) {
            throw new BadRequestException("站点地址不正确", e);
        }
        if (dto.getUrl().endsWith("/")) {
            dto.setUrl(dto.getUrl().substring(0, dto.getUrl().length() - 1));
        }
        if (StringUtils.isBlank(dto.getToken()) && StringUtils.isBlank(dto.getUsername())) {
            throw new BadRequestException("Token和用户名至少填写一个");
        }
        if (dto.getToken() == null) {
            dto.setToken("");
        }
        if (dto.getUsername() == null) {
            dto.setUsername("");
        }
        if (dto.getPassword() == null) {
            dto.setPassword("");
        }
    }

    private MovieDetail toItemDetail(Feiniu site, JsonNode item, boolean directoryAsFolder) {
        MovieDetail movie = new MovieDetail();
        String type = item.path("type").asText();
        String guid = item.path("guid").asText();
        if ("Directory".equals(type) && directoryAsFolder) {
            movie.setVod_id(site.getId() + ":dir:" + item.path("ancestor_guid").asText() + ":" + guid);
            movie.setVod_tag(FOLDER);
        } else {
            movie.setVod_id(buildItemId(site.getId(), guid));
        }
        movie.setVod_name(item.path("title").asText());
        if ("Episode".equals(type) && StringUtils.isNotBlank(item.path("tv_title").asText())) {
            movie.setVod_name(item.path("tv_title").asText());
        }
        movie.setVod_pic(imageUrl(site, firstText(item, "poster", "poster_list", "posters")));
        movie.setVod_remarks(item.path("vote_average").asText(""));
        movie.setVod_year(yearOf(item));
        return movie;
    }

    private List<Sub> getSubtitles(Feiniu site, String token, JsonNode streamList) {
        List<Sub> subtitles = new ArrayList<>();
        for (JsonNode subNode : streamList.path("subtitle_streams")) {
            if (subNode.has("is_external") && !subNode.path("is_external").asBoolean()) {
                continue;
            }
            String guid = subNode.path("guid").asText("");
            if (guid.isBlank()) {
                continue;
            }
            Sub sub = new Sub();
            sub.setName(StringUtils.defaultIfBlank(subNode.path("title").asText(), guid));
            sub.setLang(subNode.path("language").asText(""));
            sub.setFormat(StringUtils.defaultIfBlank(subNode.path("format").asText(), subNode.path("codec_name").asText("srt")));
            sub.setUrl(site.getUrl() + "/v/api/v1/subtitle/dl/" + guid);
            subtitles.add(sub);
        }
        return subtitles;
    }

    private Object buildPlayUrl(Feiniu site, String token, JsonNode playInfo, JsonNode streamList, JsonNode streamInfo,
                                String subToken, String baseUrl) {
        if (isDirectPlayable(streamInfo)) {
            return buildFallbackUrls(site, playInfo, streamList);
        }

        JsonNode playResponse = startPlay(site, token, playInfo, streamInfo);
        if (playResponse != null && StringUtils.isNotBlank(playResponse.path("play_link").asText())) {
            return List.of(
                    resolutionLabel(preferredResolution(streamInfo)),
                    buildProxyUrl(baseUrl, subToken, site.getId(), playResponse.path("play_link").asText())
            );
        }

        return buildFallbackUrls(site, playInfo, streamList);
    }

    private JsonNode loadStreamInfo(Feiniu site, String token, JsonNode playInfo) {
        String mediaGuid = playInfo.path("media_guid").asText("");
        if (StringUtils.isBlank(mediaGuid)) {
            return null;
        }
        try {
            return apiClient.getStream(site, token, mediaGuid);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isDirectPlayable(JsonNode streamInfo) {
        if (streamInfo == null || streamInfo.isMissingNode() || streamInfo.isNull()) {
            return true;
        }
        JsonNode videoStream = streamInfo.path("video_stream");
        String videoCodec = videoStream.path("codec_name").asText("");
        String wrapper = videoStream.path("wrapper").asText("");
        JsonNode audioStream = firstAudioStream(streamInfo);
        String audioCodec = audioStream.path("codec_name").asText("");

        return "h264".equalsIgnoreCase(videoCodec)
                && "aac".equalsIgnoreCase(audioCodec)
                && ("MP4".equalsIgnoreCase(wrapper) || StringUtils.isBlank(wrapper));
    }

    private JsonNode startPlay(Feiniu site, String token, JsonNode playInfo, JsonNode streamInfo) {
        JsonNode videoStream = streamInfo.path("video_stream");
        JsonNode audioStream = firstAudioStream(streamInfo);
        String mediaGuid = firstNonBlank(
                playInfo.path("media_guid").asText(""),
                videoStream.path("media_guid").asText("")
        );
        String videoGuid = firstNonBlank(
                playInfo.path("video_guid").asText(""),
                videoStream.path("guid").asText("")
        );
        if (StringUtils.isBlank(mediaGuid) || StringUtils.isBlank(videoGuid)) {
            return null;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("media_guid", mediaGuid);
        body.put("video_guid", videoGuid);
        body.put("video_encoder", "h264");
        body.put("resolution", preferredResolution(streamInfo));
        body.put("bitrate", preferredBitrate(streamInfo));
        body.put("startTimestamp", 0L);
        body.put("audio_encoder", "aac");
        body.put("audio_guid", firstNonBlank(
                playInfo.path("audio_guid").asText(""),
                audioStream.path("guid").asText("")
        ));
        body.put("subtitle_guid", playInfo.path("subtitle_guid").asText(""));
        body.put("channels", audioStream.path("channels").asInt(0));

        try {
            return apiClient.startPlay(site, token, body);
        } catch (Exception ignored) {
            return null;
        }
    }

    private JsonNode firstAudioStream(JsonNode streamInfo) {
        JsonNode audioStreams = streamInfo.path("audio_streams");
        if (audioStreams.isArray() && !audioStreams.isEmpty()) {
            return audioStreams.get(0);
        }
        return streamInfo.path("audio_stream");
    }

    private String preferredResolution(JsonNode streamInfo) {
        JsonNode qualities = streamInfo.path("qualities");
        if (qualities.isArray() && !qualities.isEmpty()) {
            String resolution = qualities.get(0).path("resolution").asText("");
            if (StringUtils.isNotBlank(resolution)) {
                return resolution;
            }
        }
        JsonNode videoStream = streamInfo.path("video_stream");
        String resolution = videoStream.path("resolution").asText("");
        if (StringUtils.isNotBlank(resolution)) {
            return resolution;
        }
        resolution = videoStream.path("resolution_type").asText("").replace("p", "");
        if (StringUtils.isNotBlank(resolution)) {
            return resolution;
        }
        int height = videoStream.path("height").asInt(0);
        return height > 0 ? String.valueOf(height) : "";
    }

    private long preferredBitrate(JsonNode streamInfo) {
        JsonNode qualities = streamInfo.path("qualities");
        if (qualities.isArray() && !qualities.isEmpty()) {
            long bitrate = qualities.get(0).path("bitrate").asLong(0);
            if (bitrate > 0) {
                return bitrate;
            }
        }
        JsonNode videoStream = streamInfo.path("video_stream");
        long bitrate = videoStream.path("bps").asLong(0);
        if (bitrate > 0) {
            return bitrate;
        }
        return videoStream.path("bitrate").asLong(0);
    }

    private String resolutionLabel(String resolution) {
        if (StringUtils.isBlank(resolution)) {
            return "转码";
        }
        return resolution.endsWith("p") ? resolution : resolution + "p";
    }

    private boolean isProxyPlayResult(Object url) {
        if (url instanceof String value) {
            return value.contains("/feiniu-proxy/");
        }
        if (url instanceof List<?> values) {
            for (Object value : values) {
                if (value instanceof String string && string.contains("/feiniu-proxy/")) {
                    return true;
                }
            }
        }
        return false;
    }

    String rewritePlaylist(String playlist, int siteId, String token, String baseUrl, String currentUrl) {
        if (StringUtils.isBlank(playlist)) {
            return "";
        }
        URI current = URI.create(currentUrl);
        StringBuilder builder = new StringBuilder();
        for (String line : playlist.split("\\R", -1)) {
            if (line.isBlank()) {
                builder.append(line).append('\n');
                continue;
            }
            if (line.startsWith("#")) {
                builder.append(rewriteTaggedUriLine(line, siteId, token, baseUrl, current)).append('\n');
                continue;
            }
            String resolved = current.resolve(line).toString();
            builder.append(buildProxyUrl(baseUrl, token, siteId, resolved)).append('\n');
        }
        return builder.toString();
    }

    private String rewriteTaggedUriLine(String line, int siteId, String token, String baseUrl, URI current) {
        int uriIndex = line.indexOf("URI=\"");
        if (uriIndex < 0) {
            return line;
        }
        int start = uriIndex + 5;
        int end = line.indexOf('"', start);
        if (end < 0) {
            return line;
        }
        String original = line.substring(start, end);
        String resolved = current.resolve(original).toString();
        return line.substring(0, start)
                + buildProxyUrl(baseUrl, token, siteId, resolved)
                + line.substring(end);
    }

    private String buildProxyUrl(String baseUrl, String token, int siteId, String path) {
        return UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/feiniu-proxy/{token}")
                .queryParam("site", siteId)
                .queryParam("path", URLEncoder.encode(path, StandardCharsets.UTF_8).replace("+", "%20"))
                .buildAndExpand(token)
                .toUriString();
    }

    private boolean isPlaylist(String targetUrl, String contentType) {
        return targetUrl.contains(".m3u8")
                || (contentType != null && (contentType.contains("mpegurl") || contentType.contains("application/vnd.apple.mpegurl")));
    }

    private String readText(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        try (InputStream in = inputStream; Scanner scanner = new Scanner(in, StandardCharsets.UTF_8)) {
            scanner.useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        }
    }

    private void copy(InputStream inputStream, OutputStream outputStream) throws IOException {
        if (inputStream == null) {
            return;
        }
        try (InputStream in = inputStream) {
            byte[] buffer = new byte[64 * 1024];
            int len;
            while ((len = in.read(buffer)) != -1) {
                outputStream.write(buffer, 0, len);
            }
        }
    }

    private Object buildFallbackUrls(Feiniu site, JsonNode playInfo, JsonNode streamList) {
        List<String> urls = new ArrayList<>();
        JsonNode videoStreams = streamList.path("video_streams");
        if (videoStreams.isArray() && !videoStreams.isEmpty()) {
            for (JsonNode stream : videoStreams) {
                String mediaGuid = stream.path("media_guid").asText();
                urls.add(StringUtils.defaultIfBlank(stream.path("resolution_type").asText(), stream.path("title").asText("默认")));
                urls.add(apiClient.getMediaRangeUrl(site, mediaGuid));
            }
            return urls;
        }
        return apiClient.getMediaRangeUrl(site, playInfo.path("media_guid").asText());
    }

    private String ensureToken(Feiniu site) {
        String token = site.getToken();
        if (StringUtils.isNotBlank(token)) {
            try {
                apiClient.getUserInfo(site, token);
                return token;
            } catch (Exception ignored) {
                // fall through to relogin
            }
        }
        if (StringUtils.isBlank(site.getUsername()) || StringUtils.isBlank(site.getPassword())) {
            throw new BadRequestException(site.getName() + " 缺少有效Token，且未配置用户名密码");
        }
        String newToken = apiClient.login(site);
        site.setToken(newToken);
        feiniuRepository.save(site);
        return newToken;
    }

    private String imageUrl(Feiniu site, String path) {
        if (StringUtils.isBlank(path)) {
            return null;
        }
        if (path.startsWith("http")) {
            return path;
        }
        return site.getUrl() + path;
    }

    private String firstText(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (value.isTextual() && StringUtils.isNotBlank(value.asText())) {
                return value.asText();
            }
            if (value.isArray() && !value.isEmpty() && value.get(0).isTextual()) {
                return value.get(0).asText();
            }
        }
        return "";
    }

    private String yearOf(JsonNode item) {
        String date = firstText(item, "release_date", "air_date");
        if (date.length() >= 4) {
            return date.substring(0, 4);
        }
        return "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private String buildItemId(int siteId, String guid) {
        return siteId + ":item:" + guid;
    }

    private IdParts parseItemId(String id) {
        String[] parts = id.split(":");
        if (parts.length < 3 || !"item".equals(parts[1])) {
            throw new BadRequestException("无效的飞牛资源ID");
        }
        return new IdParts(Integer.parseInt(parts[0]), parts[2]);
    }

    private String episodeLabel(JsonNode item) {
        String title = item.path("title").asText();
        int episode = item.path("episode_number").asInt(0);
        if (title.equals("第 " + episode + " 集") || episode == 0) {
            return title;
        }
        return episode + "." + title;
    }

    private long normalizeProgress(long progress, long duration) {
        if (duration > 0 && progress > duration * 10) {
            return progress / 1000;
        }
        return progress;
    }

    private record IdParts(int siteId, String guid) {
    }

    private record LastPlayState(Feiniu site, String token, String itemId, String mediaGuid, String videoGuid,
                                 String audioGuid, String subtitleGuid, long duration) {
        private String guid() {
            return itemId.substring(itemId.lastIndexOf(':') + 1);
        }

        private String playLink() {
            try {
                return new URL(site.getUrl()).getHost();
            } catch (Exception e) {
                return site.getUrl();
            }
        }
    }
}
