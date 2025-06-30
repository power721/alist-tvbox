package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.service.AListLocalService;
import cn.har01d.alist_tvbox.service.ShareService;
import cn.har01d.alist_tvbox.service.SiteService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/alist")

public class AListController {
    private final AListLocalService service;
    private final SiteService siteService;
    private final ShareService shareService;
    private final AListLocalService aListLocalService;

    public AListController(AListLocalService service,
                           SiteService siteService,
                           ShareService shareService,
                           AListLocalService aListLocalService) {
        this.service = service;
        this.siteService = siteService;
        this.shareService = shareService;
        this.aListLocalService = aListLocalService;
    }

    @GetMapping("/status")
    public int checkStatus() {
        return service.checkStatus();
    }

    @GetMapping("/start/status")
    public int getStatus() {
        return service.getStatus();
    }

    @PostMapping("/status")
    public void updateStatus(int code) {
        service.updateStatus(code);
        if (code == 3) {
            shareService.cleanInvalidShares();
        }
    }

    @PostMapping("/stop")
    public void stopAListServer() {
        service.stopAListServer();
    }

    @PostMapping("/start")
    public void startAListServer() {
        service.startAListServer();
    }

    @PostMapping("/restart")
    public void restartAListServer() {
        service.restartAListServer();
    }

    @GetMapping("/port")
    public int getPort() {
        return aListLocalService.getExternalPort();
    }

    @PostMapping("/reset_token")
    public void resetToken() {
        siteService.resetToken();
    }
}
