package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.ParseRequest;
import cn.har01d.alist_tvbox.service.OfflineDownloadService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.service.TvBoxService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublicOfflineDownloadController {
    private final OfflineDownloadService offlineDownloadService;
    private final SubscriptionService subscriptionService;
    private final TvBoxService tvBoxService;

    public PublicOfflineDownloadController(OfflineDownloadService offlineDownloadService,
                                           SubscriptionService subscriptionService,
                                           TvBoxService tvBoxService) {
        this.offlineDownloadService = offlineDownloadService;
        this.subscriptionService = subscriptionService;
        this.tvBoxService = tvBoxService;
    }

    @PostMapping("/offline_download")
    public Object download(@RequestBody ParseRequest request,
                           @RequestParam(required = false, defaultValue = "") String ac) {
        return download("", request, ac);
    }

    @PostMapping("/offline_download/{token}")
    public Object download(@PathVariable String token,
                           @RequestBody ParseRequest request,
                           @RequestParam(required = false, defaultValue = "") String ac) {
        subscriptionService.checkToken(token);

        OfflineDownloadService.DownloadTarget target = offlineDownloadService.downloadTarget(request);
        String targetPath = target.path();
        if (target.folder()) {
            targetPath += "/~playlist";
        }
        return tvBoxService.getDetail(ac, "1$" + targetPath);
    }
}
