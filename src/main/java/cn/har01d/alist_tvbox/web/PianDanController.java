package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.service.PianDanService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class PianDanController {
    private final PianDanService pianDanService;
    private final SubscriptionService subscriptionService;

    @GetMapping("/pian-dan")
    public Object browse(String t,
                         String ac,
                         String wd,
                         @RequestParam(required = false, defaultValue = "1") int pg,
                         @RequestParam(required = false, defaultValue = "20") int size,
                         @RequestParam Map<String, String> filters) {
        return browse("", t, ac, wd, pg, size, filters);
    }

    @GetMapping("/pian-dan/{token}")
    public Object browse(@PathVariable String token,
                         String t,
                         String ac,
                         String wd,
                         @RequestParam(required = false, defaultValue = "1") int pg,
                         @RequestParam(required = false, defaultValue = "20") int size,
                         @RequestParam Map<String, String> filters) {
        subscriptionService.checkToken(token);
        if (StringUtils.isNotBlank(wd)) {
            return pianDanService.search(wd, pg, size);
        }
        if (StringUtils.isBlank(t)) {
            return pianDanService.category();
        }
        if ("0".equals(t)) {
            return pianDanService.home();
        }
        return pianDanService.list(t, ac, pg, size, filters);
    }
}
