package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.service.AListLocalService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/alist")
@Profile("xiaoya")
public class AListController {
    private final AListLocalService service;

    public AListController(AListLocalService service) {
        this.service = service;
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
}
