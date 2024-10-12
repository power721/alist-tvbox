package com.github.kiulian.downloader.parser;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.github.kiulian.downloader.Config;
import com.github.kiulian.downloader.YoutubeException;
import com.github.kiulian.downloader.YoutubeException.BadPageException;
import com.github.kiulian.downloader.cipher.Cipher;
import com.github.kiulian.downloader.cipher.CipherFactory;
import com.github.kiulian.downloader.cipher.CipherFunction;
import com.github.kiulian.downloader.downloader.Downloader;
import com.github.kiulian.downloader.downloader.YoutubeCallback;
import com.github.kiulian.downloader.downloader.client.ClientType;
import com.github.kiulian.downloader.downloader.request.*;
import com.github.kiulian.downloader.downloader.response.Response;
import com.github.kiulian.downloader.downloader.response.ResponseImpl;
import com.github.kiulian.downloader.extractor.Extractor;
import com.github.kiulian.downloader.model.playlist.PlaylistDetails;
import com.github.kiulian.downloader.model.playlist.PlaylistInfo;
import com.github.kiulian.downloader.model.playlist.PlaylistVideoDetails;
import com.github.kiulian.downloader.model.search.*;
import com.github.kiulian.downloader.model.search.query.*;
import com.github.kiulian.downloader.model.subtitles.SubtitlesInfo;
import com.github.kiulian.downloader.model.videos.VideoDetails;
import com.github.kiulian.downloader.model.videos.VideoInfo;
import com.github.kiulian.downloader.model.videos.formats.*;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public class ParserImpl implements Parser {
    private static final String ANDROID_APIKEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";
    private static final String BASE_API_URL = "https://www.youtube.com/youtubei/v1";


    private static class DelegatedCipherFactory implements CipherFactory {
        Cipher lastCipher;
        final CipherFactory factory;

        DelegatedCipherFactory(CipherFactory factory) {
            this.factory = factory;
        }

        @Override
        public Cipher createCipher(String jsUrl) throws YoutubeException {
            if (jsUrl == null)
                return lastCipher;
            return lastCipher = factory.createCipher(jsUrl);

        }

        @Override
        public void addInitialFunctionPattern(int priority, String regex) {
            factory.addInitialFunctionPattern(priority, regex);
        }

        @Override
        public void addFunctionEquivalent(String regex, CipherFunction function) {
            factory.addFunctionEquivalent(regex, function);
        }

        Cipher getLastCipher() {
            return lastCipher;
        }

        void invalidateLastCipher() {
            this.lastCipher = null;
        }
    }

    private final Config config;
    private final Downloader downloader;
    private final Extractor extractor;
    private final DelegatedCipherFactory cipherFactory;


    public ParserImpl(Config config, Downloader downloader, Extractor extractor, CipherFactory cipherFactory) {
        this.config = config;
        this.downloader = downloader;
        this.extractor = extractor;
        this.cipherFactory = new DelegatedCipherFactory(cipherFactory);


    }

    @Override
    public Response<VideoInfo> parseVideo(RequestVideoInfo request) {
        if (request.isAsync()) {
            ExecutorService executorService = config.getExecutorService();
            Future<VideoInfo> result = executorService.submit(() -> parseVideo(request.getVideoId(), request.getCallback(), request.getClientType()));
            return ResponseImpl.fromFuture(result);
        }
        try {
            VideoInfo result = parseVideo(request.getVideoId(), request.getCallback(), request.getClientType());
            return ResponseImpl.from(result);
        } catch (YoutubeException e) {
            return ResponseImpl.error(e);
        }
    }

    private VideoInfo parseVideo(String videoId, YoutubeCallback<VideoInfo> callback, ClientType client) throws YoutubeException {
        // try to spoof android
        // workaround for issue https://github.com/sealedtx/java-youtube-downloader/issues/97
        VideoInfo videoInfo = parseVideoAndroid(videoId, callback, client);
        if (videoInfo == null) {
            videoInfo = parseVideoWeb(videoId, callback);
        }
        if (callback != null) {
            callback.onFinished(videoInfo);
        }
        return videoInfo;
    }

    private VideoInfo parseVideoAndroid(String videoId, YoutubeCallback<VideoInfo> callback, ClientType client) throws YoutubeException {
        String url = BASE_API_URL + "/player?key=" + ANDROID_APIKEY;


        RequestWebpage request = new RequestWebpage(url, "POST", client.getBody().fluentPut("videoId", videoId).toJSONString())
                .header("Content-Type", "application/json");

        Response<String> response = downloader.downloadWebpage(request);
        if (!response.ok()) {
            return null;
        }

        JSONObject playerResponse;
        try {
            playerResponse = JSONObject.parseObject(response.data());
        } catch (Exception ignore) {
            return null;
        }

        VideoDetails videoDetails = parseVideoDetails(videoId, playerResponse);
        if (videoDetails.isDownloadable()) {
            JSONObject context = playerResponse.getJSONObject("responseContext");
            String clientVersion = extractor.extractClientVersionFromContext(context);
            List<Format> formats;
            try {
                formats = parseFormats(playerResponse, null, clientVersion);
            } catch (YoutubeException.InvalidJsUrlException e) {
                JSONObject playerConfig = downloadPlayerConfig(videoId, callback);
                String jsUrl;
                try {
                    jsUrl = extractor.extractJsUrlFromConfig(playerConfig, videoId);
                    formats = parseFormats(playerResponse, jsUrl, clientVersion);
                } catch (YoutubeException ex) {
                    if (callback != null) {
                        callback.onError(ex);
                    }
                    throw ex;
                }
            } catch (YoutubeException e) {
                if (callback != null) {
                    callback.onError(e);
                }
                throw e;
            }

            List<SubtitlesInfo> subtitlesInfo = parseCaptions(playerResponse);
            return new VideoInfo(videoDetails, formats, subtitlesInfo);
        } else {
            return new VideoInfo(videoDetails, Collections.emptyList(), Collections.emptyList());
        }

    }

    private JSONObject downloadPlayerConfig(String videoId, YoutubeCallback<VideoInfo> callback) throws YoutubeException {
        String htmlUrl = "https://www.youtube.com/watch?v=" + videoId;

        Response<String> response = downloader.downloadWebpage(new RequestWebpage(htmlUrl));
        if (!response.ok()) {
            YoutubeException e = new YoutubeException.DownloadException(String.format("Could not load url: %s, exception: %s", htmlUrl, response.error().getMessage()));
            if (callback != null) {
                callback.onError(e);
            }
            throw e;
        }
        String html = response.data();

        JSONObject playerConfig;
        try {
            playerConfig = extractor.extractPlayerConfigFromHtml(html);
        } catch (YoutubeException e) {
            if (callback != null) {
                callback.onError(e);
            }
            throw e;
        }
        return playerConfig;
    }

    private VideoInfo parseVideoWeb(String videoId, YoutubeCallback<VideoInfo> callback) throws YoutubeException {
        JSONObject playerConfig = downloadPlayerConfig(videoId, callback);

        JSONObject args = playerConfig.getJSONObject("args");
        JSONObject playerResponse = args.getJSONObject("player_response");

        if (!playerResponse.containsKey("streamingData") && !playerResponse.containsKey("videoDetails")) {
            YoutubeException e = new YoutubeException.BadPageException("streamingData and videoDetails not found");
            if (callback != null) {
                callback.onError(e);
            }
            throw e;
        }

        VideoDetails videoDetails = parseVideoDetails(videoId, playerResponse);
        if (videoDetails.isDownloadable()) {
            String jsUrl;
            try {
                jsUrl = extractor.extractJsUrlFromConfig(playerConfig, videoId);
            } catch (YoutubeException e) {
                if (callback != null) {
                    callback.onError(e);
                }
                throw e;
            }
            JSONObject context = playerConfig.getJSONObject("args").getJSONObject("player_response").getJSONObject("responseContext");
            String clientVersion = extractor.extractClientVersionFromContext(context);
            List<Format> formats;
            try {
                formats = parseFormats(playerResponse, jsUrl, clientVersion);
            } catch (YoutubeException e) {
                if (callback != null) {
                    callback.onError(e);
                }
                throw e;
            }
            List<SubtitlesInfo> subtitlesInfo = parseCaptions(playerResponse);
            return new VideoInfo(videoDetails, formats, subtitlesInfo);
        } else {
            return new VideoInfo(videoDetails, Collections.emptyList(), Collections.emptyList());
        }
    }

    private VideoDetails parseVideoDetails(String videoId, JSONObject playerResponse) {
        if (!playerResponse.containsKey("videoDetails")) {
            return new VideoDetails(videoId);
        }

        JSONObject videoDetails = playerResponse.getJSONObject("videoDetails");
        String liveHLSUrl = null;
        if (videoDetails.getBooleanValue("isLive")) {
            if (playerResponse.containsKey("streamingData")) {
                liveHLSUrl = playerResponse.getJSONObject("streamingData").getString("hlsManifestUrl");
            }
        }
        return new VideoDetails(videoDetails, liveHLSUrl);
    }

    private List<Format> parseFormats(JSONObject playerResponse, String jsUrl, String clientVersion) throws YoutubeException {
        if (!playerResponse.containsKey("streamingData")) {
            throw new YoutubeException.BadPageException("streamingData not found");
        }

        JSONObject streamingData = playerResponse.getJSONObject("streamingData");
        JSONArray jsonFormats = new JSONArray();
        if (streamingData.containsKey("formats")) {
            jsonFormats.addAll(streamingData.getJSONArray("formats"));
        }
        JSONArray jsonAdaptiveFormats = new JSONArray();
        if (streamingData.containsKey("adaptiveFormats")) {
            jsonAdaptiveFormats.addAll(streamingData.getJSONArray("adaptiveFormats"));
        }

        List<Format> formats = new ArrayList<>(jsonFormats.size() + jsonAdaptiveFormats.size());
        populateFormats(formats, jsonFormats, jsUrl, false, clientVersion);
        populateFormats(formats, jsonAdaptiveFormats, jsUrl, true, clientVersion);
        return formats;
    }

    private void populateFormats(List<Format> formats, JSONArray jsonFormats, String jsUrl, boolean isAdaptive, String clientVersion) throws YoutubeException.CipherException {
        for (int i = 0; i < jsonFormats.size(); i++) {
            JSONObject json = jsonFormats.getJSONObject(i);
            if ("FORMAT_STREAM_TYPE_OTF".equals(json.getString("type")))
                continue; // unsupported otf formats which cause 404 not found

            int itagValue = json.getIntValue("itag");
            Itag itag;
            try {
                itag = Itag.valueOf("i" + itagValue);
            } catch (IllegalArgumentException e) {
                System.err.println("Error parsing format: unknown itag " + itagValue);
                continue;
            }

            try {
                Format format = parseFormat(json, jsUrl, itag, isAdaptive, clientVersion);
                formats.add(format);
            } catch (YoutubeException.CipherException e) {
                throw e;
            } catch (YoutubeException e) {
                System.err.println("Error " + e.getMessage() + " parsing format: " + json);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    private Format parseFormat(JSONObject json, String jsUrl, Itag itag, boolean isAdaptive, String clientVersion) throws YoutubeException {
        if (json.containsKey("signatureCipher")) {
            JSONObject jsonCipher = new JSONObject();
            String[] cipherData = json.getString("signatureCipher").replace("\\u0026", "&").split("&");
            for (String s : cipherData) {
                String[] keyValue = s.split("=");
                jsonCipher.put(keyValue[0], keyValue[1]);
            }
            if (!jsonCipher.containsKey("url")) {
                throw new YoutubeException.BadPageException("Could not found url in cipher data");
            }
            String urlWithSig = jsonCipher.getString("url");
            try {
                urlWithSig = URLDecoder.decode(urlWithSig, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }

            if (urlWithSig.contains("signature")
                    || (!jsonCipher.containsKey("s") && (urlWithSig.contains("&sig=") || urlWithSig.contains("&lsig=")))) {
                // do nothing, this is pre-signed videos with signature
            } else if (jsUrl != null || cipherFactory.getLastCipher() != null) {
                String s = jsonCipher.getString("s");
                try {
                    s = URLDecoder.decode(s, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
                Cipher cipher = cipherFactory.createCipher(jsUrl);

                String signature = cipher.getSignature(s);
                String decipheredUrl = urlWithSig + "&sig=" + signature;
                json.put("url", decipheredUrl);
            } else {

                throw new YoutubeException.InvalidJsUrlException("deciphering is required but no js url");

            }
        }

        boolean hasVideo = itag.isVideo() || json.containsKey("size") || json.containsKey("width");
        boolean hasAudio = itag.isAudio() || json.containsKey("audioQuality");

        if (hasVideo && hasAudio)
            return new VideoWithAudioFormat(json, isAdaptive, clientVersion);
        else if (hasVideo)
            return new VideoFormat(json, isAdaptive, clientVersion);
        return new AudioFormat(json, isAdaptive, clientVersion);
    }

    private List<SubtitlesInfo> parseCaptions(JSONObject playerResponse) {
        if (!playerResponse.containsKey("captions")) {
            return Collections.emptyList();
        }
        JSONObject captions = playerResponse.getJSONObject("captions");

        JSONObject playerCaptionsTracklistRenderer = captions.getJSONObject("playerCaptionsTracklistRenderer");
        if (playerCaptionsTracklistRenderer == null || playerCaptionsTracklistRenderer.isEmpty()) {
            return Collections.emptyList();
        }

        JSONArray captionsArray = playerCaptionsTracklistRenderer.getJSONArray("captionTracks");
        if (captionsArray == null || captionsArray.isEmpty()) {
            return Collections.emptyList();
        }

        List<SubtitlesInfo> subtitlesInfo = new ArrayList<>();
        for (int i = 0; i < captionsArray.size(); i++) {
            JSONObject subtitleInfo = captionsArray.getJSONObject(i);
            String language = subtitleInfo.getString("languageCode");
            String url = subtitleInfo.getString("baseUrl");
            String vssId = subtitleInfo.getString("vssId");

            if (language != null && url != null && vssId != null) {
                boolean isAutoGenerated = vssId.startsWith("a.");
                subtitlesInfo.add(new SubtitlesInfo(url, language, isAutoGenerated, true));
            }
        }
        return subtitlesInfo;
    }

    @Override
    public Response<PlaylistInfo> parsePlaylist(RequestPlaylistInfo request) {
        if (request.isAsync()) {
            ExecutorService executorService = config.getExecutorService();
            Future<PlaylistInfo> result = executorService.submit(() -> parsePlaylist(request.getPlaylistId(), request.getCallback(), request.getClientType()));
            return ResponseImpl.fromFuture(result);
        }
        try {
            PlaylistInfo result = parsePlaylist(request.getPlaylistId(), request.getCallback(), request.getClientType());
            return ResponseImpl.from(result);
        } catch (YoutubeException e) {
            return ResponseImpl.error(e);
        }

    }

    private PlaylistInfo parsePlaylist(String playlistId, YoutubeCallback<PlaylistInfo> callback, ClientType client) throws YoutubeException {
        String htmlUrl = "https://www.youtube.com/playlist?list=" + playlistId;

        Response<String> response = downloader.downloadWebpage(new RequestWebpage(htmlUrl));
        if (!response.ok()) {
            YoutubeException e = new YoutubeException.DownloadException(String.format("Could not load url: %s, exception: %s", htmlUrl, response.error().getMessage()));
            if (callback != null) {
                callback.onError(e);
            }
            throw e;
        }
        String html = response.data();

        JSONObject initialData;
        try {
            initialData = extractor.extractInitialDataFromHtml(html);
        } catch (YoutubeException e) {
            if (callback != null) {
                callback.onError(e);
            }
            throw e;
        }

        if (!initialData.containsKey("metadata")) {
            throw new YoutubeException.BadPageException("Invalid initial data json");
        }

        PlaylistDetails playlistDetails = parsePlaylistDetails(playlistId, initialData);

        List<PlaylistVideoDetails> videos;
        try {
            videos = parsePlaylistVideos(initialData, playlistDetails.videoCount(), client);
        } catch (YoutubeException e) {
            if (callback != null) {
                callback.onError(e);
            }
            throw e;
        }
        return new PlaylistInfo(playlistDetails, videos);
    }

    private PlaylistDetails parsePlaylistDetails(String playlistId, JSONObject initialData) {
        String title = initialData.getJSONObject("metadata")
                .getJSONObject("playlistMetadataRenderer")
                .getString("title");
        JSONArray sideBarItems = initialData.getJSONObject("sidebar").getJSONObject("playlistSidebarRenderer").getJSONArray("items");
        String author = null;
        try {
            // try to retrieve author, some playlists may have no author
            author = sideBarItems.getJSONObject(1)
                    .getJSONObject("playlistSidebarSecondaryInfoRenderer")
                    .getJSONObject("videoOwner")
                    .getJSONObject("videoOwnerRenderer")
                    .getJSONObject("title")
                    .getJSONArray("runs")
                    .getJSONObject(0)
                    .getString("text");
        } catch (Exception ignored) {
        }
        JSONArray stats = sideBarItems.getJSONObject(0)
                .getJSONObject("playlistSidebarPrimaryInfoRenderer")
                .getJSONArray("stats");
        int videoCount = extractor.extractIntegerFromText(stats.getJSONObject(0).getJSONArray("runs").getJSONObject(0).getString("text"));
        long viewCount = extractor.extractLongFromText(stats.getJSONObject(1).getString("simpleText"));

        return new PlaylistDetails(playlistId, title, author, videoCount, viewCount);
    }

    private List<PlaylistVideoDetails> parsePlaylistVideos(JSONObject initialData, int videoCount, ClientType client) throws YoutubeException {
        JSONObject content;

        try {
            content = initialData.getJSONObject("contents")
                    .getJSONObject("twoColumnBrowseResultsRenderer")
                    .getJSONArray("tabs").getJSONObject(0)
                    .getJSONObject("tabRenderer")
                    .getJSONObject("content")
                    .getJSONObject("sectionListRenderer")
                    .getJSONArray("contents").getJSONObject(0)
                    .getJSONObject("itemSectionRenderer")
                    .getJSONArray("contents").getJSONObject(0)
                    .getJSONObject("playlistVideoListRenderer");
        } catch (NullPointerException e) {
            throw new YoutubeException.BadPageException("Playlist initial data not found");
        }

        List<PlaylistVideoDetails> videos;
        if (videoCount > 0) {
            videos = new ArrayList<>(videoCount);
        } else {
            videos = new LinkedList<>();
        }


        populatePlaylist(content, videos, client);
        return videos;
    }

    private void populatePlaylist(JSONObject content, List<PlaylistVideoDetails> videos, ClientType client) throws YoutubeException {
        JSONArray contents;
        if (content.containsKey("contents")) { // parse first items (up to 100)
            contents = content.getJSONArray("contents");
        } else if (content.containsKey("continuationItems")) { // parse continuationItems
            contents = content.getJSONArray("continuationItems");
        } else if (content.containsKey("continuations")) { // load continuation
            JSONObject nextContinuationData = content.getJSONArray("continuations")
                    .getJSONObject(0)
                    .getJSONObject("nextContinuationData");
            String continuation = nextContinuationData.getString("continuation");
            String ctp = nextContinuationData.getString("clickTrackingParams");
            loadPlaylistContinuation(continuation, ctp, videos, client);
            return;
        } else { // nothing found
            return;
        }

        for (int i = 0; i < contents.size(); i++) {
            JSONObject contentsItem = contents.getJSONObject(i);
            if (contentsItem.containsKey("playlistVideoRenderer")) {
                videos.add(new PlaylistVideoDetails(contentsItem.getJSONObject("playlistVideoRenderer")));
            } else {
                if (contentsItem.containsKey("continuationItemRenderer")) {
                    JSONObject continuationEndpoint = contentsItem.getJSONObject("continuationItemRenderer")
                            .getJSONObject("continuationEndpoint");
                    String continuation = continuationEndpoint.getJSONObject("continuationCommand").getString("token");
                    String ctp = continuationEndpoint.getString("clickTrackingParams");
                    loadPlaylistContinuation(continuation, ctp, videos, client);
                }
            }
        }
    }

    private void loadPlaylistContinuation(String continuation, String ctp, List<PlaylistVideoDetails> videos, ClientType client) throws YoutubeException {
        JSONObject content;
        String url = BASE_API_URL + "/browse?key=" + ANDROID_APIKEY;
        JSONObject body = client.getBody()
                .fluentPut("continuation", continuation)
                .fluentPut("clickTracking", new JSONObject()
                        .fluentPut("clickTrackingParams", ctp));


        RequestWebpage request = new RequestWebpage(url, "POST", body.toJSONString())
                .header("X-YouTube-Client-Name", "1")
                .header("X-YouTube-Client-Version", client.getVersion())
                .header("Content-Type", "application/json");

        Response<String> response = downloader.downloadWebpage(request);
        if (!response.ok()) {
            throw new YoutubeException.DownloadException(String.format("Could not load url: %s, exception: %s", url, response.error().getMessage()));
        }
        String html = response.data();

        try {
            JSONObject jsonResponse = JSON.parseObject(html);

            if (jsonResponse.containsKey("continuationContents")) {
                content = jsonResponse
                        .getJSONObject("continuationContents")
                        .getJSONObject("playlistVideoListContinuation");
            } else {
                content = jsonResponse.getJSONArray("onResponseReceivedActions")
                        .getJSONObject(0)
                        .getJSONObject("appendContinuationItemsAction");
            }
            populatePlaylist(content, videos, client);
        } catch (YoutubeException e) {
            throw e;
        } catch (Exception e) {
            throw new YoutubeException.BadPageException("Could not parse playlist continuation json");
        }
    }

    @Override
    public Response<PlaylistInfo> parseChannelsUploads(RequestChannelUploads request) {
        if (request.isAsync()) {
            ExecutorService executorService = config.getExecutorService();
            Future<PlaylistInfo> result = executorService.submit(() -> parseChannelsUploads(request.getChannelId(), request.getCallback(), request.getClientType()));
            return ResponseImpl.fromFuture(result);
        }
        try {
            PlaylistInfo result = parseChannelsUploads(request.getChannelId(), request.getCallback(), request.getClientType());
            return ResponseImpl.from(result);
        } catch (YoutubeException e) {
            return ResponseImpl.error(e);
        }
    }

    private PlaylistInfo parseChannelsUploads(String channelId, YoutubeCallback<PlaylistInfo> callback, ClientType client) throws YoutubeException {
        String playlistId = null;
        if (channelId.length() == 24 && channelId.startsWith("UC")) { // channel id pattern
            playlistId = "UU" + channelId.substring(2); // replace "UC" with "UU"
        } else { // channel name
            String channelLink = "https://www.youtube.com/c/" + channelId + "/videos?view=57";

            Response<String> response = downloader.downloadWebpage(new RequestWebpage(channelLink));
            if (!response.ok()) {
                YoutubeException e = new YoutubeException.DownloadException(String.format("Could not load url: %s, exception: %s", channelLink, response.error().getMessage()));
                if (callback != null) {
                    callback.onError(e);
                }
                throw e;
            }
            String html = response.data();

            Scanner scan = new Scanner(html);
            scan.useDelimiter("list=");
            while (scan.hasNext()) {
                String pId = scan.next();
                if (pId.startsWith("UU")) {
                    playlistId = pId.substring(0, 24);
                    break;
                }
            }
        }
        if (playlistId == null) {
            final YoutubeException e = new YoutubeException.BadPageException("Upload Playlist not found");
            if (callback != null) {
                callback.onError(e);
            }
            throw e;
        }
        return parsePlaylist(playlistId, callback, client);
    }

    @Override
    public Response<List<SubtitlesInfo>> parseSubtitlesInfo(RequestSubtitlesInfo request) {
        if (request.isAsync()) {
            ExecutorService executorService = config.getExecutorService();
            Future<List<SubtitlesInfo>> result = executorService.submit(() -> parseSubtitlesInfo(request.getVideoId(), request.getCallback()));
            return ResponseImpl.fromFuture(result);
        }
        try {
            List<SubtitlesInfo> result = parseSubtitlesInfo(request.getVideoId(), request.getCallback());
            return ResponseImpl.from(result);
        } catch (YoutubeException e) {
            return ResponseImpl.error(e);
        }
    }

    private List<SubtitlesInfo> parseSubtitlesInfo(String videoId, YoutubeCallback<List<SubtitlesInfo>> callback) throws YoutubeException {
        String xmlUrl = "https://video.google.com/timedtext?hl=en&type=list&v=" + videoId;

        Response<String> response = downloader.downloadWebpage(new RequestWebpage(xmlUrl));
        if (!response.ok()) {
            YoutubeException e = new YoutubeException.DownloadException(String.format("Could not load url: %s, exception: %s", xmlUrl, response.error().getMessage()));
            if (callback != null) {
                callback.onError(e);
            }
            throw e;
        }
        String xml = response.data();
        List<String> languages;
        try {
            languages = extractor.extractSubtitlesLanguagesFromXml(xml);
        } catch (YoutubeException e) {
            if (callback != null) {
                callback.onError(e);
            }
            throw e;
        }

        List<SubtitlesInfo> subtitlesInfo = new ArrayList<>();
        for (String language : languages) {
            String url = String.format("https://www.youtube.com/api/timedtext?lang=%s&v=%s",
                    language, videoId);
            subtitlesInfo.add(new SubtitlesInfo(url, language, false));
        }

        return subtitlesInfo;
    }

    @Override
    public Response<SearchResult> parseSearchResult(RequestSearchResult request) {
        if (request.isAsync()) {
            ExecutorService executorService = config.getExecutorService();
            Future<SearchResult> result = executorService.submit(() -> parseSearchResult(request.query(), request.encodeParameters(), request.getCallback()));
            return ResponseImpl.fromFuture(result);
        }
        try {
            SearchResult result = parseSearchResult(request.query(), request.encodeParameters(), request.getCallback());
            return ResponseImpl.from(result);
        } catch (YoutubeException e) {
            return ResponseImpl.error(e);
        }
    }

    @Override
    public Response<SearchResult> parseSearchContinuation(RequestSearchContinuation request) {
        if (request.isAsync()) {
            ExecutorService executorService = config.getExecutorService();
            Future<SearchResult> result = executorService.submit(() -> parseSearchContinuation(request.continuation(), request.getCallback(), request.getClientType()));
            return ResponseImpl.fromFuture(result);
        }
        try {
            SearchResult result = parseSearchContinuation(request.continuation(), request.getCallback(), request.getClientType());
            return ResponseImpl.from(result);
        } catch (YoutubeException e) {
            return ResponseImpl.error(e);
        }
    }

    @Override
    public Response<SearchResult> parseSearcheable(RequestSearchable request) {
        if (request.isAsync()) {
            ExecutorService executorService = config.getExecutorService();
            Future<SearchResult> result = executorService.submit(() -> parseSearchable(request.searchPath(), request.getCallback()));
            return ResponseImpl.fromFuture(result);
        }
        try {
            SearchResult result = parseSearchable(request.searchPath(), request.getCallback());
            return ResponseImpl.from(result);
        } catch (YoutubeException e) {
            return ResponseImpl.error(e);
        }
    }

    private SearchResult parseSearchResult(String query, String parameters, YoutubeCallback<SearchResult> callback) throws YoutubeException {
        String searchQuery;
        try {
            searchQuery = URLEncoder.encode(query, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            searchQuery = query;
            e.printStackTrace();
        }
        String url = "https://www.youtube.com/results?search_query=" + searchQuery;
        if (parameters != null) {
            url += "&sp=" + parameters;
        }
        try {
            return parseHtmlSearchResult(url);
        } catch (YoutubeException e) {
            if (callback != null) {
                callback.onError(e);
            }
            throw e;
        }
    }

    private SearchResult parseSearchable(String searchPath, YoutubeCallback<SearchResult> callback) throws YoutubeException {
        String url = "https://www.youtube.com" + searchPath;
        try {
            return parseHtmlSearchResult(url);
        } catch (YoutubeException e) {
            if (callback != null) {
                callback.onError(e);
            }
            throw e;
        }
    }

    private SearchResult parseHtmlSearchResult(String url) throws YoutubeException {
        Response<String> response = downloader.downloadWebpage(new RequestWebpage(url));
        if (!response.ok()) {
            throw new YoutubeException.DownloadException(String.format("Could not load url: %s, exception: %s", url, response.error().getMessage()));
        }

        String html = response.data();

        JSONObject initialData = extractor.extractInitialDataFromHtml(html);
        JSONArray rootContents;
        try {
            rootContents = initialData.getJSONObject("contents")
                    .getJSONObject("twoColumnSearchResultsRenderer")
                    .getJSONObject("primaryContents")
                    .getJSONObject("sectionListRenderer")
                    .getJSONArray("contents");
        } catch (NullPointerException e) {
            throw new YoutubeException.BadPageException("Search result root contents not found");
        }

        long estimatedCount = extractor.extractLongFromText(initialData.getString("estimatedResults"));
        String clientVersion = extractor.extractClientVersionFromContext(initialData.getJSONObject("responseContext"));
        SearchContinuation continuation = getSearchContinuation(rootContents, clientVersion);
        return parseSearchResult(estimatedCount, rootContents, continuation);
    }

    private SearchResult parseSearchContinuation(SearchContinuation continuation, YoutubeCallback<SearchResult> callback, ClientType client) throws YoutubeException {
        String url = BASE_API_URL + "/search?key=" + ANDROID_APIKEY + "&prettyPrint=false";
        JSONObject body = client.getBody()
                .fluentPut("continuation", continuation.token())
                .fluentPut("clickTracking", new JSONObject()
                        .fluentPut("clickTrackingParams", continuation.clickTrackingParameters()));


        RequestWebpage request = new RequestWebpage(url, "POST", body.toJSONString())
                .header("X-YouTube-Client-Name", "1")
                .header("X-YouTube-Client-Version", continuation.clientVersion())
                .header("Content-Type", "application/json");

        Response<String> response = downloader.downloadWebpage(request);
        if (!response.ok()) {
            YoutubeException e = new YoutubeException.DownloadException(String.format("Could not load url: %s, exception: %s", url, response.error().getMessage()));
            if (callback != null) {
                callback.onError(e);
            }
            throw e;
        }
        String html = response.data();

        JSONObject jsonResponse;
        JSONArray rootContents;
        try {
            jsonResponse = JSON.parseObject(html);
            if (jsonResponse.containsKey("onResponseReceivedCommands")) {
                rootContents = jsonResponse.getJSONArray("onResponseReceivedCommands")
                        .getJSONObject(0)
                        .getJSONObject("appendContinuationItemsAction")
                        .getJSONArray("continuationItems");
            } else {
                throw new YoutubeException.BadPageException("Could not find continuation data");
            }
        } catch (YoutubeException e) {
            throw e;
        } catch (Exception e) {
            throw new YoutubeException.BadPageException("Could not parse search continuation json");
        }

        long estimatedResults = extractor.extractLongFromText(jsonResponse.getString("estimatedResults"));
        SearchContinuation nextContinuation = getSearchContinuation(rootContents, continuation.clientVersion());
        return parseSearchResult(estimatedResults, rootContents, nextContinuation);
    }

    private SearchContinuation getSearchContinuation(JSONArray rootContents, String clientVersion) {
        if (rootContents.size() > 1) {
            if (rootContents.getJSONObject(1).containsKey("continuationItemRenderer")) {
                JSONObject endPoint = rootContents.getJSONObject(1)
                        .getJSONObject("continuationItemRenderer")
                        .getJSONObject("continuationEndpoint");
                String token = endPoint.getJSONObject("continuationCommand").getString("token");
                String ctp = endPoint.getString("clickTrackingParams");
                return new SearchContinuation(token, clientVersion, ctp);
            }
        }
        return null;
    }

    private SearchResult parseSearchResult(long estimatedResults, JSONArray rootContents, SearchContinuation continuation) throws BadPageException {
        JSONArray contents;

        try {
            contents = rootContents.getJSONObject(0)
                    .getJSONObject("itemSectionRenderer")
                    .getJSONArray("contents");
        } catch (NullPointerException e) {
            throw new YoutubeException.BadPageException("Search result contents not found");
        }

        List<SearchResultItem> items = new ArrayList<>(contents.size());
        Map<QueryElementType, QueryElement> queryElements = new HashMap<>();
        for (int i = 0; i < contents.size(); i++) {
            final SearchResultElement element = parseSearchResultElement(contents.getJSONObject(i));
            if (element != null) {
                if (element instanceof SearchResultItem) {
                    items.add((SearchResultItem) element);
                } else {
                    QueryElement queryElement = (QueryElement) element;
                    queryElements.put(queryElement.type(), queryElement);
                }
            }
        }
        if (continuation == null) {
            return new SearchResult(estimatedResults, items, queryElements);
        } else {
            return new ContinuatedSearchResult(estimatedResults, items, queryElements, continuation);
        }
    }

    private static SearchResultElement parseSearchResultElement(JSONObject jsonItem) {
        String rendererKey = jsonItem.keySet().iterator().next();
        JSONObject jsonRenderer = jsonItem.getJSONObject(rendererKey);
        switch (rendererKey) {
            case "videoRenderer":
                return new SearchResultVideoDetails(jsonRenderer, false);
            case "movieRenderer":
                return new SearchResultVideoDetails(jsonRenderer, true);
            case "playlistRenderer":
                return new SearchResultPlaylistDetails(jsonRenderer);
            case "channelRenderer":
                return new SearchResultChannelDetails(jsonRenderer);
            case "shelfRenderer":
                return new SearchResultShelf(jsonRenderer);
            case "showingResultsForRenderer":
                return new QueryAutoCorrection(jsonRenderer);
            case "didYouMeanRenderer":
                return new QuerySuggestion(jsonRenderer);
            case "horizontalCardListRenderer":
                return new QueryRefinementList(jsonRenderer);
            default:
                System.out.println("Unknown search result element type " + rendererKey);
                System.out.println(jsonItem);
                return null;
        }
    }
}
