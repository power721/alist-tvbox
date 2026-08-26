package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.playback.PlaybackSyncPage;
import cn.har01d.alist_tvbox.dto.playback.PlaybackSyncInput;
import cn.har01d.alist_tvbox.dto.playback.PlaybackDeleteInput;
import cn.har01d.alist_tvbox.dto.playback.PlaybackTokenDto;
import cn.har01d.alist_tvbox.service.PlaybackSyncService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 多端播放记录同步入口。
 * <p>
 * 同步端点(/event、/events、/changes)为 {@code permitAll}:令牌即鉴权,
 * 由 {@link PlaybackSyncService#resolveUid(String)} 解析 uid(双源:playback_token ∪ session)。
 * 令牌管理端点(/tokens)走 session(Spring Security ADMIN|USER),uid 取自 SecurityContext。
 */
@RestController
public class PlaybackSyncController {
    private final PlaybackSyncService playbackSyncService;

    public PlaybackSyncController(PlaybackSyncService playbackSyncService) {
        this.playbackSyncService = playbackSyncService;
    }

    // ── PUSH(令牌鉴权) ───────────────────────────────────────────────────

    @PostMapping("/api/playback/event")
    public void event(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        PlaybackSyncService.TokenIdentity id = playbackSyncService.resolveIdentity(token(request));
        playbackSyncService.apply(id, body, null, idempotencyKey(request));
    }

    @PostMapping("/api/playback/events")
    public void events(HttpServletRequest request, @RequestBody List<Map<String, Object>> body) {
        PlaybackSyncService.TokenIdentity id = playbackSyncService.resolveIdentity(token(request));
        playbackSyncService.applyAll(id, body);
    }

    // ── PULL(令牌鉴权) ───────────────────────────────────────────────────

    @GetMapping("/api/playback/changes")
    public PlaybackSyncPage changes(HttpServletRequest request,
                                    @RequestHeader(value = "X-PlaySync-Since", required = false) String sinceHeader,
                                    @RequestHeader(value = "X-WebHTV-Since", required = false) String sinceHeaderLegacy,
                                    @RequestHeader(value = "X-PlaySync-Limit", required = false) Integer limit,
                                    @RequestHeader(value = "X-PlaySync-Source-Kind", required = false) String sourceKind,
                                    @RequestHeader(value = "X-PlaySync-Site-Key", required = false) String siteKeys,
                                    @RequestHeader(value = "X-PlaySync-Latest", required = false) Boolean latest) {
        PlaybackSyncService.TokenIdentity id = playbackSyncService.resolveIdentity(token(request));
        long since = parseLong(sinceHeader != null ? sinceHeader : sinceHeaderLegacy, 0L);
        return playbackSyncService.pull(id.uid(), id.syncScope(), since, limit == null ? 0 : limit, sourceKind, siteKeys,
                Boolean.TRUE.equals(latest));
    }

    // ── Fish/默影视 webhtv.playback.v1 兼容入口(与同步开关无关) ──────────────
    // 客户端在「Webhook 上报」与「远端同步」填同一地址 /api/playback/sync,用方法区分:
    // POST=上报(等同 /event),GET=拉取(等同 /changes,字段按 webhtv 兼容映射)。
    // 即使「播放记录同步」开关关闭,这两个入口仍可读写——开关只控制 spider 订阅注入。

    @PostMapping("/api/playback/sync")
    public void syncPush(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        PlaybackSyncService.TokenIdentity id = playbackSyncService.resolveIdentity(token(request));
        playbackSyncService.apply(id, body, null, idempotencyKey(request));
    }

    @GetMapping("/api/playback/sync")
    public PlaybackSyncPage syncPull(HttpServletRequest request,
                                     @RequestHeader(value = "X-WebHTV-Since", required = false) String sinceHeader,
                                     @RequestHeader(value = "X-PlaySync-Since", required = false) String sinceHeaderAlt,
                                     @RequestHeader(value = "X-WebHTV-Limit", required = false) Integer limitHeader,
                                     @RequestHeader(value = "X-PlaySync-Limit", required = false) Integer limitHeaderAlt) {
        PlaybackSyncService.TokenIdentity id = playbackSyncService.resolveIdentity(token(request));
        long since = parseLong(sinceHeader != null ? sinceHeader : sinceHeaderAlt, 0L);
        int limit = limitHeader != null ? limitHeader : (limitHeaderAlt != null ? limitHeaderAlt : 0);
        PlaybackSyncPage page = playbackSyncService.pull(id.uid(), id.syncScope(), since, limit, null, null, false);
        // webhtv 客户端逐条校验拉取的 upsert(siteKey/vodId/vodName/episodeName/positionMs>0/durationMs>0),
        // 任一不达标即判失败且不推进游标 → 死循环。服务端先行剔除,保证游标正常前进。
        page.getItems().removeIf(item -> !webhtvValid(item));
        return page;
    }

    /** 诊断接口：使用普通登录会话鉴权，分页返回当前用户的播放同步记录。 */
    @GetMapping("/api/playback/records")
    public Page<PlaybackSyncInput> records(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int pageSize,
            @RequestParam(required = false) Boolean webPlayable) {
        return playbackSyncService.list(currentUid(), page, pageSize, Boolean.TRUE.equals(webPlayable));
    }

    @GetMapping("/api/playback/records/-/item")
    public PlaybackSyncInput record(@RequestParam String sourceKind,
                                    @RequestParam String sourceKey,
                                    @RequestParam String vodId) {
        return playbackSyncService.getRecord(currentUid(), sourceKind, sourceKey, vodId);
    }

    /** 管理页面批量删除同步记录；删除会生成墓碑并下发到其他客户端。 */
    @PostMapping("/api/playback/records/-/delete")
    public void deleteRecords(@RequestBody List<PlaybackDeleteInput> records) {
        playbackSyncService.deleteRecords(currentUid(), records);
    }

    /** 管理页面清空当前用户的全部同步记录。 */
    @DeleteMapping("/api/playback/records")
    public void deleteAllRecords() {
        playbackSyncService.deleteAllRecords(currentUid());
    }

    /**
     * 管理页面按身份清除墓碑(支持 item/site/all 作用域),用于清理客户端 bug
     * 产生的误报墓碑。只清删除水位,不改动现存的播放记录。
     */
    @PostMapping("/api/playback/tombstones/-/delete")
    public Map<String, Integer> deleteTombstones(@RequestBody List<PlaybackDeleteInput> records) {
        return Map.of("removed", playbackSyncService.deleteTombstones(currentUid(), records));
    }

    // ── 令牌管理(session 鉴权,ADMIN|USER) ───────────────────────────────

    @GetMapping("/api/playback/tokens")
    public List<PlaybackTokenDto> listTokens() {
        return playbackSyncService.listTokens(currentUid());
    }

    @PostMapping("/api/playback/tokens")
    public PlaybackTokenDto createToken(@RequestBody Map<String, Object> body) {
        Object name = body == null ? null : body.get("name");
        return playbackSyncService.createToken(currentUid(), name == null ? null : String.valueOf(name));
    }

    @DeleteMapping("/api/playback/tokens/{id}")
    public void deleteToken(@PathVariable Integer id) {
        playbackSyncService.deleteToken(currentUid(), id);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static String token(HttpServletRequest request) {
        String t = request.getHeader("X-PlaySync-Token");
        if (isBlank(t)) {
            t = request.getHeader("X-WebHTV-Token");
        }
        if (isBlank(t)) {
            t = stripScheme(request.getHeader("Authorization"));
        }
        return t;
    }

    /** Authorization: Bearer &lt;token&gt; —— 去掉方案前缀,播放令牌与会话令牌都按裸值解析。 */
    private static String stripScheme(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return v.substring(7).trim();
        }
        return v;
    }

    private static String idempotencyKey(HttpServletRequest request) {
        String t = request.getHeader("Idempotency-Key");
        if (isBlank(t)) {
            t = request.getHeader("X-WebHTV-Dedupe-Key");
        }
        if (isBlank(t)) {
            t = request.getHeader("X-WebHTV-Webhook-Id");
        }
        return t;
    }

    private static int currentUid() {
        // 会话令牌路径 TokenFilter.setDetails(userId);Basic Auth 路径 details 是 WebAuthenticationDetails,
        // 直接强转会 ClassCastException 500 —— 回落 principal 解析(MyUserDetailsService 以 id 字符串作 username)
        var authentication = SecurityContextHolder.getContext().getAuthentication();
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

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static long parseLong(String s, long def) {
        if (isBlank(s)) {
            return def;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    /** webhtv 客户端对拉取 upsert 的必填/取值校验:不达标的记录在 /sync 响应中剔除,避免客户端死循环。 */
    private static boolean webhtvValid(PlaybackSyncInput item) {
        return item != null
                && item.getSourceKey() != null && !item.getSourceKey().isBlank()
                && item.getVodId() != null && !item.getVodId().isBlank()
                && item.getVodName() != null && !item.getVodName().isBlank()
                && item.getEpisodeName() != null && !item.getEpisodeName().isBlank()
                && item.getPositionMs() > 0
                && item.getDurationMs() > 0;
    }
}
