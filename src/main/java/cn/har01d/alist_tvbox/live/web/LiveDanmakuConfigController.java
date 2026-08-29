package cn.har01d.alist_tvbox.live.web;

import cn.har01d.alist_tvbox.dto.DanmakuConfig;
import cn.har01d.alist_tvbox.entity.User;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import cn.har01d.alist_tvbox.service.SettingService;
import cn.har01d.alist_tvbox.service.UserService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 用户级弹幕渲染配置(ADMIN/USER)。弹幕样式是个人观看偏好,各用户独立存储;
 * 未配置回落全局基线 danmaku_config。凭证与直播内容走 /live/danmaku 轮询接口按 token 归属下发。
 */
@RestController
@RequestMapping("/api/live/danmaku-config")
public class LiveDanmakuConfigController {
    private final UserService userService;
    private final SettingService settingService;

    public LiveDanmakuConfigController(UserService userService, SettingService settingService) {
        this.userService = userService;
        this.settingService = settingService;
    }

    @GetMapping
    public DanmakuConfig get() {
        return settingService.getDanmakuConfig(currentUser().getId());
    }

    @PutMapping
    public Map<String, Object> save(@RequestBody DanmakuConfig config) {
        settingService.saveDanmakuConfig(currentUser().getId(), config);
        return Map.of("success", true);
    }

    private User currentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getPrincipal().toString();
        User user = userService.findByUsername(username);
        if (user == null) {
            throw new NotFoundException("用户不存在");
        }
        return user;
    }
}
