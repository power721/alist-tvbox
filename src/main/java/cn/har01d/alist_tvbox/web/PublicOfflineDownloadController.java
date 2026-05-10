package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.OfflineDownloadRequest;
import cn.har01d.alist_tvbox.service.OfflineDownloadService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublicOfflineDownloadController {
    private final OfflineDownloadService offlineDownloadService;
    private final SubscriptionService subscriptionService;

    public PublicOfflineDownloadController(OfflineDownloadService offlineDownloadService,
                                           SubscriptionService subscriptionService) {
        this.offlineDownloadService = offlineDownloadService;
        this.subscriptionService = subscriptionService;
    }

    @PostMapping("/offline_download")
    public Object download(@RequestBody OfflineDownloadRequest request,
                           @RequestParam(required = false, defaultValue = "") String ac) {
        return offlineDownloadService.download(request, ac);
    }

    @PostMapping("/offline_download/{token}")
    public Object download(@PathVariable String token,
                           @RequestBody OfflineDownloadRequest request,
                           @RequestParam(required = false, defaultValue = "") String ac) {
        subscriptionService.checkToken(token);
        return offlineDownloadService.download(request, ac);
    }
}
