package cn.har01d.alist_tvbox.service;

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

    public AListService(RestTemplateBuilder builder) {
        this.restTemplate = builder.build();
    }

    public List<FsInfo> listFiles(String site, String path) {
        String url = site + "/api/fs/list";
        FsRequest request = new FsRequest();
        request.setPath(path);
        FsListResponse response = restTemplate.postForObject(url, request, FsListResponse.class);
        log.debug("list files: {} {}", path, response.getData());
        return response.getData().getContent();
    }

    public FsDetail getFile(String site, String path) {
        String url = site + "/api/fs/get";
        FsRequest request = new FsRequest();
        request.setPath(path);
        FsDetailResponse response = restTemplate.postForObject(url, request, FsDetailResponse.class);
        log.debug("get file: {} {}", path, response.getData());
        return response.getData();
    }
}
