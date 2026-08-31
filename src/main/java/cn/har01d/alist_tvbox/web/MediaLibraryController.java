package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.MediaSubscriptionService;
import cn.har01d.alist_tvbox.service.PianDanService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import com.qq.tars.common.util.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * csp_Media「我的追剧」媒体库源端点(/media 与 /media/{token})。
 * <p>
 * 用户视角即一个媒体库:封面/元数据来自订阅绑定的豆瓣/TMDB 数据,播放链接由
 * {@link MediaSubscriptionService#playEpisode} 实时选源(转存 &gt; 主源 &gt; 补缺)并逐源回退,
 * 同一集多个分享源切换对用户无感。集 id 为逻辑 id(msubep-{subId}-{episode}),播放时才解析。
 * <p>
 * 分类在 最近更新/连载中/已完结/全部 之外合并片单追更分类(豆瓣/TMDB 榜单,复用 web 片单追更的
 * {@link PianDanService#subscriptionCategory}):片单条目详情带一条「➕ 加入追剧」伪播放线路,
 * 点击经 /play 的 msubadd-{vodId} 一键建订阅(电视端闭环,无需开 web)。
 * <p>
 * 站内搜索(wd)同为闭环入口:已订阅命中在前,片单 TMDB 全库搜索(multi)在后 ——
 * 未追的剧/电影也能搜到,详情同样带「加入追剧」。
 */
@Slf4j
@RestController
public class MediaLibraryController {
    /** browse 已占用的保留参数,其余 query 参数透传为片单筛选(TVBox categoryContent extend 同名平铺)。 */
    private static final Set<String> RESERVED_PARAMS = Set.of("id", "t", "pg", "ac", "wd", "title");

    private final SubscriptionService subscriptionService;
    private final MediaSubscriptionService mediaSubscriptionService;
    private final PianDanService pianDanService;

    public MediaLibraryController(SubscriptionService subscriptionService,
                                  MediaSubscriptionService mediaSubscriptionService,
                                  PianDanService pianDanService) {
        this.subscriptionService = subscriptionService;
        this.mediaSubscriptionService = mediaSubscriptionService;
        this.pianDanService = pianDanService;
    }

    @GetMapping("/media")
    public Object browse(String id, String t, String ac, String wd, String title,
                         @RequestParam(required = false, defaultValue = "1") int pg,
                         @RequestParam Map<String, String> params) {
        return browse("", id, t, ac, wd, title, pg, params);
    }

    @GetMapping("/media/{token}")
    public Object browse(@PathVariable String token, String id, String t, String ac, String wd, String title,
                         @RequestParam(required = false, defaultValue = "1") int pg,
                         @RequestParam Map<String, String> params) {
        subscriptionService.checkToken(token);
        int uid = mediaSubscriptionService.resolveUid(token);
        if (StringUtils.isNotBlank(id)) {
            if (isPianDanId(id)) {
                return pianDanDetail(uid, id);
            }
            return detail(uid, id, ac, title);
        }
        if (StringUtils.isNotBlank(wd)) {
            return searchAll(uid, wd, pg);
        }
        if (isPianDanId(t)) {
            return pianDanList(uid, t, pg, params);
        }
        if (StringUtils.isNotBlank(t) && !"0".equals(t)) {
            return mediaSubscriptionService.contentList(uid, t, null);
        }
        if (StringUtils.isNotBlank(t)) { // t=0:首页直接展示全部
            return mediaSubscriptionService.contentList(uid);
        }
        return categories();
    }

    private static boolean isPianDanId(String value) {
        return value != null && (value.startsWith(PianDanService.DOUBAN_PREFIX) || value.startsWith(PianDanService.TMDB_PREFIX)
                || value.startsWith("s:"));
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

    /** 站内搜索:已订阅命中在前 + 片单 TMDB 全库(multi)在后 —— 没追过的剧/电影也能搜到,
     *  点进详情即片单条目(带「➕ 加入追剧」)。翻页只翻 TMDB 侧(已订阅命中固定在第一页);
     *  TMDB 结果里已追的带「已追」角标(与片单分类列表同口径),封面统一重建客户端可用地址。 */
    private MovieList searchAll(int uid, String wd, int pg) {
        List<MovieDetail> merged = new ArrayList<>();
        if (pg <= 1) {
            merged.addAll(mediaSubscriptionService.contentList(uid, null, wd).getList());
        }
        MovieList tmdb = pianDanService.search(wd, pg, 24);
        for (MovieDetail item : tmdb.getList()) {
            item.setVod_pic(mediaSubscriptionService.absoluteClientCover(item.getVod_pic()));
            if (mediaSubscriptionService.isSubscribedTitle(uid, item.getVod_name())) {
                item.setVod_remarks("已追 " + StringUtils.defaultString(item.getVod_remarks()));
            }
            merged.add(item);
        }
        MovieList result = new MovieList();
        result.setList(merged);
        result.setPage(tmdb.getPage());
        result.setPagecount(tmdb.getPagecount());
        result.setTotal(tmdb.getTotal() + (pg <= 1 ? merged.size() - tmdb.getList().size() : 0));
        result.setLimit(merged.size());
        return result;
    }

    /** 片单分类条目列表:ac=web 走豆瓣封面代理,再统一重建客户端可用绝对地址;已订阅条目带「已追」角标。 */
    private Object pianDanList(int uid, String type, int pg, Map<String, String> params) {
        Map<String, String> filters = new java.util.HashMap<>();
        params.forEach((key, value) -> {
            if (!RESERVED_PARAMS.contains(key) && StringUtils.isNotBlank(value)) {
                filters.put(key, value);
            }
        });
        MovieList result = pianDanService.list(type, "web", pg, 24, filters);
        for (MovieDetail item : result.getList()) {
            item.setVod_pic(mediaSubscriptionService.absoluteClientCover(item.getVod_pic()));
            if (mediaSubscriptionService.isSubscribedTitle(uid, item.getVod_name())) {
                item.setVod_remarks("已追 " + StringUtils.defaultString(item.getVod_remarks()));
            }
        }
        return result;
    }

    /** 片单条目详情:元数据直取(TMDB)/豆瓣条目本地库唯一匹配富化(无匹配回落仅标题),带「加入追剧」伪播放线路。 */
    /** 片单详情就地改写 pic/remarks/play 字段,tmdbDetail 命中短缓存返回共享实例 —— 拷贝再装配,防缓存被污染。 */
    private static MovieDetail copyDetail(MovieDetail source) {
        if (source == null) {
            return null;
        }
        MovieDetail copy = new MovieDetail();
        copy.setVod_id(source.getVod_id());
        copy.setVod_name(source.getVod_name());
        copy.setVod_pic(source.getVod_pic());
        copy.setVod_year(source.getVod_year());
        copy.setVod_actor(source.getVod_actor());
        copy.setVod_content(source.getVod_content());
        copy.setType_name(source.getType_name());
        copy.setVod_remarks(source.getVod_remarks());
        copy.setExt(source.getExt());
        return copy;
    }

    private Object pianDanDetail(int uid, String id) {
        MovieDetail detail;
        if (id.startsWith(PianDanService.TMDB_PREFIX)) {
            String[] parts = id.split(":");
            if (parts.length < 3) {
                throw new BadRequestException("无效的片单条目: " + id);
            }
            try {
                detail = copyDetail(pianDanService.tmdbDetail(parts[1], Integer.parseInt(parts[2])));
            } catch (NumberFormatException e) {
                throw new BadRequestException("无效的片单条目: " + id);
            }
            if (detail == null) {
                throw new BadRequestException("片单条目信息获取失败: " + id);
            }
        } else if (id.startsWith("s:")) {
            // 豆瓣片单条目无 subject id:名称(+vod_id 内嵌年份)在本地豆瓣库严格唯一匹配,命中返回富详情
            PianDanService.NameYear entry = PianDanService.parseSubjectId(id);
            detail = mediaSubscriptionService.localDoubanDetail(entry.name(), entry.year());
            if (detail == null) {
                detail = new MovieDetail();
                detail.setVod_name(entry.name());
                detail.setVod_content("来自豆瓣片单。点击「加入追剧」按标题订阅。");
            }
            if (StringUtils.isBlank(detail.getVod_content())) {
                detail.setVod_content("来自豆瓣片单。点击「加入追剧」按标题订阅。");
            }
            detail.setVod_id(id);
        } else {
            throw new BadRequestException("无效的片单条目: " + id);
        }
        boolean subscribed = mediaSubscriptionService.isSubscribedTitle(uid, detail.getVod_name());
        detail.setVod_pic(mediaSubscriptionService.absoluteClientCover(detail.getVod_pic()));
        if (subscribed) {
            detail.setVod_remarks("已追 " + StringUtils.defaultString(detail.getVod_remarks()));
        }
        detail.setVod_play_from("片单");
        // 爬虫端拼 GET 参数不编码,标题里的空格/&/# 会截断请求 —— vodId 先按 form 编码;
        // OkHttp/服务端各解一次百分号序列后,PlayController 收到的即是原始 vodId。
        // play id 载荷 {vodId}|{剧名}|{季?}:剧名让订阅/取消零网络,季号让多季剧精确到「追剧·第N季」。
        // 第一条目固定是无副作用的「媒体信息」:部分播放器内核进详情会自动触发第一集,首条不能是订阅动作
        String payload = id + "|" + detail.getVod_name();
        String encoded = java.net.URLEncoder.encode(payload, java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder playUrl = new StringBuilder("📄 媒体信息$")
                .append(MediaSubscriptionService.INFO_PLAY_PREFIX).append(encoded);
        if (detail.getExt() instanceof List<?> seasons && !seasons.isEmpty()) {
            // 多季剧按季展开:每季一条目(已追季为取消项),单点直达「追剧·第5季」
            for (Object season : seasons) {
                int number = ((Number) season).intValue();
                String seasonPayload = payload + "|" + number;
                String seasonEncoded = java.net.URLEncoder.encode(seasonPayload, java.nio.charset.StandardCharsets.UTF_8);
                String seasonTitle = detail.getVod_name() + " 第" + number + "季";
                if (mediaSubscriptionService.isSubscribedTitle(uid, seasonTitle)) {
                    playUrl.append("#➖ 取消·第").append(number).append("季$")
                            .append(MediaSubscriptionService.UNSUBSCRIBE_PLAY_PREFIX).append(seasonEncoded);
                } else {
                    playUrl.append("#➕ 追剧·第").append(number).append("季$")
                            .append(MediaSubscriptionService.SUBSCRIBE_PLAY_PREFIX).append(seasonEncoded);
                }
            }
        } else if (subscribed) {
            playUrl.append("#➖ 取消追剧$")
                    .append(MediaSubscriptionService.UNSUBSCRIBE_PLAY_PREFIX).append(encoded);
        } else {
            playUrl.append("#➕ 加入追剧$")
                    .append(MediaSubscriptionService.SUBSCRIBE_PLAY_PREFIX).append(encoded);
        }
        detail.setVod_play_url(playUrl.toString());
        detail.setExt(null); // 季号清单只用于装配,不出响应
        MovieList result = new MovieList();
        result.getList().add(detail);
        result.setTotal(1);
        result.setLimit(1);
        log.debug("detail: {} {}", id, detail);
        return result;
    }

    private CategoryList categories() {
        CategoryList result = new CategoryList();
        List<Category> categories = new ArrayList<>();
        categories.add(category("recent", "最近更新"));
        categories.add(category("active", "连载中"));
        categories.add(category("ended", "已完结"));
        categories.add(category("all", "全部订阅"));
        // 片单追更分类(豆瓣/TMDB 榜单+筛选,排除电影类目):type_id 带 douban:/tmdb: 前缀,与上方短 id 不撞车
        CategoryList pianDan = pianDanService.subscriptionCategory();
        categories.addAll(pianDan.getCategories());
        result.setCategories(categories);
        result.getFilters().putAll(pianDan.getFilters());
        result.setTotal(categories.size());
        result.setLimit(categories.size());
        return result;
    }

    private Category category(String typeId, String typeName) {
        Category item = new Category();
        item.setType_id(typeId);
        item.setType_name(typeName);
        item.setType_flag(0);
        return item;
    }
}
