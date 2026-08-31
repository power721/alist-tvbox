package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.tg.SearchRequest;
import cn.har01d.alist_tvbox.service.PanLinkCheckService;
import cn.har01d.alist_tvbox.service.RemoteSearchService;
import cn.har01d.alist_tvbox.service.SubscriptionService;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;

@RestController
public class RemoteSearchController {
    private final SubscriptionService subscriptionService;
    private final RemoteSearchService remoteSearchService;
    private final PanLinkCheckService panLinkCheckService;
    private final ObjectMapper objectMapper;

    public RemoteSearchController(SubscriptionService subscriptionService, RemoteSearchService remoteSearchService,
                                  PanLinkCheckService panLinkCheckService, ObjectMapper objectMapper) {
        this.subscriptionService = subscriptionService;
        this.remoteSearchService = remoteSearchService;
        this.panLinkCheckService = panLinkCheckService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/api/pansou")
    public ObjectNode getPanSouInfo() {
        return remoteSearchService.getPanSouInfo();
    }

    @PostMapping("/api/pansou/check/links")
    public ObjectNode checkPanSouLinks(@RequestBody ObjectNode request) {
        return panLinkCheckService.checkPanSouLinks(request);
    }

    // Plugin-facing, token-gated variant. Plugins (spider/filter) run inside the TVBox client
    // and only hold the subscription vod token — not the X-API-KEY that /api/pansou/check/links
    // requires. disk_type is optional and inferred from the URL when omitted.
    @PostMapping("/check-links")
    public ObjectNode checkLinks(@RequestBody ObjectNode request) {
        return checkLinks("", request);
    }

    @PostMapping("/check-links/{token}")
    public ObjectNode checkLinks(@PathVariable String token, @RequestBody ObjectNode request) {
        subscriptionService.checkToken(token);
        return panLinkCheckService.checkLinks(request);
    }

    @GetMapping("/pansou")
    public Object pansou(String id, String t, String wd, String title, @RequestParam(required = false, defaultValue = "1") int pg) {
        return pansou("", id, t, wd, title, pg);
    }

    @GetMapping("/pansou/{token}")
    public Object pansou(@PathVariable String token, String id, String t, String wd, String title, @RequestParam(required = false, defaultValue = "1") int pg) {
        subscriptionService.checkToken(token);
        if (StringUtils.isNotBlank(id)) {
            return remoteSearchService.detail(id, title, wd);
        } else if (StringUtils.isNotBlank(wd)) {
            return remoteSearchService.pansou(wd);
        } else if ("0".equals(t)) {
            return remoteSearchService.pansou("");
        }
        return null;
    }

    @GetMapping("/pansou-group")
    public Object pansouGroup(String id, String t, String wd, String title, @RequestParam(required = false, defaultValue = "1") int pg) {
        return pansouGroup("", id, t, wd, title, pg);
    }

    @GetMapping("/pansou-group/{token}")
    public Object pansouGroup(@PathVariable String token, String id, String t, String wd, String title, @RequestParam(required = false, defaultValue = "1") int pg) {
        subscriptionService.checkToken(token);
        if (StringUtils.isNotBlank(id)) {
            return remoteSearchService.detail(id, title, wd);
        } else if (StringUtils.isNotBlank(wd)) {
            return remoteSearchService.pansouGroup(wd);
        } else if (StringUtils.isNotBlank(t) && !"0".equals(t)) {
            return remoteSearchService.pansouGroupList(t, pg);
        }
        return null;
    }

    @GetMapping("/tgsp")
    public String searchPg(String keyword, String channelUsername, String encode, HttpServletResponse response) {
        response.setHeader("server", "hypercorn-h11");
        return remoteSearchService.searchPg(keyword, channelUsername, encode);
    }

    @PostMapping("/tgsp")
    public String searchPgPost(@RequestBody String body, HttpServletResponse response) throws JsonProcessingException {
        response.setHeader("server", "hypercorn-h11");
        String json = new String(Base64.getDecoder().decode(body));
        SearchRequest request = objectMapper.readValue(json, SearchRequest.class);
        if ("2".equals(request.getPage())) {
            return "";
        }
        return remoteSearchService.searchPg(request.getKeyword(), request.getChannelUsername(), request.getEncode());
    }

    @PostMapping(value = "/tgsp/s/{id}", produces = "text/plain;charset=UTF-8")
    public String searchPgChannel(@PathVariable String id, @RequestBody String body, HttpServletResponse response) throws JsonProcessingException {
        return searchPgPost(body, response);
    }
}
