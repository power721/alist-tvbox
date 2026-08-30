package cn.har01d.alist_tvbox.live.web;

import cn.har01d.alist_tvbox.dto.PlatformCookieDto;
import cn.har01d.alist_tvbox.live.service.LivePlatformCookieService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * web 管理端的直播平台 cookie 配置(ADMIN,SecurityConfig 对 /api/** 默认仅管理员)。
 * 用户 cookie 优先于匿名身份,是平台风控后的自愈手段。
 */
@RestController
@RequestMapping("/api/live/cookies")
public class LiveCookieController {
    private static final Map<String, String[]> PLATFORM_META = Map.of(
            "douyin", new String[]{"抖音", "风控自愈:浏览器 F12 复制 ttwid 等整串 cookie"},
            "bili", new String[]{"B站", "登录态可提高接口配额,弹幕以登录身份进房"},
            "soop", new String[]{"SOOP", "登录后可观看受限直播间"});

    private final LivePlatformCookieService cookieService;

    public LiveCookieController(LivePlatformCookieService cookieService) {
        this.cookieService = cookieService;
    }

    @GetMapping
    public List<PlatformCookieDto> list() {
        List<PlatformCookieDto> result = new ArrayList<>();
        cookieService.cookieKeys().forEach((platform, key) -> {
            PlatformCookieDto dto = new PlatformCookieDto();
            dto.setPlatform(platform);
            String[] meta = PLATFORM_META.get(platform);
            dto.setName(meta == null ? platform : meta[0]);
            dto.setHint(meta == null ? "" : meta[1]);
            dto.setCookie(cookieService.getCookie(platform));
            result.add(dto);
        });
        return result;
    }

    @PutMapping
    public Map<String, Object> save(@RequestBody PlatformCookieDto dto) {
        cookieService.save(dto.getPlatform(), dto.getCookie());
        return Map.of("success", true);
    }

    @DeleteMapping
    public Map<String, Object> clear(@RequestParam String platform) {
        cookieService.save(platform, "");
        return Map.of("success", true);
    }

    /** 保存前验证 cookie 可用性,返回 valid 与说明(B站能校验登录账号)。 */
    @PostMapping("/verify")
    public Map<String, Object> verify(@RequestBody PlatformCookieDto dto) throws Exception {
        String[] result = cookieService.verify(dto.getPlatform(), dto.getCookie());
        return Map.of("valid", Boolean.parseBoolean(result[0]), "message", result[1]);
    }
}
