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
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.io.IOUtils;
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
import java.util.Set;
import java.util.concurrent.CancellationException;

import static cn.har01d.alist_tvbox.util.Constants.STORAGE_ID_FRAGMENT;

@Slf4j
@Service
public class ProxyService {
    private static final int BUFFER_SIZE = 64 * 1024;
    private final Cache<String, String> cache = Caffeine.newBuilder()
            .maximumSize(50)
            .build();
    private final AppProperties appProperties;
    private final Environment environment;
    private final DriverAccountRepository driverAccountRepository;
    private final ShareRepository shareRepository;
    private final SiteService siteService;
    private final AListService aListService;
    private final Set<String> proxyDrivers = Set.of("Quark", "UC", "QuarkShare", "UCShare", "115 Cloud");

    public ProxyService(AppProperties appProperties,
                        Environment environment,
                        DriverAccountRepository driverAccountRepository,
                        ShareRepository shareRepository,
                        SiteService siteService,
                        AListService aListService) {
        this.appProperties = appProperties;
        this.environment = environment;
        this.driverAccountRepository = driverAccountRepository;
        this.shareRepository = shareRepository;
        this.siteService = siteService;
        this.aListService = aListService;
    }

    public void proxy(String path, HttpServletRequest request, HttpServletResponse response) throws IOException {
        Map<String, String> headers = new HashMap<>();
        var it = request.getHeaderNames().asIterator();
        while (it.hasNext()) {
            String name = it.next();
            headers.put(name, request.getHeader(name));
        }
        headers.put("user-agent", appProperties.getUserAgent());
        headers.put("referer", Constants.ALIPAN);

        String[] parts = path.split("\\$");
        Site site = siteService.getById(Integer.parseInt(parts[0]));
        path = parts[1];
        FsDetail fsDetail = aListService.getFile(site, path);
        if (fsDetail == null) {
            throw new BadRequestException("找不到文件 " + path);
        }

        String url = fsDetail.getRawUrl();
        if (fsDetail.getProvider().equals("115 Share")) {
            // ignore
        } else if (url.contains("115cdn.net")) {
            DriverAccount account;
            if (url.contains(STORAGE_ID_FRAGMENT)) {
                int index = url.indexOf(STORAGE_ID_FRAGMENT);
                int accountId = Integer.parseInt(url.substring(index + STORAGE_ID_FRAGMENT.length())) - DriverAccountService.IDX;
                account = driverAccountRepository.findById(accountId).orElseThrow();
                url = url.substring(0, index);
            } else {
                account = driverAccountRepository.findByTypeAndMasterTrue(DriverType.PAN115).orElseThrow();
            }
            log.debug("use 115 account: {}", account.getId());
            String cookie = account.getCookie();
            headers.put("cookie", cookie);
            headers.put("user-agent", Constants.USER_AGENT);
            headers.put("referer", "https://115.com/");
        } else if (fsDetail.getProvider().contains("Thunder")) {
            headers.put("user-agent", "AndroidDownloadManager/13 (Linux; U; Android 13; M2004J7AC Build/SP1A.210812.016)");
        } else if (fsDetail.getProvider().contains("Aliyundrive")) {
            headers.put("origin", Constants.ALIPAN);
        } else if (proxyDrivers.contains(fsDetail.getProvider())) {
            url = buildProxyUrl(site, path, fsDetail.getSign());
        }
        log.debug("play url: {}", url);

        log.trace("headers: {}", headers);
        downloadStraight(url, request, response, headers);
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

    private String buildDirectUrl(Site site, String path, String sign) {
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
}
