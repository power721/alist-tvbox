package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.Video;
import cn.har01d.alist_tvbox.entity.PlayUrl;
import cn.har01d.alist_tvbox.entity.PlayUrlRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import cn.har01d.alist_tvbox.live.service.HuyaParseService;
import cn.har01d.alist_tvbox.model.FsDetail;
import cn.har01d.alist_tvbox.util.Constants;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
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
import java.time.Duration;
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
    private final HuyaParseService huyaParseService;
    private final Set<String> proxyDrivers = Set.of("AliyundriveOpen", "AliyunShare", "BaiduNetdisk", "BaiduShare2",
            "Quark", "UC", "UCTV", "QuarkShare", "UCShare", "115 Cloud", "115 Share", "115 Index", "GuangYaPan", "GuangYaPanShare");
    private final OkHttpClient okHttpClient = new OkHttpClient();
    private final Cache<String, FsDetail> fileCache = Caffeine.newBuilder()
            .maximumSize(512)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    public ProxyService(AppProperties appProperties,
                        PlayUrlRepository playUrlRepository,
                        SiteService siteService,
                        AListService aListService,
                        AListLocalService aListLocalService,
                        HuyaParseService huyaParseService) {
        this.appProperties = appProperties;
        this.playUrlRepository = playUrlRepository;
        this.siteService = siteService;
        this.aListService = aListService;
        this.aListLocalService = aListLocalService;
        this.huyaParseService = huyaParseService;
    }

    @Scheduled(cron = "0 45 * * * *")
    public void clean() {
        List<PlayUrl> expired = playUrlRepository.findByTimeBeforeAndRatingIsNull(Instant.now());
        if (!expired.isEmpty()) {
            log.info("delete {} expired play urls", expired.size());
        }
        playUrlRepository.deleteAll(expired);
    }

    public int generateImageUrl(String url, String referer) {
        PlayUrl playUrl = playUrlRepository.findFirstBySiteAndPath(0, url, Sort.by("id").descending());
        if (playUrl == null || playUrl.getTime().isBefore(Instant.now())) {
            playUrl = playUrlRepository.save(new PlayUrl(url, referer, Instant.now().plus(3, ChronoUnit.DAYS)));
        }
        return playUrl.getId();
    }

    public int generateProxyUrl(String path) {
        PlayUrl playUrl = playUrlRepository.findFirstBySiteAndPath(0, path, Sort.by("id").descending());
        if (playUrl == null || playUrl.getTime().isBefore(Instant.now())) {
            playUrl = playUrlRepository.save(new PlayUrl(0, path, Instant.now().plus(1, ChronoUnit.DAYS)));
        }
        return playUrl.getId();
    }

    public int generateProxyUrl(Site site, String path, Video video) {
        PlayUrl playUrl = playUrlRepository.findFirstBySiteAndPath(site.getId(), path, Sort.by("id").descending());
        if (playUrl == null || playUrl.getTime().isBefore(Instant.now())) {
            playUrl = playUrlRepository.save(new PlayUrl(site.getId(), path, Instant.now().plus(7, ChronoUnit.DAYS)));
        }
        video.setId(playUrl.getId());
        video.setRating(playUrl.getRating());
        return playUrl.getId();
    }

    public int generateProxyUrl(Site site, String path) {
        PlayUrl playUrl = playUrlRepository.findFirstBySiteAndPath(site.getId(), path, Sort.by("id").descending());
        if (playUrl == null || playUrl.getTime().isBefore(Instant.now())) {
            playUrl = playUrlRepository.save(new PlayUrl(site.getId(), path, Instant.now().plus(7, ChronoUnit.DAYS)));
        }
        return playUrl.getId();
    }

    /** 长效代理注册(追剧盘线路等长期回放场景):行不存在新建;剩余寿命超过 ttl 一半直接复用(不写库);
     * 不足(含已过期)原地续满 ttl —— 播放历史/跨端同步绑定的 `siteId@pid` 物理地址持续可播,
     * 不再被 7 天默认有效期回收;停止回放 ttl 后由 clean 自然清理。共享行(ownerUid=0)。 */
    public int generateProxyUrl(Site site, String path, Duration ttl) {
        return generateProxyUrl(site, path, ttl, 0);
    }

    /** 带归属的长效代理注册:ownerUid&gt;0 的行 /p 校验 token 归属一致;同盘同路径已有行直接复用
     * (归属不迁移,先注册者所有 —— 共享挂载下同剧多用户共用同一路径是预期行为)。 */
    public int generateProxyUrl(Site site, String path, Duration ttl, int ownerUid) {
        Instant now = Instant.now();
        PlayUrl playUrl = playUrlRepository.findFirstBySiteAndPath(site.getId(), path, Sort.by("id").descending());
        if (playUrl == null) {
            PlayUrl created = new PlayUrl(site.getId(), path, now.plus(ttl));
            created.setOwnerUid(ownerUid);
            return playUrlRepository.save(created).getId();
        }
        if (playUrl.getTime().isAfter(now.plus(ttl.dividedBy(2)))) {
            return playUrl.getId();
        }
        playUrl.setTime(now.plus(ttl));
        return playUrlRepository.save(playUrl).getId();
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

    public PlayUrl getPlayUrl(int id) {
        return playUrlRepository.findById(id).orElseThrow(() -> new NotFoundException("Not found: " + id));
    }

    /** uid&lt;=0 视为管理级/共享 token(全局 tokens、匿名管理入口):归属行一律放行。 */
    public void proxy(String tid, int uid, HttpServletRequest request, HttpServletResponse response) throws IOException {
        int id = parsePlayUrlId(tid);
        PlayUrl playUrl = playUrlRepository.findById(id).orElseThrow(() -> new NotFoundException("Not found: " + id));
        // 盘线路 pid 归属校验(docs/multi-user-design.md §3.3):共享行(0)放行;归属行仅本人可播
        if (playUrl.getOwnerUid() > 0 && uid > 0 && playUrl.getOwnerUid() != uid) {
            throw new BadRequestException("无权播放该资源");
        }
        String path = playUrl.getPath();
        String url;

        Map<String, String> headers = new HashMap<>();
        var it = request.getHeaderNames().asIterator();
        while (it.hasNext()) {
            String name = it.next();
            headers.put(name, request.getHeader(name));
        }
        headers.put("user-agent", Constants.MOBILE_USER_AGENT);

        if (playUrl.getSite() == 0) {
            url = huyaParseService.getTrueUrl(playUrl.getPath());
            headers.put("user-agent", huyaParseService.getUa());
            headers.put("referer", "https://www.huya.com/");
        } else {
            Site site = siteService.getById(playUrl.getSite());
            FsDetail fsDetail = fileCache.get(site.getId() + "|" + path, k -> aListService.getFile(site, path));
            if (fsDetail == null) {
                throw new BadRequestException("找不到文件 " + path);
            }

            url = fsDetail.getRawUrl();
            String driver = fsDetail.getProvider();
            // check url for Alias
            if (url.contains(".strm")) {
                Request rq = new Request.Builder().url(url).get().build();
                try (Response rp = okHttpClient.newCall(rq).execute(); ResponseBody body = rp.body()) {
                    url = body != null ? body.string() : url;
                }
                log.debug("302 {} {}", driver, url);
                response.sendRedirect(url);
                return;
            } else if (url.contains(".m3u8")) {
                log.debug("302 {} {}", driver, url);
                response.sendRedirect(url);
                return;
            } else if (url.contains("#proxy=0")) {
                log.debug("302 {} {}", driver, url);
                response.sendRedirect(url);
                return;
            } else if (proxyDrivers.contains(driver) || url.contains("aliyundrive")
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
            headers.put("referer", Constants.ALIPAN);
        }

        log.trace("headers: {}", headers);

        downloadStraight(url, request, response, headers);
    }

    static int parsePlayUrlId(String tid) {
        String[] parts = tid.split("@");
        return Integer.parseInt(parts[1].split("\\.")[0]);
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
            return UriComponentsBuilder.fromUriString(site.getUrl())
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

    /** 按用户吊销:管理级清全部;USER 只清自己的归属行(共享行/他人行不可动)。 */
    @org.springframework.transaction.annotation.Transactional
    public void deleteByOwner(int uid) {
        if (uid <= 0) {
            deleteAll();
            return;
        }
        playUrlRepository.deleteByOwnerUid(uid);
    }

    public Page<PlayUrl> list(Pageable pageable) {
        return playUrlRepository.findAll(pageable);
    }

    /** 列表按归属过滤:管理级全量;USER 仅自己的归属行。 */
    public Page<PlayUrl> list(Pageable pageable, int uid) {
        if (uid <= 0) {
            return list(pageable);
        }
        return playUrlRepository.findByOwnerUid(uid, pageable);
    }
}
