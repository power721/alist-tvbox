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
        int uid = playbackSyncService.resolveUid(token(request));
        playbackSyncService.apply(uid, body, null, idempotencyKey(request));
    }

    @PostMapping("/api/playback/events")
    public void events(HttpServletRequest request, @RequestBody List<Map<String, Object>> body) {
        int uid = playbackSyncService.resolveUid(token(request));
        playbackSyncService.applyAll(uid, body);
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
        int uid = playbackSyncService.resolveUid(token(request));
        long since = parseLong(sinceHeader != null ? sinceHeader : sinceHeaderLegacy, 0L);
        return playbackSyncService.pull(uid, since, limit == null ? 0 : limit, sourceKind, siteKeys,
                Boolean.TRUE.equals(latest));
    }

    /** 诊断接口：使用普通登录会话鉴权，分页返回当前用户的播放同步记录。 */
    @GetMapping("/api/playback/records")
    public Page<PlaybackSyncInput> records(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int pageSize) {
        return playbackSyncService.list(currentUid(), page, pageSize);
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
        return (int) SecurityContextHolder.getContext().getAuthentication().getDetails();
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
}
