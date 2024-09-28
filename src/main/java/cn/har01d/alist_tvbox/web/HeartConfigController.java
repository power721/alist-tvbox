package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.AccountRepository;
import cn.har01d.alist_tvbox.entity.PanAccountRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/heart")
public class HeartConfigController {
    private final SubscriptionService subscriptionService;
    private final AccountRepository accountRepository;
    private final SettingRepository settingRepository;
    private final PanAccountRepository panAccountRepository;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public HeartConfigController(SubscriptionService subscriptionService,
                                 AccountRepository accountRepository,
                                 SettingRepository settingRepository,
                                 PanAccountRepository panAccountRepository,
                                 ObjectMapper objectMapper,
                                 RestTemplateBuilder builder
    ) {
        this.subscriptionService = subscriptionService;
        this.accountRepository = accountRepository;
        this.settingRepository = settingRepository;
        this.panAccountRepository = panAccountRepository;
        this.objectMapper = objectMapper;
        this.restTemplate = builder.build();
    }

    @GetMapping("/version")
    public Object version() throws IOException {
        String remote = restTemplate.getForObject("https://gitlab.com/power0721/pg/-/raw/main/version1.txt", String.class);
        String local = "";
        Path path = Path.of("/data/heart_version.txt");
        if (Files.exists(path)) {
            local = Files.readString(path);
        }
        return Map.of("local", local, "remote", remote);
    }

    @GetMapping("/config")
    public Map<String, Object> config(String token) throws IOException {
        subscriptionService.checkToken(token);

        Map<String, Object> map = new HashMap<>();
        accountRepository.getFirstByMasterTrue().ifPresent(account -> {
            map.put("aliToken", account.getRefreshToken());
            //map.put("open_token", account.getOpenToken());
        });
        panAccountRepository.findByTypeAndMasterTrue(DriverType.QUARK).stream().findFirst().ifPresent(share -> map.put("quarkCookie", share.getCookie()));
        panAccountRepository.findByTypeAndMasterTrue(DriverType.PAN115).stream().findFirst().ifPresent(share -> map.put("115Cookie", share.getCookie()));
        panAccountRepository.findByTypeAndMasterTrue(DriverType.UC).stream().findFirst().ifPresent(share -> map.put("ucCookie", share.getCookie()));
        settingRepository.findById("delete_code_115").map(Setting::getValue).ifPresent(code -> map.put("pwdRb115", code));

        map.put("goServerUrl", "http://127.0.0.1:9966");
        map.put("ydAuth", "");
        map.put("proxy", "http://127.0.0.1:1072");

        Path path = Path.of("/data/heart.json");
        if (Files.exists(path)) {
            String json = Files.readString(path);
            Map<String, Object> override = (Map<String, Object>) objectMapper.readValue(json, Map.class);
            map.putAll(override);
        }

        return map;
    }
}
