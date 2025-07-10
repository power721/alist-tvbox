package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.PlayUrl;
import cn.har01d.alist_tvbox.entity.PlayUrlRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import cn.har01d.alist_tvbox.model.FsDetail;
import cn.har01d.alist_tvbox.util.Constants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

@Slf4j
@Service
public class ProxyService {
    private static final int BUFFER_SIZE = 64 * 1024;
    private final AppProperties appProperties;
    private final PlayUrlRepository playUrlRepository;
    private final SiteService siteService;
    private final AListService aListService;
    private final AListLocalService aListLocalService;
    private final Set<String> proxyDrivers = Set.of("AliyundriveOpen", "AliyunShare", "BaiduNetdisk", "BaiduShare2",
            "Quark", "UC", "QuarkShare", "UCShare", "115 Cloud", "115 Share");

    public ProxyService(AppProperties appProperties,
                        PlayUrlRepository playUrlRepository,
                        SiteService siteService,
                        AListService aListService,
                        AListLocalService aListLocalService) {
        this.appProperties = appProperties;
        this.playUrlRepository = playUrlRepository;
        this.siteService = siteService;
        this.aListService = aListService;
        this.aListLocalService = aListLocalService;
    }

    @Scheduled(cron = "0 45 * * * *")
    public void clean() {
        List<PlayUrl> expired = playUrlRepository.findByTimeBefore(Instant.now());
        if (!expired.isEmpty()) {
            log.info("delete {} expired play urls", expired.size());
        }
        playUrlRepository.deleteAll(expired);
    }

    public int generateProxyUrl(Site site, String path) {
        PlayUrl playUrl = playUrlRepository.findFirstBySiteAndPath(site.getId(), path, Sort.by("id").descending());
        if (playUrl == null || playUrl.getTime().isBefore(Instant.now())) {
            playUrl = playUrlRepository.save(new PlayUrl(site.getId(), path, Instant.now().plus(7, ChronoUnit.DAYS)));
        }
        return playUrl.getId();
    }

    public int generatePath(Site site, String path) {
        PlayUrl playUrl = playUrlRepository.findFirstBySiteAndPath(site.getId(), path, Sort.by("id").descending());
        if (playUrl == null) {
            playUrl = playUrlRepository.save(new PlayUrl(site.getId(), path, Instant.now().plus(30, ChronoUnit.DAYS)));
        }
        return playUrl.getId();
    }

    public String getPath(int id) {
        PlayUrl playUrl = playUrlRepository.findById(id).orElseThrow(() -> new NotFoundException("Not found"));
        return playUrl.getPath();
    }

    public void proxy(String tid, HttpServletRequest request, HttpServletResponse response) throws IOException {
        String[] parts = tid.split("@");
        int id = Integer.parseInt(parts[1]);
        PlayUrl playUrl = playUrlRepository.findById(id).orElseThrow(() -> new NotFoundException("Not found: " + id));
        String path = playUrl.getPath();
        Site site = siteService.getById(playUrl.getSite());
        FsDetail fsDetail = aListService.getFile(site, path);
        if (fsDetail == null) {
            throw new BadRequestException("找不到文件 " + path);
        }

        String url = fsDetail.getRawUrl();
        String driver = fsDetail.getProvider();
        // check url for Alias
        if (proxyDrivers.contains(driver) || url.contains("115cdn") || url.contains("aliyundrive")
                || url.contains("baidu.com") || url.contains("quark.cn") || url.contains("uc.cn")
                || url.startsWith("http://localhost")) {
            log.debug("{} {}", driver, url);
            url = buildAListProxyUrl(site, path, fsDetail.getSign());
        } else {
            // 302
            log.debug("302 {} {}", driver, url);
            response.sendRedirect(url);
            return;
        }
        log.debug("proxy url: {} {}", driver, url);

        Map<String, String> headers = new HashMap<>();
        var it = request.getHeaderNames().asIterator();
        while (it.hasNext()) {
            String name = it.next();
            headers.put(name, request.getHeader(name));
        }
        headers.put("user-agent", appProperties.getUserAgent());
        headers.put("referer", Constants.ALIPAN);
        log.trace("headers: {}", headers);

        downloadStraight(url, request, response, headers);
    }

    private String buildAListProxyUrl(Site site, String path, String sign) {
        if (site.getUrl().startsWith("http://localhost")) {
            return ServletUriComponentsBuilder.fromCurrentRequest()
                    .port(aListLocalService.getExternalPort())
                    .replacePath("/p" + path)
                    .replaceQuery(StringUtils.isBlank(sign) ? "" : "sign=" + sign)
                    .build()
                    .toUri()
                    .toASCIIString();
        } else {
            if (StringUtils.isNotBlank(site.getFolder())) {
                path = fixPath(site.getFolder() + "/" + path);
            }
            return UriComponentsBuilder.fromHttpUrl(site.getUrl())
                    .replacePath("/p" + path)
                    .replaceQuery(StringUtils.isBlank(sign) ? "" : "sign=" + sign)
                    .build()
                    .toUri()
                    .toASCIIString();
        }
    }

    private String fixPath(String path) {
        return path.replaceAll("/+", "/");
    }

    public void downloadStraight(String url, HttpServletRequest request, HttpServletResponse response, Map<String, String> headers) throws IOException {
        HttpURLConnection urlConnection = openConnection(url, headers);
        urlConnection.setRequestMethod(request.getMethod());
        response.setStatus(urlConnection.getResponseCode());
        urlConnection.getHeaderFields().forEach((key, value) -> response.setHeader(key, value.get(0)));
        copyAndCloseInput(urlConnection.getInputStream(), response.getOutputStream());
    }

    private static void copyAndCloseInput(InputStream is, OutputStream os) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            int count;
            while ((count = is.read(buffer)) != -1) {
                if (Thread.interrupted()) {
                    throw new CancellationException();
                }
                os.write(buffer, 0, count);
            }
        } finally {
            IOUtils.closeQuietly(is);
        }
    }

    private HttpURLConnection openConnection(String httpUrl, Map<String, String> headers) throws IOException {
        URL url = new URL(httpUrl);
        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                urlConnection.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }
        return urlConnection;
    }

    public void deleteAll() {
        playUrlRepository.deleteAll();
    }

    public Page<PlayUrl> list(Pageable pageable) {
        return playUrlRepository.findAll(pageable);
    }
}
