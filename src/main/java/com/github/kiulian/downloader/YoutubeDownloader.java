package com.github.kiulian.downloader;


import com.github.kiulian.downloader.cipher.CachedCipherFactory;
import com.github.kiulian.downloader.downloader.Downloader;
import com.github.kiulian.downloader.downloader.DownloaderImpl;
import com.github.kiulian.downloader.downloader.request.RequestChannelUploads;
import com.github.kiulian.downloader.downloader.request.RequestPlaylistInfo;
import com.github.kiulian.downloader.downloader.request.RequestSearchContinuation;
import com.github.kiulian.downloader.downloader.request.RequestSearchResult;
import com.github.kiulian.downloader.downloader.request.RequestSearchable;
import com.github.kiulian.downloader.downloader.request.RequestSubtitlesInfo;
import com.github.kiulian.downloader.downloader.request.RequestVideoFileDownload;
import com.github.kiulian.downloader.downloader.request.RequestVideoInfo;
import com.github.kiulian.downloader.downloader.request.RequestVideoStreamDownload;
import com.github.kiulian.downloader.downloader.request.RequestWebpage;
import com.github.kiulian.downloader.downloader.response.Response;
import com.github.kiulian.downloader.downloader.response.ResponseImpl;
import com.github.kiulian.downloader.extractor.ExtractorImpl;
import com.github.kiulian.downloader.model.BrowseRequest;
import com.github.kiulian.downloader.model.playlist.PlaylistInfo;
import com.github.kiulian.downloader.model.search.SearchResult;
import com.github.kiulian.downloader.model.subtitles.SubtitlesInfo;
import com.github.kiulian.downloader.model.videos.VideoInfo;
import com.github.kiulian.downloader.parser.Parser;
import com.github.kiulian.downloader.parser.ParserImpl;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static com.github.kiulian.downloader.model.Utils.createOutDir;

public class YoutubeDownloader {

    private final Config config;
    private final Downloader downloader;
    private final Parser parser;

    public YoutubeDownloader() {
        this(Config.buildDefault());
    }

    public YoutubeDownloader(Config config) {
        this.config = config;
        this.downloader = new DownloaderImpl(config);
        this.parser = new ParserImpl(config, downloader, new ExtractorImpl(downloader), new CachedCipherFactory(downloader));
    }

    public YoutubeDownloader(Config config, Downloader downloader) {
        this(config, downloader, new ParserImpl(config, downloader, new ExtractorImpl(downloader), new CachedCipherFactory(downloader)));
    }

    public YoutubeDownloader(Config config, Downloader downloader, Parser parser) {
        this.config = config;
        this.parser = parser;
        this.downloader = downloader;
    }

    public Config getConfig() {
        return config;
    }

    public Response<VideoInfo> getVideoInfo(RequestVideoInfo request) {
        return parser.parseVideo(request);
    }

    public Response<List<SubtitlesInfo>> getSubtitlesInfo(RequestSubtitlesInfo request) {
        return parser.parseSubtitlesInfo(request);
    }

    public Response<PlaylistInfo> getChannelUploads(RequestChannelUploads request) {
        return parser.parseChannelsUploads(request);
    }

    public Response<PlaylistInfo> getPlaylistInfo(RequestPlaylistInfo request) {
        return parser.parsePlaylist(request);
    }

    public Response<SearchResult> search(RequestSearchResult request) {
        return parser.parseSearchResult(request);
    }

    public Response<SearchResult> searchContinuation(RequestSearchContinuation request) {
        return parser.parseSearchContinuation(request);
    }

    public Response<SearchResult> search(RequestSearchable request) {
        return parser.parseSearcheable(request);
    }

    public void browse(BrowseRequest request) {
        parser.browse(request);
    }

    public Response<File> downloadVideoFile(RequestVideoFileDownload request) {
        File outDir = request.getOutputDirectory();
        try {
            createOutDir(outDir);
        } catch (IOException e) {
            return ResponseImpl.error(e);
        }

        return downloader.downloadVideoAsFile(request);
    }

    public Response<Void> downloadVideoStream(RequestVideoStreamDownload request) {
        return downloader.downloadVideoAsStream(request);
    }

    public Response<String> downloadSubtitle(RequestWebpage request) {
        return downloader.downloadWebpage(request);
    }

}
