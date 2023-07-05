package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.service.AListLocalService;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/alist")
@Profile("xiaoya")
public class AListController {
    private final AListLocalService service;
    private final Environment environment;

    public AListController(AListLocalService service, Environment environment) {
        this.service = service;
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
        return environment.getProperty("ALIST_PORT");
    }
}
