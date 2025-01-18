package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.PanAccount;
import cn.har01d.alist_tvbox.entity.PanAccountRepository;
import cn.har01d.alist_tvbox.util.Constants;
import cn.har01d.alist_tvbox.util.Utils;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

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
    private static final int BUFFER_SIZE = 8 * 1024;
    private final Cache<String, String> cache = Caffeine.newBuilder()
            .maximumSize(10)
            .build();
    private final AppProperties appProperties;
    private final PanAccountRepository panAccountRepository;

    public ProxyService(AppProperties appProperties, PanAccountRepository panAccountRepository) {
        this.appProperties = appProperties;
        this.panAccountRepository = panAccountRepository;
    }

    public String generateProxyUrl(String url) {
        String id = "115-" + UUID.randomUUID().toString().replace("-", "");
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

    public void proxy(String id, HttpServletRequest request, HttpServletResponse response) throws IOException {
        String url = cache.getIfPresent(id);
        if (url != null) {
            log.info("proxy url: {} {}", id, url);
            String cookie = panAccountRepository.findByTypeAndMasterTrue(DriverType.PAN115).map(PanAccount::getCookie).orElse("");
            Map<String, String> headers = new HashMap<>();
            headers.put("Range", request.getHeader("Range"));
            headers.put("User-Agent", Constants.USER_AGENT);
            headers.put("Cookie", cookie);
            headers.put("Referer", "https://115.com/");
            log.debug("Range: {}", headers.get("Range"));

            downloadStraight(url, response, headers);
        }
    }

    public void downloadStraight(String url, HttpServletResponse response, Map<String, String> headers) throws IOException {
        HttpURLConnection urlConnection = openConnection(url, headers);
        int responseCode = urlConnection.getResponseCode();
        if (responseCode != 200 && responseCode != 206) {
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
