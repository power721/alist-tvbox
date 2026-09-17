package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.WatchlistDto;
import cn.har01d.alist_tvbox.entity.WatchlistItem;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.MediaSubscriptionService;
import cn.har01d.alist_tvbox.service.WatchlistService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 稍后再看管理接口(web,docs/watchlist-design.md):全部按 currentUid 隔离;
 * TVBox/WebHome 端走 /media t=want 与 /play watchadd-/watchdel-,不经此控制器。
 */
@RestController
@RequestMapping("/api/watchlist")
public class WatchlistController {
    private final WatchlistService watchlistService;
    private final MediaSubscriptionService mediaSubscriptionService;

    public WatchlistController(WatchlistService watchlistService,
                               MediaSubscriptionService mediaSubscriptionService) {
        this.watchlistService = watchlistService;
        this.mediaSubscriptionService = mediaSubscriptionService;
    }

    @GetMapping
    public List<WatchlistDto> list() {
        int uid = currentUid();
        var subscriptions = mediaSubscriptionService.subscriptionsOf(uid);
        return watchlistService.list(uid).stream()
                .map(item -> toDto(item, mediaSubscriptionService.isSubscribedTitle(uid,
                        displayName(item), subscriptions)))
                .toList();
    }

    @GetMapping("/exists")
    public Map<String, Boolean> exists(@RequestParam String vodId) {
        return Map.of("exists", watchlistService.exists(currentUid(), vodId));
    }

    /** 手动加入:vodId 缺省时按 标题[@年份] 组 s: 形态(标题匹配,详情可能不准,列表页有来源标注)。 */
    @PostMapping
    public Map<String, Object> add(@RequestBody Map<String, Object> body) {
        String title = StringUtils.trimToEmpty((String) body.get("title"));
        if (StringUtils.isBlank(title)) {
            throw new BadRequestException("标题不能为空");
        }
        String vodId = StringUtils.trimToEmpty((String) body.get("vodId"));
        if (StringUtils.isBlank(vodId)) {
            Integer year = parseInteger(body.get("year"));
            vodId = "s:" + title + (year != null ? "@" + year : "");
        }
        Integer season = parseInteger(body.get("season"));
        String payload = vodId + "|" + title + (season != null ? "|" + season : "");
        var result = watchlistService.add(currentUid(), payload);
        return Map.of("existed", result.existed(), "msg", result.msg());
    }

    @PatchMapping("/{id}/status")
    public Map<String, Boolean> updateStatus(@PathVariable int id, @RequestBody Map<String, String> body) {
        return Map.of("ok", watchlistService.updateStatus(currentUid(), id, body.get("status")));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable int id) {
        watchlistService.delete(currentUid(), id);
    }

    @PostMapping("/batch-delete")
    public void batchDelete(@RequestBody Map<String, List<Integer>> body) {
        List<Integer> ids = body.get("ids");
        if (ids != null && !ids.isEmpty()) {
            watchlistService.deleteAll(currentUid(), ids);
        }
    }

    private static String displayName(WatchlistItem item) {
        return item.getSeason() != null ? item.getTitle() + " 第" + item.getSeason() + "季" : item.getTitle();
    }

    private WatchlistDto toDto(WatchlistItem item, boolean subscribed) {
        WatchlistDto dto = new WatchlistDto();
        dto.setId(item.getId());
        dto.setVodId(item.getVodId());
        dto.setTitle(item.getTitle());
        dto.setYear(item.getYear());
        dto.setSeason(item.getSeason());
        // 快照可能存的是外站图床地址(TMDB 被墙/豆瓣防盗链),网页直连加载失败 —— 与片单榜单同口径包 /images 代理
        dto.setPic(mediaSubscriptionService.proxiedCover(item.getPic()));
        dto.setRemarks(item.getRemarks());
        dto.setStatus(item.getStatus());
        dto.setCreatedTime(item.getCreatedTime());
        dto.setSubscribed(subscribed);
        return dto;
    }

    private static Integer parseInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && StringUtils.isNumeric(text)) {
            return Integer.valueOf(text);
        }
        return null;
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
