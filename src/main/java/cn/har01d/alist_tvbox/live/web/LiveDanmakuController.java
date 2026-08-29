package cn.har01d.alist_tvbox.live.web;

import cn.har01d.alist_tvbox.dto.DanmakuConfig;
import cn.har01d.alist_tvbox.dto.LiveDanmaku;
import cn.har01d.alist_tvbox.entity.User;
import cn.har01d.alist_tvbox.live.danmaku.LiveDanmakuService;
import cn.har01d.alist_tvbox.service.SettingService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 直播弹幕轮询接口。客户端(TVBox spider)以 2 秒级间隔轮询,以返回的 next 作为下次 after 游标。
 */
@RestController
public class LiveDanmakuController {
    private final LiveDanmakuService liveDanmakuService;
    private final SubscriptionService subscriptionService;
    private final UserService userService;
    private final SettingService settingService;

    public LiveDanmakuController(LiveDanmakuService liveDanmakuService, SubscriptionService subscriptionService,
                                 UserService userService, SettingService settingService) {
        this.liveDanmakuService = liveDanmakuService;
        this.subscriptionService = subscriptionService;
        this.userService = userService;
        this.settingService = settingService;
    }

    @GetMapping("/live/danmaku")
    public Map<String, Object> danmaku(String platform, String roomId, Long after) {
        return danmaku("", platform, roomId, after);
    }

    @GetMapping("/live/danmaku/{token}")
    public Map<String, Object> danmaku(@PathVariable String token, String platform, String roomId, Long after) {
        subscriptionService.checkToken(token);

        DanmakuConfig config = resolveConfig(token);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("platform", platform);
        result.put("roomId", roomId);
        boolean supported = LiveDanmakuService.isSupported(platform) && roomId != null && !roomId.isEmpty();
        result.put("support", supported);
        // 渲染配置随每次轮询下发,客户端据此热更新;不支持的平台也下发,便于统一处理
        result.put("config", liveDanmakuService.resolvedConfig(config));
        if (!supported) {
            result.put("messages", List.of());
            result.put("next", after == null ? 0L : after);
            return result;
        }

        LiveDanmakuService.PollResult poll = liveDanmakuService.poll(platform, roomId, after, config);
        result.put("messages", poll.messages());
        result.put("next", poll.next());
        return result;
    }

    /** token → 用户级弹幕配置:凭证形态(u-{username}-{secret})验真或裸 u-{username} 归属;共享/无 token 回落全局。 */
    private DanmakuConfig resolveConfig(String token) {
        User user = userService.findUserByCredentialToken(token);
        if (user == null) {
            user = userService.findByUserVodToken(token);
        }
        return user == null ? settingService.getDanmakuConfig(0) : settingService.getDanmakuConfig(user.getId());
    }
}
