package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Service
public class AListService {

    private static final Pattern VERSION = Pattern.compile("\"version\":\"v\\d+\\.\\d+\\.\\d+\"");
    private static final String ACCEPT = "application/json, text/plain, */*";
    private static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/107.0.0.0 Safari/537.36";

    private final RestTemplate restTemplate;
    private final Map<String, Integer> cache = new HashMap<>();
    private final Map<String, String> sites = new HashMap<>();

    public AListService(RestTemplateBuilder builder, AppProperties appProperties) {
        this.restTemplate = builder
                .defaultHeader("Accept", ACCEPT)
                .defaultHeader("User-Agent", USER_AGENT)
                .build();
        appProperties.getSites().forEach(site -> sites.put(site.getName(), site.getUrl()));
    }

    public List<String> search(String site, String keyword) {
        String url = getSiteUrl(site) + "/api/search?type=video&box=" + keyword;
        SearchResponse response = restTemplate.getForObject(url, SearchResponse.class);
        log.debug("search \"{}\" from site {} result: {} {}", keyword, site, response.getData().size(), response.getData());
        return response.getData();
    }

    public FsResponse listFiles(String site, String path, int page, int size) {
        int version = getVersion(site);
        String url = getSiteUrl(site) + (version == 2 ? "/api/public/path" : "/api/fs/list");
        FsRequest request = new FsRequest();
        request.setPath(path);
        request.setPage(page);
        request.setSize(size);
        log.debug("call api: {}", url);
        FsListResponse response = restTemplate.postForObject(url, request, FsListResponse.class);
        log.debug("list files: {} {}", path, response.getData());
        return getFiles(version, response.getData());
    }

    private FsResponse getFiles(int version, FsResponse response) {
        if (version == 2) {
            for (FsInfo fsInfo : response.getFiles()) {
                fsInfo.setThumb(fsInfo.getThumbnail());
            }
        } else {
            response.setFiles(response.getContent());
        }
        return response;
    }

    public String readFileContent(String site, String path) {
        String url = getSiteUrl(site) + "/p" + path;
        return restTemplate.getForObject(url, String.class);
    }

    public FsDetail getFile(String site, String path) {
        int version = getVersion(site);
        if (version == 2) {
            return getFileV2(site, path);
        } else {
            return getFileV3(site, path);
        }
    }

    private FsDetail getFileV3(String site, String path) {
        String url = getSiteUrl(site) + "/api/fs/get";
        FsRequest request = new FsRequest();
        request.setPath(path);
        log.debug("call api: {}", url);
        FsDetailResponse response = restTemplate.postForObject(url, request, FsDetailResponse.class);
        log.debug("get file: {} {}", path, response.getData());
        return response.getData();
    }

    private FsDetail getFileV2(String site, String path) {
        String url = getSiteUrl(site) + "/api/public/path";
        FsRequest request = new FsRequest();
        request.setPath(path);
        log.debug("call api: {}", url);
        FsListResponseV2 response = restTemplate.postForObject(url, request, FsListResponseV2.class);
        FsInfoV2 fsInfo = Optional.ofNullable(response)
                .map(Response::getData)
                .map(FsResponseV2::getFiles)
                .filter(l -> !l.isEmpty())
                .map(l -> l.get(0))
                .orElse(null);
        if (fsInfo != null) {
            FsDetail fsDetail = new FsDetail();
            fsDetail.setName(fsInfo.getName());
            fsDetail.setThumb(fsInfo.getThumbnail());
            fsDetail.setSize(fsInfo.getSize());
            fsDetail.setRaw_url(fsInfo.getUrl());
            fsDetail.setType(fsInfo.getType());
            fsDetail.setProvider(fsInfo.getDriver());
            log.debug("get file: {} {}", path, fsDetail);
            return fsDetail;
        }
        return null;
    }

    private Integer getVersion(String site) {
        if (cache.containsKey(site)) {
            return cache.get(site);
        }

        String url = getSiteUrl(site) + "/api/public/settings";
        log.debug("call api: {}", url);
        String text = restTemplate.getForObject(url, String.class);
        int version;
        if (text != null && VERSION.matcher(text).find()) {
            version = 3;
        } else {
            version = 2;
        }
        log.info("site: {} version: {}", site, version);
        cache.put(site, version);

        return version;
    }

    private String getSiteUrl(String site) {
        return sites.get(site);
    }
}
