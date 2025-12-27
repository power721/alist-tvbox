package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.tg.SearchRequest;
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
    private final ObjectMapper objectMapper;

    public RemoteSearchController(SubscriptionService subscriptionService, RemoteSearchService remoteSearchService, ObjectMapper objectMapper) {
        this.subscriptionService = subscriptionService;
        this.remoteSearchService = remoteSearchService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/api/pansou")
    public ObjectNode getPanSouInfo() {
        return remoteSearchService.getPanSouInfo();
    }

    @GetMapping("/pansou")
    public Object pansou(String id, String t, String wd, @RequestParam(required = false, defaultValue = "1") int pg) {
        return pansou("", id, t, wd, pg);
    }

    @GetMapping("/pansou/{token}")
    public Object pansou(@PathVariable String token, String id, String t, String wd, @RequestParam(required = false, defaultValue = "1") int pg) {
        subscriptionService.checkToken(token);
        if (StringUtils.isNotBlank(id)) {
            return remoteSearchService.detail(id);
        } else if (StringUtils.isNotBlank(wd)) {
            return remoteSearchService.pansou(wd);
        } else if ("0".equals(t)) {
            return remoteSearchService.pansou("");
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
