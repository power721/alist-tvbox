package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.DriveDirectory;
import cn.har01d.alist_tvbox.dto.DriveResolveResponse;
import cn.har01d.alist_tvbox.dto.ShareLink;
import cn.har01d.alist_tvbox.dto.Video;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.model.FileNameInfo;
import cn.har01d.alist_tvbox.model.FsInfo;
import cn.har01d.alist_tvbox.model.FsResponse;
import cn.har01d.alist_tvbox.util.Utils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Browse drive shares / discovery resources one directory at a time (no recursive flattening),
 * for the atv-player desktop client. Replaces the depth-3 {@code dfs()} flatten used by the
 * spider-facing {@code /tg-search?ac=gui} flow, which is left untouched for TVBox/Android.
 *
 * resourceId / dirId are stateless base64url handles ({@code siteId|path} / absolute path),
 * so the service keeps no per-resource cache.
 */
@Slf4j
@Service
public class DriveService {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final String PLAYLIST_MARKER = "~playlist";

    private final AListService aListService;
    private final ShareService shareService;
    private final SiteService siteService;
    private final ProxyService proxyService;
    private final SubscriptionService subscriptionService;
    private final AppProperties appProperties;

    public DriveService(AListService aListService,
                        ShareService shareService,
                        SiteService siteService,
                        ProxyService proxyService,
                        SubscriptionService subscriptionService,
                        AppProperties appProperties) {
        this.aListService = aListService;
        this.shareService = shareService;
        this.siteService = siteService;
        this.proxyService = proxyService;
        this.subscriptionService = subscriptionService;
        this.appProperties = appProperties;
    }

    /**
     * Resolve a share link or a {@code siteId$path} vodId into media info + top-level
     * directories (+ root's own media files). Single level, non-recursive.
     */
    public DriveResolveResponse resolve(String source, String title) {
        if (StringUtils.isBlank(source)) {
            throw new BadRequestException("source is required");
        }
        String decoded = URLDecoder.decode(source.trim(), StandardCharsets.UTF_8);

        Site site;
        String rootPath;
        if (isShareLink(decoded)) {
            ShareLink dto = new ShareLink();
            dto.setLink(decoded);
            rootPath = shareService.add(dto); // mounts temp storage + auto-descends single-folder shares
            site = siteService.getById(1);
        } else if (decoded.contains("$")) {
            String[] parts = decoded.split("\\$", 3);
            site = resolveSite(parts[0]);
            String pathPart = stripPlaylist(parts.length > 1 ? parts[1] : "");
            // vodIds like "1$<playurlId>$1" carry a numeric playurl id (not a real alist path);
            // resolve it back to the real path, mirroring TvBoxService.getDetail.
            rootPath = pathPart.matches("\\d+") ? proxyService.getPath(Integer.parseInt(pathPart)) : pathPart;
        } else {
            throw new BadRequestException("无法识别的资源: " + source);
        }

        log.info("drive resolve site={} rootPath={}", site.getId(), rootPath);

        DriveResolveResponse response = new DriveResolveResponse();
        response.setResourceId(encodeHandle(site.getId() + "|" + rootPath));
        response.setVodName(StringUtils.defaultIfBlank(title, ""));

        List<FsInfo> entries = listEntries(site, rootPath);
        List<DriveDirectory> directories = new ArrayList<>();
        List<Video> files = new ArrayList<>();
        for (FsInfo entry : entries) {
            if (entry.getType() == 1) {
                DriveDirectory directory = new DriveDirectory();
                directory.setId(encodeHandle(fixPath(rootPath + "/" + entry.getName())));
                directory.setName(entry.getName());
                directories.add(directory);
            } else if (isMediaFormat(entry.getName())) {
                files.add(toVideo(site, rootPath, entry));
            }
        }
        sortByName(directories, DriveDirectory::getName);
        sortByName(files, Video::getName);
        response.setDirectories(directories);
        response.setFiles(files);
        log.debug("resolve drive response={}", response);
        return response;
    }

    /**
     * List media files directly under one directory. Single level, non-recursive.
     * {@code dir} defaults to the resource root when blank.
     */
    public List<Video> listFiles(String resourceId, String dir) {
        String[] parts = decodeHandle(resourceId).split("\\|", 2);
        if (parts.length != 2) {
            throw new BadRequestException("invalid resourceId");
        }
        int siteId = parseIntOrThrow(parts[0], "invalid resourceId");
        Site site = siteService.getById(siteId);
        String dirPath = StringUtils.isNotBlank(dir) ? decodeHandle(dir) : parts[1];

        log.info("drive files site={} dirPath={}", site.getId(), dirPath);

        List<Video> files = new ArrayList<>();
        for (FsInfo entry : listEntries(site, dirPath)) {
            if (entry.getType() != 1 && isMediaFormat(entry.getName())) {
                files.add(toVideo(site, dirPath, entry));
            }
        }
        sortByName(files, Video::getName);
        log.debug("files list response={}", files);
        return files;
    }

    private List<FsInfo> listEntries(Site site, String path) {
        try {
            return doListEntries(site, path);
        } catch (BadRequestException e) {
            // Re-mount expired temp shares, mirroring TvBoxService.getPlaylist's recovery.
            String message = e.getMessage();
            if (message != null && message.contains("object not found")
                    && path.contains("/temp/") && path.contains("@")) {
                ShareLink share = new ShareLink();
                share.setLink(shareService.getLinkByPath(path));
                shareService.add(share);
                return doListEntries(site, path);
            }
            throw e;
        }
    }

    private List<FsInfo> doListEntries(Site site, String path) {
        FsResponse response = aListService.listFiles(site, path, 1, 0);
        if (response == null || response.getFiles() == null) {
            return List.of();
        }
        return response.getFiles();
    }

    /** Natural-order sort (episode numbers, 上/中/下 chapters); drive APIs return entries in
     *  unspecified order (often upload time), e.g. 上集/下集/中集. */
    private static <T> void sortByName(List<T> list, Function<T, String> nameExtractor) {
        try {
            list.sort(Comparator.comparing(nameExtractor.andThen(FileNameInfo::new)));
        } catch (Exception e) {
            log.warn("sort error: {}", e.getMessage());
        }
    }

    private Video toVideo(Site site, String dirPath, FsInfo entry) {
        String fullPath = fixPath(dirPath + "/" + entry.getName());
        Video video = new Video();
        video.setName(entry.getName());
        video.setTitle(entry.getName());
        video.setPath(fullPath);
        video.setSize(entry.getSize());
        video.setDuration(entry.getDuration());
        video.setTime(entry.getModified());
        int playUrlId = proxyService.generateProxyUrl(site, fullPath);
        video.setPlayId(site.getId() + "@" + playUrlId);
        video.setUrl(buildProxyUrl(site, playUrlId, entry.getName()));
        return video;
    }

    /** Same shape as {@code TvBoxService.buildProxyUrl}, but bakes the first subscription token
     *  (or {@code -}) since {@code /api} requests carry no path token for {@code getCurrentToken()}. */
    private String buildProxyUrl(Site site, int playUrlId, String name) {
        String suffix = StringUtils.endsWithIgnoreCase(name, ".iso") ? ".iso" : "";
        String proxyPath = "/p/" + subscriptionService.getFirstToken() + "/"
                + site.getId() + "@" + playUrlId + suffix;
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .scheme(appProperties.isEnableHttps() && !Utils.isLocalAddress() ? "https" : "http")
                .replacePath(proxyPath)
                .replaceQuery("")
                .build()
                .toUriString();
    }

    private boolean isShareLink(String value) {
        String lower = StringUtils.lowerCase(value);
        return lower.contains("://") || lower.startsWith("magnet:") || lower.startsWith("ed2k:");
    }

    private Site resolveSite(String idPart) {
        if ("0".equals(idPart) || "null".equals(idPart)) {
            return siteService.getById(1);
        }
        try {
            return siteService.getById(Integer.parseInt(idPart));
        } catch (NumberFormatException e) {
            return siteService.getByName(idPart);
        }
    }

    private String stripPlaylist(String path) {
        int idx = path.indexOf(PLAYLIST_MARKER);
        if (idx >= 0) {
            path = path.substring(0, idx);
        }
        return fixPath(path);
    }

    private boolean isMediaFormat(String name) {
        int idx = name.lastIndexOf('.');
        if (idx <= 0) {
            return false;
        }
        String suffix = name.substring(idx + 1).toLowerCase();
        Set<String> formats = appProperties.getFormats();
        return (formats != null && formats.contains(suffix)) || "strm".equals(suffix) || "cas".equals(suffix);
    }

    private String fixPath(String path) {
        return path.replaceAll("/+", "/");
    }

    private String encodeHandle(String value) {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeHandle(String handle) {
        return new String(DECODER.decode(handle), StandardCharsets.UTF_8);
    }

    private int parseIntOrThrow(String value, String message) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new BadRequestException(message);
        }
    }
}
