package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.ParseRequest;
import cn.har01d.alist_tvbox.dto.ShareLink;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.model.DownloadTarget;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.youtube.YtdlpParseService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
public class ParseService {
    private final TvBoxService tvBoxService;
    private final OfflineDownloadService offlineDownloadService;
    private final ShareService shareService;
    private final YtdlpParseService ytdlpParseService;

    public ParseService(TvBoxService tvBoxService, OfflineDownloadService offlineDownloadService, ShareService shareService,
                        YtdlpParseService ytdlpParseService) {
        this.tvBoxService = tvBoxService;
        this.offlineDownloadService = offlineDownloadService;
        this.shareService = shareService;
        this.ytdlpParseService = ytdlpParseService;
    }

    public Object parse(ParseRequest request, String ac) {
        return parse("", request, ac);
    }

    public Object parse(String token, ParseRequest request, String ac) {
        if (StringUtils.isBlank(request.url())) {
            throw new BadRequestException("url is required");
        }
        if (request.url().startsWith("http")) {
            try {
                return drive(request.url(), request.title(), request.keyword(), ac);
            } catch (Exception e) {
                // 非网盘链接:yt-dlp 通用解析(TikTok/X/Twitch 等 1800+ 站点);
                // 失败抛回原错,调用方(jar Push/T4 pushDetail)自行回落直链三线路
                Map<String, Object> ytdlp = ytdlpParseService.detail(request.url(), token);
                if (ytdlp != null) {
                    return ytdlp;
                }
                throw e;
            }
        }
        DownloadTarget target = offlineDownloadService.downloadTarget(request);
        String targetPath = target.path();
        if (target.folder()) {
            targetPath += "/~playlist";
        }
        return tvBoxService.getDetail(ac, "1$" + targetPath, request.title(), request.keyword(), 0);
    }

    public MovieList drive(String tid, String ac) {
        return drive(tid, null, ac);
    }

    public MovieList drive(String tid, String title, String ac) {
        return drive(tid, title, null, ac);
    }

    public MovieList drive(String tid, String title, String keyword, String ac) {
        ShareLink share = new ShareLink();
        share.setLink(tid);
        String path = shareService.add(share);

        // TVBox history re-entry reaches /parse with only the share link (title=null);
        // recover/persist the real title via Share.title so getPlaylist does not fall back
        // to the storage folder name.
        String resolved = shareService.resolveShareTitle(tid, title);
        return tvBoxService.getDetail(ac, "1$" + path + "/~playlist", resolved, keyword, 0);
    }
}
