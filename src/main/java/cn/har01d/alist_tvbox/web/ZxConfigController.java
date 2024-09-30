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
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/zx")
public class ZxConfigController {
    private final SubscriptionService subscriptionService;
    private final AccountRepository accountRepository;
    private final SettingRepository settingRepository;
    private final PanAccountRepository panAccountRepository;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public ZxConfigController(SubscriptionService subscriptionService,
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
        String remote = restTemplate.getForObject("https://gitlab.com/power0721/pg/-/raw/main/zx.version", String.class);
        String local = "";
        Path path = Path.of("/data/zx_version.txt");
        if (Files.exists(path)) {
            local = Files.readString(path);
        }
        return Map.of("local", local, "remote", remote);
    }

    @GetMapping("/config")
    public ObjectNode config(String token) throws IOException {
        subscriptionService.checkToken(token);

        String json = Files.readString(Path.of("/www/zx/peizhi.json"));

        ObjectNode objectNode = (ObjectNode) objectMapper.readTree(json);

        accountRepository.getFirstByMasterTrue().ifPresent(account -> {
            objectNode.put("aliToken", account.getRefreshToken());
        });
        panAccountRepository.findByTypeAndMasterTrue(DriverType.QUARK).stream().findFirst().ifPresent(share -> objectNode.put("quarkCookie", share.getCookie()));
        panAccountRepository.findByTypeAndMasterTrue(DriverType.PAN115).stream().findFirst().ifPresent(share -> objectNode.put("115Cookie", share.getCookie()));
        panAccountRepository.findByTypeAndMasterTrue(DriverType.UC).stream().findFirst().ifPresent(share -> objectNode.put("ucCookie", share.getCookie()));
        settingRepository.findById("delete_code_115").map(Setting::getValue).ifPresent(code -> objectNode.put("pwdRb115", code));

        Path path = Path.of("/data/zx.json");
        if (Files.exists(path)) {
            json = Files.readString(path);
            ObjectNode override = (ObjectNode) objectMapper.readTree(json);
            objectNode.setAll(override);
        }

        return objectNode;
    }
}
