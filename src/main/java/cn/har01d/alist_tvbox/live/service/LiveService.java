package cn.har01d.alist_tvbox.live.service;

import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

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
    private final List<LivePlatform> platforms = new ArrayList<>();
    private final Cache<String, MovieList> cache = Caffeine.newBuilder()
            .maximumSize(20)
            .expireAfterWrite(Duration.ofMinutes(15))
            .build();
    private final HuyaService huyaService;
    private final LiveFollowService liveFollowService;
    private final SubscriptionService subscriptionService;

    public LiveService(HuyaService huyaService, DouyuService douyuService, BilibiliService bilibiliService, CcService ccService, KuaishouService kuaishouService, DouyinService douyinService, LiveFollowService liveFollowService, SubscriptionService subscriptionService) {
        this.huyaService = huyaService;
        platforms.add(huyaService);
        platforms.add(douyuService);
        platforms.add(bilibiliService);
        platforms.add(ccService);
        platforms.add(kuaishouService);
        platforms.add(douyinService);
        this.liveFollowService = liveFollowService;
        this.subscriptionService = subscriptionService;
    }

    public MovieList home() throws IOException {
        MovieList result = huyaService.home();
        // 首页推荐前插入关注的直播间(开播优先)
        int uid = liveFollowService.resolveUid(subscriptionService.getCurrentToken());
        MovieList follows = liveFollowService.list(uid);
        if (!follows.getList().isEmpty()) {
            List<MovieDetail> list = new ArrayList<>(follows.getList());
            list.addAll(result.getList());
            result.setList(list);
            result.setTotal(list.size());
            result.setLimit(list.size());
        }
        return result;
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
                    return platform.list(id, ac, sort, pg);
                }
            }
        } else {
            var temp = cache.getIfPresent(id);
            if (temp != null) {
                return temp;
            }

            for (LivePlatform platform : platforms) {
                if (platform.getType().equals(id)) {
                    var categoryList = platform.category();
                    List<MovieDetail> list = new ArrayList<>();
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
                    cache.put(id, result);
                    return result;
                }
            }
        }
        return result;
    }

    public MovieList search(String wd) throws IOException {
        MovieList result = new MovieList();
        // TODO:
        return result;
    }

    public MovieList detail(String tid, String client) throws IOException {
        MovieList result = cache.getIfPresent(tid);
        if (result == null) {
            result = new MovieList();
            String[] parts = tid.split("\\$");
            for (LivePlatform platform : platforms) {
                if (platform.getType().equals(parts[0])) {
                    result = platform.detail(tid, client);
                    if (!result.getList().isEmpty()) {
                        result.getList().get(0).setVod_director(platform.getName());
                    }
                    cache.put(tid, result);
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
