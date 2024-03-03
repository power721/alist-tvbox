package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.service.AListLocalService;
import cn.har01d.alist_tvbox.service.SiteService;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/alist")

public class AListController {
    private final AListLocalService service;
    private final SiteService siteService;
    private final AppProperties appProperties;
    private final Environment environment;

    public AListController(AListLocalService service, SiteService siteService, AppProperties appProperties, Environment environment) {
        this.service = service;
        this.siteService = siteService;
        this.appProperties = appProperties;
        this.environment = environment;
    }

    @GetMapping("/status")
    public int getAListStatus() {
        return service.getAListStatus();
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
    public String getPort() {
        return environment.getProperty("ALIST_PORT", appProperties.isHostmode() ? "5678" : "5344");
    }

    @PostMapping("/reset_token")
    public void resetToken() {
        siteService.resetToken();
    }
}
