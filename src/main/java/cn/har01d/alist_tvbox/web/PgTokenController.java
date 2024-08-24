package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.AccountRepository;
import cn.har01d.alist_tvbox.entity.PikPakAccountRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.ShareRepository;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/pg")
public class PgTokenController {
    private final SubscriptionService subscriptionService;
    private final AccountRepository accountRepository;
    private final SettingRepository settingRepository;
    private final ShareRepository shareRepository;
    private final PikPakAccountRepository pikPakAccountRepository;
    private final ObjectMapper objectMapper;

    public PgTokenController(SubscriptionService subscriptionService, AccountRepository accountRepository, SettingRepository settingRepository, ShareRepository shareRepository, PikPakAccountRepository pikPakAccountRepository, ObjectMapper objectMapper) {
        this.subscriptionService = subscriptionService;
        this.accountRepository = accountRepository;
        this.settingRepository = settingRepository;
        this.shareRepository = shareRepository;
        this.pikPakAccountRepository = pikPakAccountRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/lib/tokenm")
    public Map<String, Object> tokenm(String token) throws IOException {
        subscriptionService.checkToken(token);

        Map<String, Object> map = new HashMap<>();
        map.put("token", "");
        map.put("open_token", "");
        accountRepository.getFirstByMasterTrue().ifPresent(account -> {
            map.put("token", account.getRefreshToken());
            map.put("open_token", account.getOpenToken());
        });
        map.put("thread_limit", 32);
        map.put("is_vip", false);
        map.put("vip_thread_limit", 32);
        map.put("quark_thread_limit", 32);
        map.put("quark_vip_thread_limit", 32);
        //map.put("quark_is_vip", false);
        map.put("quark_is_guest", false);
        map.put("vod_flags", "4kz|auto");
        map.put("quark_flags", "4kz|auto");
        map.put("uc_thread_limit", 0);
        map.put("uc_is_vip", false);
        map.put("uc_flags", "4kz|auto");
        map.put("uc_vip_thread_limit", 0);
        map.put("thunder_thread_limit", 0);
        map.put("thunder_is_vip", false);
        map.put("thunder_vip_thread_limit", 0);
        map.put("thunder_flags", "4k|4kz|auto");
        map.put("aliproxy", "");
        map.put("proxy", "");
        map.put("open_api_url", settingRepository.findById("open_token_url").map(Setting::getValue).orElse("https://api.xhofe.top/alist/ali_open/token"));
        map.put("danmu", true);
        map.put("quark_danmu", true);
        map.put("quark_cookie", "");
        map.put("pan115_cookie", "");
        map.put("uc_cookie", "");
        shareRepository.findByType(2).stream().findFirst().ifPresent(share -> map.put("quark_cookie", share.getCookie()));
        shareRepository.findByType(3).stream().findFirst().ifPresent(share -> map.put("pan115_cookie", share.getCookie()));
        shareRepository.findByType(6).stream().findFirst().ifPresent(share -> map.put("uc_cookie", share.getCookie()));
        map.put("pan115_thread_limit", 0);
        map.put("pan115_vip_thread_limit", 0);
        map.put("pan115_is_vip", false);
        map.put("pan115_flags", "4kz");
        map.put("pan115_auto_delete", true);
        map.put("pan115_delete_code", "");
        map.put("thunder_username", "");
        map.put("thunder_password", "");
        map.put("thunder_captchatoken", "");
        map.put("pikpak_username", "");
        map.put("pikpak_password", "");
        pikPakAccountRepository.getFirstByMasterTrue().ifPresent(account -> {
            map.put("pikpak_username", account.getUsername());
            map.put("pikpak_password", account.getPassword());
        });
        map.put("pikpak_flags", "4k|auto");
        map.put("pikpak_thread_limit", 2);
        map.put("pikpak_vip_thread_limit", 2);
        map.put("pikpak_proxy", "");
        map.put("pikpak_proxy_onlyapi", false);

        Path path = Path.of("/data/tokenm.json");
        if (Files.exists(path)) {
            String json = Files.readString(path);
            Map<String, Object> override = objectMapper.readValue(json, Map.class);
            for (Map.Entry<String, Object> entry : override.entrySet()) {
                map.put(entry.getKey(), entry.getValue());
            }
        }

        return map;
    }
}
