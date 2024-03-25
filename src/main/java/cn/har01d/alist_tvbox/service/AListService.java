package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.FileItem;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.model.FsDetail;
import cn.har01d.alist_tvbox.model.FsDetailResponse;
import cn.har01d.alist_tvbox.model.FsInfo;
import cn.har01d.alist_tvbox.model.FsInfoV2;
import cn.har01d.alist_tvbox.model.FsListResponse;
import cn.har01d.alist_tvbox.model.FsListResponseV2;
import cn.har01d.alist_tvbox.model.FsRequest;
import cn.har01d.alist_tvbox.model.FsResponse;
import cn.har01d.alist_tvbox.model.FsResponseV2;
import cn.har01d.alist_tvbox.model.Response;
import cn.har01d.alist_tvbox.model.SearchListResponse;
import cn.har01d.alist_tvbox.model.SearchRequest;
import cn.har01d.alist_tvbox.model.SearchResult;
import cn.har01d.alist_tvbox.model.ShareInfo;
import cn.har01d.alist_tvbox.model.ShareInfoResponse;
import cn.har01d.alist_tvbox.model.VideoPreview;
import cn.har01d.alist_tvbox.model.VideoPreviewResponse;
import cn.har01d.alist_tvbox.util.Constants;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AListService {
    private static final Pattern VERSION = Pattern.compile("\"version\":\"v\\d+\\.\\d+\\.\\d+\"");

    private final RestTemplate restTemplate;
    private final SiteService siteService;
    private final AppProperties appProperties;
    private final Cache<String, VideoPreview> cache = Caffeine.newBuilder()
            .maximumSize(10)
            .expireAfterWrite(Duration.ofSeconds(895))
            .build();

    public AListService(RestTemplateBuilder builder, SiteService siteService, AppProperties appProperties) {
        this.restTemplate = builder
                .defaultHeader(HttpHeaders.ACCEPT, Constants.ACCEPT)
                .defaultHeader(HttpHeaders.USER_AGENT, Constants.USER_AGENT)
                .setConnectTimeout(Duration.ofSeconds(60))
                .setReadTimeout(Duration.ofSeconds(60))
                .build();
        this.siteService = siteService;
        this.appProperties = appProperties;
    }

    public List<SearchResult> search(Site site, String keyword) {
        String url = site.getUrl() + "/api/fs/search?keyword=" + keyword;
        SearchRequest request = new SearchRequest();
        request.setPassword(site.getPassword());
        request.setKeywords(keyword);
        SearchListResponse response = post(site, url, request, SearchListResponse.class);
        logError(response);
        log.debug("search \"{}\" from site {}:{} result: {}", keyword, site.getId(), site.getName(), response.getData().getContent().size());
        return response.getData().getContent();
    }

    public List<FileItem> browse(int id, String path) {
        List<FileItem> list = new ArrayList<>();
        if (StringUtils.isEmpty(path)) {
            list.add(new FileItem("/", "/", 1));
            return list;
        }

        Site site = siteService.getById(id);
        FsResponse response = listFiles(site, path, 1, 1000);
        for (FsInfo fsInfo : response.getFiles()) {
            FileItem item = new FileItem(fsInfo.getName(), fixPath(path + "/" + fsInfo.getName()), fsInfo.getType());
            list.add(item);
        }
        return list;
    }

    private String fixPath(String path) {
        return path.replaceAll("/+", "/");
    }

    public FsResponse listFiles(Site site, String path, int page, int size) {
        int version = getVersion(site);
        String url = getUrl(site) + (version == 2 ? "/api/public/path" : "/api/fs/list");
        FsRequest request = new FsRequest();
        request.setPassword(site.getPassword());
        request.setPath(path);
        if (StringUtils.isNotBlank(site.getFolder())) {
            request.setPath(fixPath(site.getFolder() + "/" + path));
        }
        request.setPage(page);
        request.setSize(size);
        log.debug("call api: {} request: {}", url, request);
        FsListResponse response = post(site, url, request, FsListResponse.class);
        logError(response);
        log.debug("list files: {} {}", path, response.getData());
        return getFiles(version, response.getData());
    }

    private FsResponse getFiles(int version, FsResponse response) {
        if (response == null) {
            return null;
        }
        if (version == 2) {
            for (FsInfo fsInfo : response.getFiles()) {
                fsInfo.setThumb(fsInfo.getThumbnail());
            }
        } else if (response != null && response.getContent() != null) {
            response.setFiles(response.getContent());
        }
        response.setFiles(filter(response.getFiles()));
        return response;
    }

    private List<FsInfo> filter(List<FsInfo> files) {
        return files.stream().filter(e -> include(e.getName())).collect(Collectors.toList());
    }

    private String[] excludes = {"转存赠送优惠券", "代找", "会员"};

    private boolean include(String name) {
        for (String text : excludes) {
            if (name.contains(text)) {
                return false;
            }
        }
        return true;
    }

    public ShareInfo getShareInfo(Site site, String path) {
        String url = getUrl(site) + "/api/fs/other";
        FsRequest request = new FsRequest();
        request.setMethod("share_info");
        request.setPassword(site.getPassword());
        request.setPath(path);
        if (StringUtils.isNotBlank(site.getFolder())) {
            request.setPath(fixPath(site.getFolder() + "/" + path));
        }
        log.debug("call api: {} request: {}", url, request);
        ShareInfoResponse response = post(site, url, request, ShareInfoResponse.class);
        logError(response);
        log.debug("getShareInfo: {} {}", path, response.getData());
        return response.getData();
    }

    public VideoPreview preview(Site site, String path) {
        String id = site.getId() + "-" + path;
        VideoPreview preview = cache.getIfPresent(id);
        if (preview != null) {
            log.debug("cache: {}", id);
            return preview;
        }

        String url = getUrl(site) + "/api/fs/other";
        FsRequest request = new FsRequest();
        request.setPassword(site.getPassword());
        request.setPath(path);
        request.setData("preview");
        if (StringUtils.isNotBlank(site.getFolder())) {
            request.setPath(fixPath(site.getFolder() + "/" + path));
        }
        log.debug("call api: {} request: {}", url, request);
        VideoPreviewResponse response = post(site, url, request, VideoPreviewResponse.class);
        logError(response);
        log.debug("preview urls: {} {}", path, response.getData());
        cache.put(id, response.getData());
        return response.getData();
    }

    public FsDetail getFile(Site site, String path) {
        int version = getVersion(site);
        if (version == 2) {
            return getFileV2(site, path);
        } else {
            return getFileV3(site, path);
        }
    }

    private FsDetail getFileV3(Site site, String path) {
        String url = getUrl(site) + "/api/fs/get";
        FsRequest request = new FsRequest();
        request.setPassword(site.getPassword());
        request.setPath(path);
        if (StringUtils.isNotBlank(site.getFolder())) {
            request.setPath(fixPath(site.getFolder() + "/" + path));
        }
        log.debug("call api: {} request: {}", url, request);
        FsDetailResponse response = post(site, url, request, FsDetailResponse.class);
        logError(response);
        log.debug("get file: {} {}", path, response.getData());
        return response.getData();
    }

    private FsDetail getFileV2(Site site, String path) {
        String url = getUrl(site) + "/api/public/path";
        FsRequest request = new FsRequest();
        request.setPassword(site.getPassword());
        request.setPath(path);
        if (StringUtils.isNotBlank(site.getFolder())) {
            request.setPath(fixPath(site.getFolder() + "/" + path));
        }
        log.debug("call api: {}", url);
        FsListResponseV2 response = post(site, url, request, FsListResponseV2.class);
        logError(response);
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
            fsDetail.setRawUrl(fsInfo.getUrl());
            fsDetail.setType(fsInfo.getType());
            fsDetail.setProvider(fsInfo.getDriver());
            log.debug("get file: {} {}", path, fsDetail);
            return fsDetail;
        }
        return null;
    }

    private Integer getVersion(Site site) {
        if (site.getVersion() != null) {
            return site.getVersion();
        }

        String url = getUrl(site) + "/api/public/settings";
        log.debug("call api: {}", url);
        String text = get(site, url, String.class);
        int version;
        if (text != null && VERSION.matcher(text).find()) {
            version = 3;
        } else {
            version = 2;
        }
        log.info("site {}:{} version: {}", site.getId(), site.getName(), version);
        site.setVersion(version);
        siteService.save(site);

        return version;
    }

    private String getUrl(Site site) {
        if (site.getId() == 1) {
            return appProperties.isHostmode() ? "http://localhost:5234" : "http://localhost:5244";
        }
        return site.getUrl();
    }

    private <T> T get(Site site, String url, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        if (StringUtils.isNotBlank(site.getToken())) {
            headers.add("Authorization", site.getToken());
        }
        HttpEntity<Void> entity = new HttpEntity<>(null, headers);
        ResponseEntity<T> response = restTemplate.exchange(url, HttpMethod.GET, entity, responseType);
        return response.getBody();
    }

    private <T, R> T post(Site site, String url, R request, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        if (StringUtils.isNotBlank(site.getToken())) {
            headers.add("Authorization", site.getToken());
        }
        HttpEntity<R> entity = new HttpEntity<>(request, headers);
        ResponseEntity<T> response = restTemplate.exchange(url, HttpMethod.POST, entity, responseType);
        return response.getBody();
    }

    private void logError(Response<?> response) {
        if (response != null && response.getCode() != 200) {
            log.warn("error {} {}", response.getCode(), response.getMessage());
        }
    }
}
