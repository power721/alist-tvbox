package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.model.*;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Slf4j
@Service
public class AListService {

    private static final Pattern VERSION = Pattern.compile("\"version\":\"v\\d+.\\d+.\\d+\"");

    private final RestTemplate restTemplate;
    private final Cache<String, Integer> cache;

    public AListService(RestTemplateBuilder builder, AppProperties appProperties) {
        this.restTemplate = builder.build();
        this.cache = Caffeine.newBuilder().maximumSize(appProperties.getCache().getSize()).build();
    }

    public List<FsInfo> listFiles(String site, String path) {
        int version = getVersion(site);
        String url = site + (version == 2 ? "/api/public/path" : "/api/fs/list");
        FsRequest request = new FsRequest();
        request.setPath(path);
        FsListResponse response = restTemplate.postForObject(url, request, FsListResponse.class);
        log.debug("list files: {} {}", path, response.getData());
        return version == 2 ? getFiles(response.getData()) : response.getData().getContent();
    }

    private List<FsInfo> getFiles(FsResponse response) {
        for (FsInfo fsInfo : response.getFiles()) {
            fsInfo.setThumb(fsInfo.getThumbnail());
        }
        return response.getFiles();
    }

    public FsDetail getFile(String site, String path) {
        int version = getVersion(site);
        if (version == 3) {
            return getFileV3(site, path);
        } else {
            return getFileV2(site, path);
        }
    }

    private FsDetail getFileV3(String site, String path) {
        String url = site + "/api/fs/get";
        FsRequest request = new FsRequest();
        request.setPath(path);
        FsDetailResponse response = restTemplate.postForObject(url, request, FsDetailResponse.class);
        log.debug("get file: {} {}", path, response.getData());
        return response.getData();
    }

    private FsDetail getFileV2(String site, String path) {
        String url = site + "/api/public/path";
        FsRequest request = new FsRequest();
        request.setPath(path);
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
        Integer version = cache.getIfPresent(site);
        if (version != null) {
            return version;
        }

        String text = restTemplate.getForObject(site + "/api/public/settings", String.class);
        if (text != null && VERSION.matcher(text).find()) {
            version = 3;
        } else {
            version = 2;
        }
        log.info("site: {} version: {}", site, version);
        cache.put(site, version);

        return version;
    }
}
