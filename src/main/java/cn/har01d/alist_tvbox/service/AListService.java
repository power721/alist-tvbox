package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Slf4j
@Service
public class AListService {
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;

    public AListService(RestTemplateBuilder builder, AppProperties appProperties) {
        this.restTemplate = builder.build();
        this.appProperties = appProperties;
    }

    public List<FsInfo> listFiles(String path) {
        String url = appProperties.getUrl() + "/api/fs/list";
        FsListRequest request = new FsListRequest();
        request.setPath(path);
        FsListResponse response = restTemplate.postForObject(url, request, FsListResponse.class);
        log.debug("list files: {} {}", path, response.getData());
        return response.getData().getContent();
    }

    public FsDetail getFile(String path) {
        String url = appProperties.getUrl() + "/api/fs/get";
        FsListRequest request = new FsListRequest();
        request.setPath(path);
        FsDetailResponse response = restTemplate.postForObject(url, request, FsDetailResponse.class);
        log.debug("get file: {} {}", path, response.getData());
        return response.getData();
    }
}
