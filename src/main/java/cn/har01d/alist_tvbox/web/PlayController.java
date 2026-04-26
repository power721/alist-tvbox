package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.PlayUrl;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.BiliBiliService;
import cn.har01d.alist_tvbox.service.ProxyService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.service.TvBoxService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping
public class PlayController {
    private final TvBoxService tvBoxService;
    private final BiliBiliService biliBiliService;
    private final SubscriptionService subscriptionService;
    private final ProxyService proxyService;

    public PlayController(TvBoxService tvBoxService,
                          BiliBiliService biliBiliService,
                          SubscriptionService subscriptionService,
                          ProxyService proxyService) {
        this.tvBoxService = tvBoxService;
        this.biliBiliService = biliBiliService;
        this.subscriptionService = subscriptionService;
        this.proxyService = proxyService;
    }

    @RequestMapping(value = "/p/{token}/{id}")
    public void proxy(@PathVariable String token, @PathVariable String id, HttpServletRequest request, HttpServletResponse response) throws IOException {
        subscriptionService.checkToken(token);

        proxyService.proxy(id, request, response);
    }

    @GetMapping("/play-urls")
    public Page <PlayUrl> list(Pageable pageable) {
        return proxyService.list(pageable);
    }

    @DeleteMapping("/play-urls")
    public void delete() {
        proxyService.deleteAll();
    }

    @GetMapping("/play")
    public Object play(Integer site, String path, String id, String bvid, String type, boolean dash, HttpServletRequest request) throws IOException {
        return play("", site, path, id, bvid, type, dash, request);
    }

    @GetMapping("/play/{token}")
    public Object play(@PathVariable String token, Integer site, String path, String id, String bvid, String type, boolean dash, HttpServletRequest request) throws IOException {
        subscriptionService.checkToken(token);

        String client = request.getHeader("X-CLIENT");
        // com.mygithub0.tvbox0.osdX 影视仓
        // com.fongmi.android.tv    影视
        // com.github.tvbox.osc     q版
        // com.github.tvbox.osc.bh  宝盒
        // com.github.tvbox.osc.tk  takagen99
        // com.qingsong.yingmi      影迷
        log.debug("get play url - site: {}  path: {}  id: {}  bvid: {}  type: ", site, path, id, bvid, type);

        if (StringUtils.isNotBlank(bvid)) {
            return biliBiliService.getPlayUrl(bvid, dash, client);
        }

        if (StringUtils.isNotBlank(id)) {
            String[] parts = id.split("@");
            if (parts.length > 1) {
                site = parseInt(parts[0], "站点参数格式不正确");
                path = parts[1];
                try {
                    path = proxyService.getPath(parseInt(path, "播放参数格式不正确"));
                } catch (NumberFormatException e) {
                    log.debug("", e);
                } catch (Exception e) {
                    log.warn("", e);
                }
            } else {
                path = id;
            }
        }

        if (StringUtils.isBlank(path)) {
            throw new BadRequestException("缺少播放参数");
        }

        boolean getSub = true;
        Map<String, Object> result;
        try {
            if (path.contains("/")) {
                if (path.startsWith("/")) {
                    result = tvBoxService.getPlayUrl(site, path, getSub, client, type);
                } else {
                    int index = path.indexOf('/');
                    id = path.substring(0, index);
                    path = path.substring(index);
                    result = tvBoxService.getPlayUrl(site, parseInt(id, "播放参数格式不正确"), path, getSub, client, type);
                }
            } else if (path.contains("-")) {
                String[] parts = path.split("-", 2);
                if (parts.length != 2) {
                    throw new BadRequestException("播放参数格式不正确");
                }
                id = parts[0];
                int index = parseInt(parts[1], "播放参数格式不正确");
                result = tvBoxService.getPlayUrl(site, parseInt(id, "播放参数格式不正确"), index, getSub, client, type);
            } else {
                result = tvBoxService.getPlayUrl(site, parseInt(path, "播放参数格式不正确"), getSub, client, type);
            }
        } catch (NumberFormatException e) {
            throw new BadRequestException("播放参数格式不正确", e);
        }

//        String url = (String) result.get("url");
//        if (url.contains("/redirect")) {
//            result.put("url", parseService.parse(url));
//        }

        return result;
    }

    private int parseInt(String value, String message) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new BadRequestException(message, e);
        }
    }
}
