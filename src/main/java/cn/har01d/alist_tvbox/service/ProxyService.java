package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.Share;
import cn.har01d.alist_tvbox.entity.ShareRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.model.FsDetail;
import cn.har01d.alist_tvbox.util.Constants;
import cn.har01d.alist_tvbox.util.Utils;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;

import static com.github.kiulian.downloader.model.Utils.closeSilently;

@Slf4j
@Service
public class ProxyService {
    private static final int BUFFER_SIZE = 64 * 1024;
    private final Cache<String, String> cache = Caffeine.newBuilder()
            .maximumSize(50)
            .build();
    private final AppProperties appProperties;
    private final Environment environment;
    private final DriverAccountRepository panAccountRepository;
    private final ShareRepository shareRepository;
    private final SiteService siteService;
    private final AListService aListService;

    public ProxyService(AppProperties appProperties,
                        Environment environment,
                        DriverAccountRepository panAccountRepository,
                        ShareRepository shareRepository,
                        SiteService siteService,
                        AListService aListService) {
        this.appProperties = appProperties;
        this.environment = environment;
        this.panAccountRepository = panAccountRepository;
        this.shareRepository = shareRepository;
        this.siteService = siteService;
        this.aListService = aListService;
    }

    public String generateProxyUrl(String type, String url) {
        String id = type + "-" + UUID.randomUUID().toString().replace("-", "");
        cache.put(id, url);
        return buildProxyUrl(id);
    }

    private String buildProxyUrl(String id) {
        String path = "/proxy/" + id;
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .scheme(appProperties.isEnableHttps() && !Utils.isLocalAddress() ? "https" : "http") // nginx https
                .replacePath(path)
                .replaceQuery("")
                .build()
                .toUriString();
    }

    public void proxy(String id, String path, HttpServletRequest request, HttpServletResponse response) throws IOException {
        Map<String, String> headers = new HashMap<>();
        var it = request.getHeaderNames().asIterator();
        while (it.hasNext()) {
            String name = it.next();
            headers.put(name, request.getHeader(name));
        }
        headers.put("user-agent", Constants.USER_AGENT);
        headers.put("referer", Constants.ALIPAN);

        String url = cache.getIfPresent(id);
        if (url != null) {
            log.info("proxy url: {} {}", id, url);
            if (id.startsWith("115-")) {
                String cookie = panAccountRepository.findByTypeAndMasterTrue(DriverType.PAN115).map(DriverAccount::getCookie).orElse("");
                headers.put("cookie", cookie);
                headers.put("referer", "https://115.com/");
            } else if (id.startsWith("xl-")) {
                headers.put("user-agent", "AndroidDownloadManager/13 (Linux; U; Android 13; M2004J7AC Build/SP1A.210812.016)");
            }
        } else {
            String[] parts = path.split("\\$");
            Site site = siteService.getById(Integer.parseInt(parts[0]));
            path = parts[1];
            FsDetail fsDetail = aListService.getFile(site, path);
            if (fsDetail == null) {
                throw new BadRequestException("找不到文件 " + path);
            }

            if (fsDetail.getProvider().contains("Aliyundrive")) {
                url = fsDetail.getRawUrl();
            } /*else if (fsDetail.getProvider().contains("Thunder")) {
                url = fsDetail.getRawUrl();
                headers.put("user-agent", "AndroidDownloadManager/13 (Linux; U; Android 13; M2004J7AC Build/SP1A.210812.016)");
            }*/ else if (fsDetail.getProvider().equals("115 Cloud") || fsDetail.getProvider().equals("115 Share")) {
                url = fsDetail.getRawUrl();
                String cookie = panAccountRepository.findByTypeAndMasterTrue(DriverType.PAN115).map(DriverAccount::getCookie).orElse("");
                headers.put("cookie", cookie);
                headers.put("referer", "https://115.com/");
            } else {
                url = buildProxyUrl(site, path, fsDetail.getSign());
            }
            updateShareTime(path);
        }

        log.trace("headers: {}", headers);
        downloadStraight(url, response, headers);
    }

    private void updateShareTime(String path) {
        String[] parts = path.split("/");
        if (parts.length > 3 && parts[2].equals("temp")) {
            path = "/" + parts[1] + "/" + parts[2] + "/" + parts[3];
            Share share = shareRepository.findByPath(path);
            if (share != null && share.isTemp()) {
                share.setTime(Instant.now());
                log.debug("update share time: {} {}", share.getId(), path);
                shareRepository.save(share);
            }
        }
    }

    private String buildProxyUrl(Site site, String path, String sign) {
        if (site.getUrl().startsWith("http://localhost")) {
            return ServletUriComponentsBuilder.fromCurrentRequest()
                    .port(appProperties.isHostmode() ? "5234" : environment.getProperty("ALIST_PORT", "5344"))
                    .replacePath("/d" + path)
                    .replaceQuery(StringUtils.isBlank(sign) ? "" : "sign=" + sign)
                    .build()
                    .toUri()
                    .toASCIIString();
        } else {
            if (StringUtils.isNotBlank(site.getFolder())) {
                path = fixPath(site.getFolder() + "/" + path);
            }
            return UriComponentsBuilder.fromHttpUrl(site.getUrl())
                    .replacePath("/d" + path)
                    .replaceQuery(StringUtils.isBlank(sign) ? "" : "sign=" + sign)
                    .build()
                    .toUri()
                    .toASCIIString();
        }
    }

    private String fixPath(String path) {
        return path.replaceAll("/+", "/");
    }

    public void downloadStraight(String url, HttpServletResponse response, Map<String, String> headers) throws IOException {
        HttpURLConnection urlConnection = openConnection(url, headers);
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
            closeSilently(is);
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
}
