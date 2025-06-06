package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.model.FsDetail;
import cn.har01d.alist_tvbox.util.Constants;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

@Slf4j
@Service
public class ProxyService {
    private static final int BUFFER_SIZE = 64 * 1024;
    private final AppProperties appProperties;
    private final Environment environment;
    private final SiteService siteService;
    private final AListService aListService;
    private final Set<String> proxyDrivers = Set.of("AliyundriveOpen", "AliyunShare", "BaiduNetdisk", "BaiduShare2",
            "Quark", "UC", "QuarkShare", "UCShare", "139Yun", "Yun139Share");

    public ProxyService(AppProperties appProperties,
                        Environment environment,
                        SiteService siteService,
                        AListService aListService) {
        this.appProperties = appProperties;
        this.environment = environment;
        this.siteService = siteService;
        this.aListService = aListService;
    }

    public void proxy(String path, HttpServletRequest request, HttpServletResponse response) throws IOException {
        String[] parts = path.split("\\$");
        Site site = siteService.getById(Integer.parseInt(parts[0]));
        path = parts[1];
        FsDetail fsDetail = aListService.getFile(site, path);
        if (fsDetail == null) {
            throw new BadRequestException("找不到文件 " + path);
        }

        String url = fsDetail.getRawUrl();
        String driver = fsDetail.getProvider();
        // check url for Alias
        if (proxyDrivers.contains(driver) || url.contains("115cdn") || url.contains("aliyundrive")
                || url.contains("baidu.com") || url.contains("quark.cn") || url.contains("uc.cn")) {
            log.debug("{} {}", driver, url);
            url = buildAListProxyUrl(site, path, fsDetail.getSign());
        } else {
            // 302
            log.debug("302 {} {}", driver, url);
            response.sendRedirect(url);
            return;
        }
        log.debug("proxy url: {}", url);

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
