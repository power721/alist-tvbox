package cn.har01d.alist_tvbox.live.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.util.Utils;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static cn.har01d.alist_tvbox.util.Constants.FOLDER;

@Slf4j
@Service
public class LiveService {
    public static final String HOT_CATEGORY_ID = "hot";
    private static final int HOT_LIMIT = 20;
    private final List<LivePlatform> platforms = new ArrayList<>();
    private final Cache<String, MovieList> cache = Caffeine.newBuilder()
            .maximumSize(50)
            .expireAfterWrite(Duration.ofMinutes(15))
            .build();
    private final HuyaService huyaService;
    private final LiveFollowService liveFollowService;
    private final SubscriptionService subscriptionService;
    private final AppProperties appProperties;

    public LiveService(HuyaService huyaService, DouyuService douyuService, BilibiliService bilibiliService, CcService ccService, KuaishouService kuaishouService, DouyinService douyinService, TwitchService twitchService, SoopService soopService, LiveFollowService liveFollowService, SubscriptionService subscriptionService, AppProperties appProperties) {
        this.huyaService = huyaService;
        platforms.add(huyaService);
        platforms.add(douyuService);
        platforms.add(bilibiliService);
        platforms.add(ccService);
        platforms.add(kuaishouService);
        platforms.add(douyinService);
        platforms.add(twitchService);
        platforms.add(soopService);
        this.liveFollowService = liveFollowService;
        this.subscriptionService = subscriptionService;
        this.appProperties = appProperties;
    }

    public MovieList home() throws IOException {
        try {
            return huyaService.home();
        } catch (Exception e) {
            // 首页推荐被风控/网络抖动时返回空列表,比抛 500 对播放器更友好
            log.warn("虎牙首页推荐获取失败", e);
            return new MovieList();
        }
    }

    public CategoryList category() throws IOException {
        CategoryList result = new CategoryList();
        List<Category> list = new ArrayList<>();
        Category followCategory = new Category();
        followCategory.setType_id(LiveFollowService.CATEGORY_ID);
        followCategory.setType_name("关注");
        followCategory.setType_flag(0);
        list.add(followCategory);
        for (LivePlatform platform : platforms) {
            Category category = new Category();
            category.setType_id(platform.getType());
            category.setType_name(platform.getName());
            category.setType_flag(0);
            list.add(category);
        }
        result.setCategories(list);
        result.setTotal(result.getCategories().size());
        result.setLimit(result.getCategories().size());

        log.debug("category result: {}", result);
        return result;
    }

    public MovieList list(String id, String ac, String sort, Integer pg) throws IOException {
        MovieList result = new MovieList();
        if (id.contains("-")) {
            String[] parts = id.split("-");
            for (LivePlatform platform : platforms) {
                if (platform.getType().equals(parts[0])) {
                    if (HOT_CATEGORY_ID.equals(parts[1])) {
                        return hotRooms(platform);
                    }
                    return platform.list(id, ac, sort, pg);
                }
            }
        } else {
            String mode = appProperties.getLiveHotMode();
            // 缓存键带模式后缀:切换 live_hot_mode 后旧结构的缓存自然失效
            String cacheKey = id + "#" + mode;
            var temp = cache.getIfPresent(cacheKey);
            if (temp != null) {
                return temp;
            }

            for (LivePlatform platform : platforms) {
                if (platform.getType().equals(id)) {
                    List<MovieDetail> list = new ArrayList<>();
                    if ("folder".equals(mode)) {
                        MovieDetail hot = new MovieDetail();
                        hot.setVod_id(platform.getType() + "-" + HOT_CATEGORY_ID);
                        hot.setVod_pic(getUrl("/hot_live.webp"));
                        hot.setVod_name("热门直播间");
                        hot.setVod_tag(FOLDER);
                        list.add(hot);
                    } else if ("mix".equals(mode)) {
                        // 热门拉取失败时降级为仅分类文件夹,不影响平台首页可用性
                        try {
                            MovieList hot = hotRooms(platform);
                            list.addAll(hot.getList().subList(0, Math.min(hot.getList().size(), HOT_LIMIT)));
                        } catch (Exception e) {
                            log.warn("{} hot rooms failed, fallback to categories only", platform.getName(), e);
                        }
                    }
                    var categoryList = platform.category();
                    for (var item : categoryList.getCategories()) {
                        MovieDetail detail = new MovieDetail();
                        detail.setVod_id(item.getType_id());
                        detail.setVod_name(item.getType_name());
                        detail.setVod_pic(item.getCover());
                        detail.setVod_tag(FOLDER);
                        list.add(detail);
                    }

                    result.setList(list);
                    result.setTotal(result.getList().size());
                    result.setLimit(result.getList().size());
                    cache.put(cacheKey, result);
                    return result;
                }
            }
        }
        return result;
    }

    private String getUrl(String path) {
        try {
            return ServletUriComponentsBuilder.fromCurrentRequest()
                    .scheme(appProperties.isEnableHttps() && !Utils.isLocalAddress() ? "https" : "http") // nginx https
                    .replacePath(path)
                    .replaceQuery(null)
                    .build()
                    .toUriString();
        } catch (IllegalStateException e) {
            // 无请求上下文(单元测试/非 HTTP 线程)时封面缺省,不影响列表数据本身
            return null;
        }
    }

    /** 平台热门/推荐直播间(platform.home() 数据源),点"热门直播间"文件夹或混排时取用,带 15 分钟缓存。 */
    private MovieList hotRooms(LivePlatform platform) throws IOException {
        String cacheKey = platform.getType() + "-" + HOT_CATEGORY_ID;
        MovieList cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }
        MovieList result = platform.home();
        cache.put(cacheKey, result);
        return result;
    }

    public MovieList search(String wd) throws IOException {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();
        for (LivePlatform platform : platforms) {
            try {
                MovieList platformResult = platform.search(wd);
                if (platformResult != null) {
                    for (MovieDetail detail : platformResult.getList()) {
                        String remarks = detail.getVod_remarks();
                        detail.setVod_remarks("[" + platform.getName() + "]" +
                                (remarks == null || remarks.isBlank() ? "" : " " + remarks));
                    }
                    list.addAll(platformResult.getList());
                }
            } catch (Exception e) {
                log.warn("{} search failed: {}", platform.getName(), wd, e);
            }
        }
        result.setList(list);
        result.setTotal(list.size());
        result.setLimit(list.size());
        log.debug("search result: {}", result);
        return result;
    }

    public MovieList detail(String tid, String client) throws IOException {
        // 网页端与安卓客户端拿到的播放地址形态不同(如 Twitch/SOOP 仅网页端走代理),缓存键须区分
        String cacheKey = tid + "@" + (client == null ? "" : client);
        MovieList result = cache.getIfPresent(cacheKey);
        if (result == null) {
            result = new MovieList();
            String[] parts = tid.split("\\$");
            for (LivePlatform platform : platforms) {
                if (platform.getType().equals(parts[0])) {
                    result = platform.detail(tid, client);
                    if (!result.getList().isEmpty()) {
                        result.getList().get(0).setVod_director(platform.getName());
                    }
                    cache.put(cacheKey, result);
                    break;
                }
            }
        }
        return decorateWithFollowTrack(result);
    }

    /** 在缓存结果的副本上追加"关注/取消关注"轨道,避免把关注状态写进 15 分钟缓存。 */
    private MovieList decorateWithFollowTrack(MovieList raw) {
        if (raw.getList().isEmpty()) {
            return raw;
        }
        int uid = liveFollowService.resolveUid(subscriptionService.getCurrentToken());
        MovieList result = new MovieList();
        BeanUtils.copyProperties(raw, result);
        result.setList(new ArrayList<>(raw.getList()));
        MovieDetail decorated = new MovieDetail();
        BeanUtils.copyProperties(raw.getList().get(0), decorated);
        result.getList().set(0, decorated);
        liveFollowService.appendFollowTrack(decorated, uid);
        return result;
    }

    public Object play(String id) {
        Map<String, Object> result = new HashMap<>();
        //result.put("url", urls);
        //result.put("header", "{\"User-Agent\": \"" + Constants.USER_AGENT + "\"}");
        result.put("parse", 0);
        log.debug("{}", result);
        return result;
    }
}
