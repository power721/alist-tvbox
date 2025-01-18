package cn.har01d.alist_tvbox.live.web;

import cn.har01d.alist_tvbox.live.service.HuyaService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
public class HuyaController {
    private final HuyaService huyaService;
    private final SubscriptionService subscriptionService;

    public HuyaController(HuyaService huyaService, SubscriptionService subscriptionService) {
        this.huyaService = huyaService;
        this.subscriptionService = subscriptionService;
    }

    @GetMapping("/huya")
    public Object browse(String ids, String wd, String t, String sort, @RequestParam(required = false, defaultValue = "1") Integer pg) throws IOException {
        return browse("", ids, wd, t, sort, pg);
    }

    @GetMapping("/huya/{token}")
    public Object browse(@PathVariable String token, String ids, String wd, String t, String sort, @RequestParam(required = false, defaultValue = "1") Integer pg) throws IOException {
        subscriptionService.checkToken(token);
        if (ids != null && !ids.isEmpty()) {
            if (ids.equals("recommend")) {
                return huyaService.home();
            }
            return huyaService.detail(ids);
        } else if (wd != null && !wd.isEmpty()) {
            return huyaService.search(wd);
        } else if (t != null && !t.isEmpty()) {
            if (t.equals("0")) {
                return huyaService.home();
            }
            return huyaService.list(t, sort, pg);
        }
        return huyaService.category();
    }
}
