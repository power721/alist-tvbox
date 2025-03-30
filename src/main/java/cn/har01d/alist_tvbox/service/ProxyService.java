package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
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
    private final SiteService siteService;
    private final AListService aListService;

    public ProxyService(AppProperties appProperties,
                        Environment environment,
                        DriverAccountRepository panAccountRepository,
                        SiteService siteService,
                        AListService aListService) {
        this.appProperties = appProperties;
        this.environment = environment;
        this.panAccountRepository = panAccountRepository;
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
        headers.put("User-Agent", Constants.USER_AGENT);
        headers.put("Referer", Constants.ALIPAN);

        String url = cache.getIfPresent(id);
        if (url != null) {
            log.info("proxy url: {} {}", id, url);
            if (url.startsWith("115-")) {
                String cookie = panAccountRepository.findByTypeAndMasterTrue(DriverType.PAN115).map(DriverAccount::getCookie).orElse("");
                headers.put("Cookie", cookie);
                headers.put("Referer", "https://115.com/");
            }
        } else {
            String[] parts = path.split("\\$");
            Site site = siteService.getById(Integer.parseInt(parts[0]));
            path = parts[1];
            FsDetail fsDetail = aListService.getFile(site, path);
            if (fsDetail == null) {
                throw new BadRequestException("找不到文件 " + path);
            }

            if (fsDetail.getProvider().equals("AliyundriveShare2Open") || fsDetail.getProvider().equals("AliyundriveOpen")) {
                url = fsDetail.getRawUrl();
            } else {
                url = buildProxyUrl(site, path, fsDetail.getSign());
            }
        }

        downloadStraight(url, response, headers);
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
        int responseCode = urlConnection.getResponseCode();
        if (responseCode >= 400) {
            throw new RuntimeException("Failed to download: HTTP " + responseCode);
        }
        response.setStatus(responseCode);
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
