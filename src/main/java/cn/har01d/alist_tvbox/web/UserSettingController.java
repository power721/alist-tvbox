package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.UserSettingDto;
import cn.har01d.alist_tvbox.entity.User;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import cn.har01d.alist_tvbox.service.SettingService;
import cn.har01d.alist_tvbox.service.UserService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 用户级设置(ADMIN/USER):白名单键(SettingService.USER_SETTING_KEYS)按登录用户读写,
 * 存 {key}:u{uid} 键级行,读取走 用户值→全局值 回退。全局值仍走 /api/settings(仅管理员)。
 */
@RestController
@RequestMapping("/api/user-settings")
public class UserSettingController {
    private final UserService userService;
    private final SettingService settingService;

    public UserSettingController(UserService userService, SettingService settingService) {
        this.userService = userService;
        this.settingService = settingService;
    }

    @GetMapping("/{name}")
    public UserSettingDto get(@PathVariable String name) {
        User user = currentUser();
        if (!SettingService.USER_SETTING_KEYS.contains(name)) {
            throw new BadRequestException("不支持用户级覆盖: " + name);
        }
        String value = settingService.getUserSetting(name, user.getId());
        boolean userLevel = settingService.hasUserSetting(name, user.getId());
        return new UserSettingDto(name, value, userLevel);
    }

    @PutMapping("/{name}")
    public Map<String, Object> save(@PathVariable String name, @RequestBody UserSettingDto dto) {
        User user = currentUser();
        settingService.saveUserSetting(name, user.getId(), dto == null ? null : dto.getValue());
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
