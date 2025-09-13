package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.FileItem;
import cn.har01d.alist_tvbox.dto.ValidateResult;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.model.FsDetail;
import cn.har01d.alist_tvbox.model.FsDetailResponse;
import cn.har01d.alist_tvbox.model.FsInfo;
import cn.har01d.alist_tvbox.model.FsInfoV2;
import cn.har01d.alist_tvbox.model.FsListResponse;
import cn.har01d.alist_tvbox.model.FsListResponseV2;
import cn.har01d.alist_tvbox.model.FsRequest;
import cn.har01d.alist_tvbox.model.FsResponse;
import cn.har01d.alist_tvbox.model.FsResponseV2;
import cn.har01d.alist_tvbox.model.LoginRequest;
import cn.har01d.alist_tvbox.model.LoginResponse;
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
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static cn.har01d.alist_tvbox.util.Constants.ATV_PASSWORD;

@Slf4j
@Service
public class AListService {
    private static final Pattern VERSION = Pattern.compile("\"version\":\"v\\d+\\.\\d+\\.\\d+\"");

    private final RestTemplate restTemplate;
    private final SettingRepository settingRepository;
    private final SiteService siteService;
    private final AListLocalService aListLocalService;
    private final Cache<String, VideoPreview> cache = Caffeine.newBuilder()
            .maximumSize(10)
            .expireAfterWrite(Duration.ofSeconds(895))
            .build();

    public AListService(RestTemplateBuilder builder,
                        SettingRepository settingRepository,
                        SiteService siteService,
                        AListLocalService aListLocalService) {
        this.restTemplate = builder
                .defaultHeader(HttpHeaders.ACCEPT, Constants.ACCEPT)
                .defaultHeader(HttpHeaders.USER_AGENT, Constants.USER_AGENT)
                .connectTimeout(Duration.ofSeconds(60))
                .readTimeout(Duration.ofSeconds(60))
                .build();
        this.settingRepository = settingRepository;
        this.siteService = siteService;
        this.aListLocalService = aListLocalService;
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

    public ValidateResult validate(String path) {
        Site site = siteService.getById(1);
        try {
            listFiles(site, path, 1, 1);
            return new ValidateResult(true, "");
        } catch (Exception e) {
            return new ValidateResult(false, e.getMessage());
        }
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
        for (var file : response.getFiles()) {
            try {
                file.setModified(OffsetDateTime.parse(file.getModified()).toLocalDateTime().truncatedTo(ChronoUnit.SECONDS).toString());
            } catch (Exception e) {
                log.debug("{}", e.getMessage());
            }
        }
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
        if (response.getData() != null) {
            cache.put(id, response.getData());
        }
        return response.getData();
    }

    public void rename(Site site, String path, String newName) {
        Map<String, Object> data = new HashMap<>();
        data.put("name", newName);
        data.put("overwrite", false);
        data.put("path", path);
        String url = getUrl(site) + "/api/fs/rename";
        log.debug("call api: {} request: {}", url, data);
        LoginResponse response = postAdmin(site, url, data, LoginResponse.class);
        logError(response);
    }

    public void remove(Site site, String path) {
        int index = path.lastIndexOf("/");
        String dir = path.substring(0, index);
        String name = path.substring(index + 1);
        Map<String, Object> data = new HashMap<>();
        data.put("dir", dir);
        data.put("names", List.of(name));
        String url = getUrl(site) + "/api/fs/remove";
        log.debug("call api: {} request: {}", url, data);
        LoginResponse response = postAdmin(site, url, data, LoginResponse.class);
        logError(response);
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
            return "http://localhost:" + aListLocalService.getInternalPort();
        }
        return site.getUrl();
    }

    private <T> T get(Site site, String url, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        if (StringUtils.isNotBlank(site.getToken())) {
            headers.set(HttpHeaders.AUTHORIZATION, site.getToken());
        }
        HttpEntity<Void> entity = new HttpEntity<>(null, headers);
        ResponseEntity<T> response = restTemplate.exchange(url, HttpMethod.GET, entity, responseType);
        return response.getBody();
    }

    private <T, R> T post(Site site, String url, R request, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        if (StringUtils.isNotBlank(site.getToken())) {
            headers.set(HttpHeaders.AUTHORIZATION, site.getToken());
        }
        HttpEntity<R> entity = new HttpEntity<>(request, headers);
        ResponseEntity<T> response = restTemplate.exchange(url, HttpMethod.POST, entity, responseType);
        return response.getBody();
    }

    private <T, R> T postAdmin(Site site, String url, R request, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, login(site));
        HttpEntity<R> entity = new HttpEntity<>(request, headers);
        ResponseEntity<T> response = restTemplate.exchange(url, HttpMethod.POST, entity, responseType);
        return response.getBody();
    }

    private String login(Site site) {
        String username = "atv";
        String password = settingRepository.findById(ATV_PASSWORD).map(Setting::getValue).orElseThrow(BadRequestException::new);
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        String url = getUrl(site) + "/api/auth/login";
        LoginResponse response = restTemplate.postForObject(url, request, LoginResponse.class);
        log.debug("AList login response: {}", response);
        return response.getData().getToken();
    }

    private void logError(Response<?> response) {
        if (response != null && response.getCode() >= 400) {
            log.error("error {} {}", response.getCode(), response.getMessage());
            String message = response.getMessage().replace("failed get objs: ", "").replace("failed to list objs: ", "").trim();
            throw new BadRequestException(message);
        }
    }
}
