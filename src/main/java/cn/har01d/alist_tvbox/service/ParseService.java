package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.ParseRequest;
import cn.har01d.alist_tvbox.dto.ShareLink;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ParseService {
    private final TvBoxService tvBoxService;
    private final OfflineDownloadService offlineDownloadService;
    private final ShareService shareService;

    public ParseService(TvBoxService tvBoxService, OfflineDownloadService offlineDownloadService, ShareService shareService) {
        this.tvBoxService = tvBoxService;
        this.offlineDownloadService = offlineDownloadService;
        this.shareService = shareService;
    }

    public Object parse(ParseRequest request, String ac) {
        if (StringUtils.isBlank(request.url())) {
            throw new BadRequestException("url is required");
        }
        if (request.url().startsWith("http")) {
            return drive(request.url(), ac);
        }
        OfflineDownloadService.DownloadTarget target = offlineDownloadService.downloadTarget(request);
        String targetPath = target.path();
        if (target.folder()) {
            targetPath += "/~playlist";
        }
        return tvBoxService.getDetail(ac, "1$" + targetPath);
    }

    public MovieList drive(String tid, String ac) {
        ShareLink share = new ShareLink();
        share.setLink(tid);
        String path = shareService.add(share);

        return tvBoxService.getDetail(ac, "1$" + path + "/~playlist");
    }
}
