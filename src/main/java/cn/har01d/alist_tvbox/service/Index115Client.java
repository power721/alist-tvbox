package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.Index115File;
import cn.har01d.alist_tvbox.dto.Index115LinkData;
import cn.har01d.alist_tvbox.dto.Index115Response;
import cn.har01d.alist_tvbox.dto.Index115SearchData;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.util.Constants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class Index115Client {
    private final RestTemplate restTemplate;

    public Index115Client(RestTemplateBuilder builder) {
        this.restTemplate = builder.build();
    }

    public List<Index115File> browse(Site site, String shareCode, String receiveCode, String parentId) {
        String url = site.getUrl() + "/api/index115/browse?share_code={sc}&receive_code={rc}&parent_id={pid}";
        Map<String, String> vars = new HashMap<>();
        vars.put("sc", shareCode == null ? "" : shareCode);
        vars.put("rc", receiveCode == null ? "" : receiveCode);
        vars.put("pid", parentId == null ? "" : parentId);
        return get(site, url, vars, new ParameterizedTypeReference<>() {});
    }

    public Index115SearchData search(Site site, String query, int page, int perPage) {
        String url = site.getUrl() + "/api/index115/search?q={q}&page={page}&per_page={pp}";
        Map<String, String> vars = new HashMap<>();
        vars.put("q", query);
        vars.put("page", String.valueOf(page));
        vars.put("pp", String.valueOf(perPage));
        return get(site, url, vars, new ParameterizedTypeReference<>() {});
    }

    public Index115File getFile(Site site, String id) {
        String url = site.getUrl() + "/api/index115/detail?id={id}";
        Map<String, String> vars = new HashMap<>();
        vars.put("id", id == null ? "" : id);
        return get(site, url, vars, new ParameterizedTypeReference<>() {});
    }

    public String resolveLink(Site site, String cookie, String shareCode, String receiveCode, String fileId) {
        String url = site.getUrl() + "/api/index115/link";
        HttpHeaders h = headers(site);
        h.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("cookie", cookie);
        body.put("share_code", shareCode);
        body.put("receive_code", receiveCode);
        body.put("file_id", fileId);
        ResponseEntity<Index115Response<Index115LinkData>> resp = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(body, h), new ParameterizedTypeReference<>() {});
        return unwrap(resp).getUrl();
    }

    private <T> T get(Site site, String url, Map<String, String> vars, ParameterizedTypeReference<Index115Response<T>> type) {
        ResponseEntity<Index115Response<T>> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(null, headers(site)), type, vars);
        return unwrap(resp);
    }

    private HttpHeaders headers(Site site) {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.ACCEPT, Constants.ACCEPT);
        h.set(HttpHeaders.USER_AGENT, Constants.USER_AGENT);
        if (site.getToken() != null && !site.getToken().isBlank()) {
            h.set(HttpHeaders.AUTHORIZATION, site.getToken());
        }
        return h;
    }

    private <T> T unwrap(ResponseEntity<Index115Response<T>> resp) {
        Index115Response<T> r = resp.getBody();
        if (r == null || r.getCode() >= 400) {
            throw new BadRequestException(r == null ? "empty PowerList response" : r.getMessage());
        }
        return r.getData();
    }
}
