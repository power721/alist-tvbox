package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.MediaSubscriptionDto;
import cn.har01d.alist_tvbox.dto.MediaSubscriptionEventDto;
import cn.har01d.alist_tvbox.dto.MediaSubscriptionRequest;
import cn.har01d.alist_tvbox.dto.MediaSubscriptionResourceDto;
import cn.har01d.alist_tvbox.service.MediaSubscriptionCheckService;
import cn.har01d.alist_tvbox.service.MediaSubscriptionService;
import cn.har01d.alist_tvbox.service.MediaSubscriptionTransferService;
import cn.har01d.alist_tvbox.service.PianDanService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 追剧订阅(自动追更)管理接口。登录态鉴权(ADMIN/USER),数据按当前用户隔离。
 */
@RestController
@RequestMapping("/api/media-subscriptions")
public class MediaSubscriptionController {
    private final MediaSubscriptionService subscriptionService;
    private final MediaSubscriptionCheckService checkService;
    private final MediaSubscriptionTransferService transferService;
    private final PianDanService pianDanService;

    public MediaSubscriptionController(MediaSubscriptionService subscriptionService,
                                       MediaSubscriptionCheckService checkService,
                                       MediaSubscriptionTransferService transferService,
                                       PianDanService pianDanService) {
        this.subscriptionService = subscriptionService;
        this.checkService = checkService;
        this.transferService = transferService;
        this.pianDanService = pianDanService;
    }

    /** 片单追更:片单导航分类(豆瓣/TMDB 榜单与筛选定义,排除电影类目——追更只对剧集/综艺有意义)。管理端代理,走登录态鉴权,免 vod token。 */
    @GetMapping("/navigation")
    public Object navigationCategories() {
        return pianDanService.subscriptionCategory();
    }

    /** 片单追更:分类条目列表。ac 固定 web(豆瓣封面走 /images 代理防盗链)。 */
    @GetMapping("/navigation/list")
    public Object navigationList(String t,
                                 @RequestParam(required = false, defaultValue = "1") int pg,
                                 @RequestParam(required = false, defaultValue = "24") int size,
                                 @RequestParam Map<String, String> filters) {
        return pianDanService.list(t, "web", pg, size, filters);
    }

    @GetMapping
    public List<MediaSubscriptionDto> list() {
        return subscriptionService.list(currentUid());
    }

    @PostMapping
    public MediaSubscriptionDto create(@RequestBody MediaSubscriptionRequest request) {
        MediaSubscriptionDto dto = subscriptionService.create(currentUid(), request);
        checkService.checkAsync(currentUid(), dto.getId());
        return dto;
    }

    @PostMapping("/{id}")
    public MediaSubscriptionDto update(@PathVariable int id, @RequestBody MediaSubscriptionRequest request) {
        return subscriptionService.update(currentUid(), id, request);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable int id) {
        subscriptionService.delete(currentUid(), id);
        return Map.of("success", true);
    }

    @PostMapping("/{id}/check")
    public Map<String, Object> check(@PathVariable int id) {
        checkService.checkAsync(currentUid(), id);
        return Map.of("started", true);
    }

    /** 详情页"刷新元数据":穿透缓存直取外网,重写订阅快照与 media_metadata 表(异步)。 */
    @PostMapping("/{id}/refresh-meta")
    public Map<String, Object> refreshMeta(@PathVariable int id) {
        checkService.refreshMetadataAsync(currentUid(), id);
        return Map.of("started", true);
    }

    /** 详情页"检查更新"(轻量):刷新元数据后官方已播 vs 本地已有的结论进事件流,不做资源搜索/挂载。 */
    @PostMapping("/{id}/check-update")
    public Map<String, Object> checkUpdate(@PathVariable int id) {
        checkService.checkUpdateAsync(currentUid(), id);
        return Map.of("started", true);
    }

    @PostMapping("/{id}/pause")
    public MediaSubscriptionDto pause(@PathVariable int id) {
        return subscriptionService.pause(currentUid(), id);
    }

    @PostMapping("/{id}/resume")
    public MediaSubscriptionDto resume(@PathVariable int id) {
        return subscriptionService.resume(currentUid(), id);
    }

    @GetMapping("/{id}/events")
    public List<MediaSubscriptionEventDto> events(@PathVariable int id) {
        return subscriptionService.events(currentUid(), id);
    }

    @GetMapping("/{id}/resources")
    public List<MediaSubscriptionResourceDto> resources(@PathVariable int id) {
        return subscriptionService.resources(currentUid(), id);
    }

    @PostMapping("/{id}/resources/{resourceId}/activate")
    public Map<String, Object> activate(@PathVariable int id, @PathVariable int resourceId) {
        checkService.activateAsync(currentUid(), id, resourceId);
        return Map.of("started", true);
    }

    /** 钉选资源为主源:立即挂载,自动换源不再覆盖(归属复核豁免、候选序置顶)。 */
    @PostMapping("/{id}/resources/{resourceId}/pin")
    public Map<String, Object> pin(@PathVariable int id, @PathVariable int resourceId) {
        checkService.pinAsync(currentUid(), id, resourceId);
        return Map.of("started", true);
    }

    /** 取消钉选:清除标记,当前挂载不动,自动换源恢复。 */
    @PostMapping("/{id}/resources/{resourceId}/unpin")
    public Map<String, Object> unpin(@PathVariable int id, @PathVariable int resourceId) {
        checkService.unpinAsync(currentUid(), id, resourceId);
        return Map.of("success", true);
    }

    /** 资源级起始集号:该资源第 1 集对应全剧第 N 集(body {startEpisode},0/缺省=清除)。
     *  季包资源(完结季裸 1-8 实为全剧 153-160)混进连续编号订阅时手动对齐;改动后该资源集源行重扫。 */
    @PostMapping("/{id}/resources/{resourceId}/episode-start")
    public Map<String, Object> setEpisodeStart(@PathVariable int id, @PathVariable int resourceId,
                                               @RequestBody(required = false) Map<String, Integer> body) {
        subscriptionService.setResourceEpisodeStart(currentUid(), id, resourceId,
                body == null ? null : body.get("startEpisode"));
        return Map.of("success", true);
    }

    /** 手动转存增量(TRANSFER 模式)。 */
    @PostMapping("/{id}/transfer")
    public Map<String, Object> transfer(@PathVariable int id) {
        transferService.transferAsync(currentUid(), id);
        return Map.of("started", true);
    }

    @GetMapping("/{id}/transfer/progress")
    public Map<String, Object> transferProgress(@PathVariable int id) {
        return transferService.progress(currentUid(), id);
    }

    /** 多季联动:检查是否有下一季可订阅(前端一键开订)。 */
    @GetMapping("/{id}/next-season")
    public Map<String, Object> nextSeason(@PathVariable int id) {
        return subscriptionService.nextSeason(currentUid(), id);
    }

    /** 播出时间轴:昨天 → 未来 7 天,每天更新的订阅与播出时间。 */
    @GetMapping("/schedule")
    public List<Map<String, Object>> schedule() {
        return subscriptionService.schedule(currentUid());
    }

    @GetMapping("/meta/resolve")
    public Map<String, Object> resolveMetaLink(@RequestParam String url) {
        return subscriptionService.resolveMetaLink(url);
    }

    @GetMapping("/meta/search")
    public Map<String, Object> metaSearch(String keyword, String provider) {
        return subscriptionService.metaSearch(provider, keyword);
    }

    /** dry-run 预览(§10.2):即时搜索,返回候选 + 打分明细,不落库。 */
    @PostMapping("/preview")
    public List<Map<String, Object>> preview(@RequestBody MediaSubscriptionRequest request) {
        String keyword = StringUtils.defaultIfBlank(request.getKeyword(), request.getName());
        if (StringUtils.isBlank(keyword)) {
            return List.of();
        }
        return checkService.preview(keyword, request.getSeason(), request.getFilter());
    }

    /** 更新收件箱:近 3 天全部订阅的新集/换源/补缺事件。 */
    @GetMapping("/inbox")
    public List<Map<String, Object>> inbox() {
        return subscriptionService.inbox(currentUid());
    }

    /** 集数清单:每集是否已有、来源(主源/补缺)。 */
    @GetMapping("/{id}/episodes")
    public List<Map<String, Object>> episodes(@PathVariable int id) {
        return subscriptionService.episodes(currentUid(), id);
    }

    /** 媒体详情:元数据快照(名称/年份/状态/简介)+ 分集列表(标题/播出时间/剧照 + 本地是否已有)。零网络。 */
    @GetMapping("/{id}/detail")
    public Map<String, Object> detail(@PathVariable int id) {
        return subscriptionService.detail(currentUid(), id);
    }

    @GetMapping("/export")
    public List<Map<String, Object>> export() {
        return subscriptionService.export(currentUid());
    }

    @PostMapping("/import")
    public Map<String, Object> importSubscriptions(@RequestBody List<MediaSubscriptionRequest> requests) {
        return subscriptionService.importSubscriptions(currentUid(), requests);
    }

    /** 批量操作(§10.5):check/pause/resume/delete。 */
    @PostMapping("/batch")
    public Map<String, Object> batch(@RequestBody Map<String, Object> body) {
        String action = String.valueOf(body.get("action"));
        @SuppressWarnings("unchecked")
        List<Number> ids = (List<Number>) body.getOrDefault("ids", List.of());
        int affected = 0;
        for (Number idValue : ids) {
            int id = idValue.intValue();
            try {
                switch (action) {
                    case "check" -> {
                        checkService.checkAsync(currentUid(), id);
                        affected++;
                    }
                    case "pause" -> {
                        subscriptionService.pause(currentUid(), id);
                        affected++;
                    }
                    case "resume" -> {
                        subscriptionService.resume(currentUid(), id);
                        affected++;
                    }
                    case "delete" -> {
                        subscriptionService.delete(currentUid(), id);
                        affected++;
                    }
                    default -> throw new IllegalArgumentException("未知操作: " + action);
                }
            } catch (Exception e) {
                // 单条失败不阻断批量
            }
        }
        return Map.of("success", true, "affected", affected);
    }

    /** 健康面板统计(§10.5):各状态数量 + 今日新集事件数。 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return subscriptionService.stats(currentUid());
    }

    /** web 搜索页"追更"按钮:当前资源直接成为主源(订阅即所见)。 */
    @PostMapping("/follow")
    public Map<String, Object> follow(@RequestBody Map<String, Object> body) {
        return subscriptionService.handleAction(currentUid(), "follow", body);
    }

    @GetMapping("/preference")
    public Map<String, Object> getPreference() {
        return Map.of("config", subscriptionService.getPreference(currentUid()));
    }

    @PostMapping("/preference")
    public Map<String, Object> savePreference(@RequestBody Map<String, String> body) {
        String config = body.get("config");
        return Map.of("config", subscriptionService.savePreference(currentUid(), config));
    }

    private static int currentUid() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        // 会话令牌路径 TokenFilter.setDetails(userId);Basic Auth 路径 details 是 WebAuthenticationDetails,
        // 直接强转会 ClassCastException 500 —— 回落 principal 解析(MyUserDetailsService 以 id 字符串作 username)
        if (authentication.getDetails() instanceof Integer userId) {
            return userId;
        }
        if (authentication.getPrincipal() instanceof org.springframework.security.core.userdetails.User user) {
            try {
                return Integer.parseInt(user.getUsername());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }
}
