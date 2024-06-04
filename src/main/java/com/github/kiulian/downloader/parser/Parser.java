package com.github.kiulian.downloader.parser;

import com.github.kiulian.downloader.downloader.request.RequestChannelUploads;
import com.github.kiulian.downloader.downloader.request.RequestPlaylistInfo;
import com.github.kiulian.downloader.downloader.request.RequestSearchContinuation;
import com.github.kiulian.downloader.downloader.request.RequestSearchResult;
import com.github.kiulian.downloader.downloader.request.RequestSearchable;
import com.github.kiulian.downloader.downloader.request.RequestSubtitlesInfo;
import com.github.kiulian.downloader.downloader.request.RequestVideoInfo;
import com.github.kiulian.downloader.downloader.response.Response;
import com.github.kiulian.downloader.model.BrowseRequest;
import com.github.kiulian.downloader.model.playlist.PlaylistInfo;
import com.github.kiulian.downloader.model.search.SearchResult;
import com.github.kiulian.downloader.model.subtitles.SubtitlesInfo;
import com.github.kiulian.downloader.model.videos.VideoInfo;

import java.util.List;

public interface Parser {

    /* Video */

    Response<VideoInfo> parseVideo(RequestVideoInfo request);

    /* Playlist */

    Response<PlaylistInfo> parsePlaylist(RequestPlaylistInfo request);

    /* Channel uploads */

    Response<PlaylistInfo> parseChannelsUploads(RequestChannelUploads request);

    /* Subtitles */

    Response<List<SubtitlesInfo>> parseSubtitlesInfo(RequestSubtitlesInfo request);

    /* Search */

    Response<SearchResult> parseSearchResult(RequestSearchResult request);

    Response<SearchResult> parseSearchContinuation(RequestSearchContinuation request);

    Response<SearchResult> parseSearcheable(RequestSearchable request);

    void browse(BrowseRequest browseRequest);
}
