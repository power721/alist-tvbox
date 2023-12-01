package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.BiliBiliService;
import cn.har01d.alist_tvbox.service.ParseService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.service.TvBoxService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping
public class PlayController {
    private final TvBoxService tvBoxService;
    private final BiliBiliService biliBiliService;
    private final ParseService parseService;
    private final SubscriptionService subscriptionService;

    public PlayController(TvBoxService tvBoxService, BiliBiliService biliBiliService, ParseService parseService, SubscriptionService subscriptionService) {
        this.tvBoxService = tvBoxService;
        this.biliBiliService = biliBiliService;
        this.parseService = parseService;
        this.subscriptionService = subscriptionService;
    }

    @GetMapping("/play")
    public Object play(Integer site, String path, String id, String bvid, String type, boolean dash, HttpServletRequest request) throws IOException {
        return play("", site, path, id, bvid, type, dash, request);
    }

    @GetMapping("/play/{token}")
    public Object play(@PathVariable String token, Integer site, String path, String id, String bvid, String type, boolean dash, HttpServletRequest request) throws IOException {
        if (!subscriptionService.getToken().equals(token)) {
            throw new BadRequestException();
        }

        String client = request.getHeader("X-CLIENT");
        log.debug("{} {} {} {}", request.getMethod(), request.getRequestURI(), decodeUrl(request.getQueryString()), client);
        log.debug("get play url - site: {}  path: {}  id: {}  bvid: {}  type: ", site, path, id, bvid, type);

        if (StringUtils.isNotBlank(bvid)) {
            return biliBiliService.getPlayUrl(bvid, dash);
        }

        if (StringUtils.isNotBlank(id)) {
            String[] parts = id.split("\\~\\~\\~");
            site = Integer.parseInt(parts[0]);
            path = parts[1];
        }

        boolean sp = "com.fongmi.android.tv".equals(client);
        boolean getSub = true;
        Map<String, Object> result;
        if (path.contains("/")) {
            if (path.startsWith("/")) {
                result = tvBoxService.getPlayUrl(site, path, getSub, sp);
            } else {
                int index = path.indexOf('/');
                id = path.substring(0, index);
                path = path.substring(index);
                result = tvBoxService.getPlayUrl(site, Integer.parseInt(id), path, getSub, sp);
            }
        } else {
            result = tvBoxService.getPlayUrl(site, Integer.parseInt(path), getSub, sp);
        }

//        String url = (String) result.get("url");
//        if (url.contains("/redirect")) {
//            result.put("url", parseService.parse(url));
//        }

        return result;
    }

    private String decodeUrl(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        try {
            return URLDecoder.decode(text, "UTF-8");
        } catch (Exception e) {
            return text;
        }
    }
}
