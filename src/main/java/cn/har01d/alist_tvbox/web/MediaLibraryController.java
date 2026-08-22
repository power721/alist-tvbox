package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.MediaSubscriptionService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * csp_Media「我的追剧」媒体库源端点(/media 与 /media/{token})。
 * <p>
 * 用户视角即一个媒体库:封面/元数据来自订阅绑定的豆瓣/TMDB 数据,播放链接由
 * {@link MediaSubscriptionService#playEpisode} 实时选源(转存 &gt; 主源 &gt; 补缺)并逐源回退,
 * 同一集多个分享源切换对用户无感。集 id 为逻辑 id(msubep-{subId}-{episode}),播放时才解析。
 */
@Slf4j
@RestController
public class MediaLibraryController {
    private final SubscriptionService subscriptionService;
    private final MediaSubscriptionService mediaSubscriptionService;

    public MediaLibraryController(SubscriptionService subscriptionService,
                                  MediaSubscriptionService mediaSubscriptionService) {
        this.subscriptionService = subscriptionService;
        this.mediaSubscriptionService = mediaSubscriptionService;
    }

    @GetMapping("/media")
    public Object browse(String id, String t, String ac, String wd, String title,
                         @RequestParam(required = false, defaultValue = "1") int pg) {
        return browse("", id, t, ac, wd, title, pg);
    }

    @GetMapping("/media/{token}")
    public Object browse(@PathVariable String token, String id, String t, String ac, String wd, String title,
                         @RequestParam(required = false, defaultValue = "1") int pg) {
        subscriptionService.checkToken(token);
        int uid = mediaSubscriptionService.resolveUid(token);
        if (StringUtils.isNotBlank(id)) {
            return detail(uid, id, ac, title);
        }
        if (StringUtils.isNotBlank(wd)) {
            return mediaSubscriptionService.contentList(uid, null, wd);
        }
        if (StringUtils.isNotBlank(t) && !"0".equals(t)) {
            return mediaSubscriptionService.contentList(uid, t, null);
        }
        if (StringUtils.isNotBlank(t)) { // t=0:首页直接展示全部
            return mediaSubscriptionService.contentList(uid);
        }
        return categories();
    }

    private Object detail(int uid, String id, String ac, String title) {
        if (!id.startsWith(MediaSubscriptionService.VOD_ID_PREFIX)) {
            throw new BadRequestException("无效的媒体条目: " + id);
        }
        String vid = id.substring(MediaSubscriptionService.VOD_ID_PREFIX.length());
        int subscriptionId;
        try {
            subscriptionId = Integer.parseInt(vid.split("\\$")[0].split("#")[0]);
        } catch (NumberFormatException e) {
            throw new BadRequestException("无效的媒体条目: " + id);
        }
        return mediaSubscriptionService.contentDetail(uid, subscriptionId, ac, title);
    }

    private CategoryList categories() {
        CategoryList result = new CategoryList();
        List<Category> categories = new ArrayList<>();
        categories.add(category("all", "全部"));
        categories.add(category("active", "追更中"));
        categories.add(category("ended", "已完结"));
        result.setCategories(categories);
        result.setTotal(categories.size());
        result.setLimit(categories.size());
        return result;
    }

    private Category category(String typeId, String typeName) {
        Category item = new Category();
        item.setType_id(typeId);
        item.setType_name(typeName);
        return item;
    }
}
