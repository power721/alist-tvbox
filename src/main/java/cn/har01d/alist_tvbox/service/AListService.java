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
import org.springframework.boot.restclient.RestTemplateBuilder;
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
        return listFiles(site, path, page, size, false);
    }

    public FsResponse listFiles(Site site, String path, int page, int size, boolean refresh) {
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
        request.setRefresh(refresh);
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

        // Check for null files list to prevent NPE
        List<FsInfo> files = response.getFiles();
        if (files == null) {
            log.warn("Response has null files list");
            return response;
        }

        if (version == 2) {
            for (FsInfo fsInfo : files) {
                fsInfo.setThumb(fsInfo.getThumbnail());
            }
        } else if (response.getContent() != null) {
            response.setFiles(response.getContent());
            files = response.getFiles();
        }

        // Filter files if list is not null
        if (files != null) {
            response.setFiles(filter(files));
            for (var file : response.getFiles()) {
                try {
                    file.setModified(OffsetDateTime.parse(file.getModified()).toLocalDateTime().truncatedTo(ChronoUnit.SECONDS).toString());
                } catch (Exception e) {
                    log.debug("{}", e.getMessage());
                }
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

    public void move(Site site, String path, String newFolder) {
        int index = path.lastIndexOf("/");
        String dir = path.substring(0, index);
        String name = path.substring(index + 1);
        Map<String, Object> data = new HashMap<>();
        data.put("src_dir", dir);
        data.put("dst_dir", newFolder);
        data.put("names", List.of(name));
        data.put("overwrite", false);
        String url = getUrl(site) + "/api/fs/move";
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

    /** 创建目录(已存在时 AList 返回 500,调用方自行容忍)。 */
    public void mkdir(Site site, String path) {
        Map<String, Object> data = new HashMap<>();
        data.put("path", path);
        String url = getUrl(site) + "/api/fs/mkdir";
        log.debug("call api: {} request: {}", url, data);
        LoginResponse response = postAdmin(site, url, data, LoginResponse.class);
        logError(response);
    }

    /** 提交跨存储复制任务(异步,AList copy task);成功返回 true。 */
    public boolean copy(Site site, String srcDir, String dstDir, List<String> names) {
        Map<String, Object> data = new HashMap<>();
        data.put("src_dir", srcDir);
        data.put("dst_dir", dstDir);
        data.put("names", names);
        data.put("overwrite", false);
        String url = getUrl(site) + "/api/fs/copy";
        log.info("submit copy task: {} -> {} ({} files)", srcDir, dstDir, names.size());
        LoginResponse response = postAdmin(site, url, data, LoginResponse.class);
        logError(response);
        return true;
    }

    /**
     * 分享服务端转存(同步,网盘侧秒传,不经服务器字节中转):把 srcDir 下的文件/目录转存到 dstDir。
     * 源挂载驱动需实现服务端转存契约(全部分享盘族→同族账号;115 仅 cookie 版账号),
     * 不支持时端点返回错误,由调用方回退 copy 字节中转。
     */
    public void shareSave(Site site, String srcDir, List<String> names, String dstDir) {
        Map<String, Object> data = new HashMap<>();
        data.put("src_dir", srcDir);
        data.put("names", names);
        data.put("dst_dir", dstDir);
        String url = getUrl(site) + "/api/fs/share/save";
        log.info("share save: {}/{} -> {} ({} objects)", srcDir, names, dstDir, names.size());
        LoginResponse response = postAdmin(site, url, data, LoginResponse.class);
        logError(response);
    }

    /**
     * 轮询 AList copy 任务直至全部完成或超时。
     *
     * @return true = 无未完成任务(视为完成;失败靠调用方事后校验目标文件发现)
     */
    public boolean awaitCopyTasks(Site site, long timeoutMillis) {
        String url = getUrl(site) + "/api/admin/task/copy/undone";
        String token = login(site); // 轮询期间复用同一 token,避免每 3s 登录一次
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.set(HttpHeaders.AUTHORIZATION, token);
                ResponseEntity<com.fasterxml.jackson.databind.JsonNode> response =
                        restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(null, headers),
                                com.fasterxml.jackson.databind.JsonNode.class);
                // 任务列表接口返回裸数组 data;分页接口才是 data.content 包裹,两种都要兼容,
                // 否则解析落空会把"任务还没注册进列表"误判成"全部完成"
                var data = response.getBody() == null ? null : response.getBody().path("data");
                var content = data != null && data.isArray() ? data
                        : (data == null ? null : data.path("content"));
                if (content == null || !content.isArray() || content.isEmpty()) {
                    return true;
                }
                log.info("copy tasks running: {}", content.size());
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                log.warn("poll copy tasks failed: {}", e.getMessage());
                return false;
            }
        }
        return false;
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
        if (site.getStorageVersion() != null) {
            return site.getStorageVersion();
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
        site.setStorageVersion(version);
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
