package cn.har01d.alist_tvbox.live.web;

import cn.har01d.alist_tvbox.dto.LiveFollowDto;
import cn.har01d.alist_tvbox.live.service.LiveFollowService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * web 管理端的关注直播间接口(登录态鉴权,数据按当前用户隔离)。
 */
@RestController
@RequestMapping("/api/live/follows")
public class LiveFollowController {
    private final LiveFollowService liveFollowService;

    public LiveFollowController(LiveFollowService liveFollowService) {
        this.liveFollowService = liveFollowService;
    }

    @GetMapping
    public List<LiveFollowDto> list() {
        return liveFollowService.listDto(currentUid());
    }

    @PostMapping
    public Map<String, Object> follow(@RequestBody LiveFollowDto dto) {
        liveFollowService.follow(currentUid(), dto.getPlatform(), dto.getRoomId());
        return Map.of("success", true, "followed", true);
    }

    /** 通过官方直播间地址添加关注:平台/房间号解析与房间校验在后端完成,失败返回 400 与原因。 */
    @PostMapping("/url")
    public Map<String, Object> followByUrl(@RequestBody Map<String, String> body) {
        liveFollowService.followByUrl(currentUid(), body.get("url"));
        return Map.of("success", true, "followed", true);
    }

    @DeleteMapping
    public Map<String, Object> unfollow(@RequestParam String platform, @RequestParam String roomId) {
        boolean deleted = liveFollowService.unfollow(currentUid(), platform, roomId);
        return Map.of("success", true, "deleted", deleted);
    }

    private static int currentUid() {
        return (int) SecurityContextHolder.getContext().getAuthentication().getDetails();
    }
}
