package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.*;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.util.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cn.har01d.alist_tvbox.util.Utils;
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
import java.util.regex.Pattern;

@Slf4j
@RestController
@RequestMapping("/pg")
public class PgTokenController {
    private static final Pattern PG_PATTERN = Pattern.compile("https://raw.githubusercontent.com/fish2018/PG/main/pg.(\\d+-\\d+).zip");
    private final SubscriptionService subscriptionService;
    private final AccountRepository accountRepository;
    private final SettingRepository settingRepository;
    private final DriverAccountRepository driverAccountRepository;
    private final PikPakAccountRepository pikPakAccountRepository;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;

    public PgTokenController(SubscriptionService subscriptionService,
                             AccountRepository accountRepository,
                             SettingRepository settingRepository,
                             DriverAccountRepository driverAccountRepository,
                             PikPakAccountRepository pikPakAccountRepository,
                             ObjectMapper objectMapper,
                             RestTemplateBuilder builder,
                             AppProperties appProperties) {
        this.subscriptionService = subscriptionService;
        this.accountRepository = accountRepository;
        this.settingRepository = settingRepository;
        this.driverAccountRepository = driverAccountRepository;
        this.pikPakAccountRepository = pikPakAccountRepository;
        this.objectMapper = objectMapper;
        this.restTemplate = builder.build();
        this.appProperties = appProperties;
    }

    @GetMapping("/version")
    public Object version() throws IOException {
        String remote = getPgVersion();
        String local = "";
        Path path = Utils.getDataPath("pg_version.txt");
        if (Files.exists(path)) {
            local = Files.readString(path);
        }
        return Map.of("local", local, "remote", remote);
    }

    private String getPgVersion() throws IOException {
        String html = restTemplate.getForObject("https://github.com/fish2018/PG", String.class);
        int index = html.indexOf("PG包下载地址");
        if (index > 0) {
            html = html.substring(index);
        }
        var m = PG_PATTERN.matcher(html);
        if (m.find()) {
            return m.group(1);
        }
        return "";
    }

    @GetMapping("/lib/tokenm")
    public ObjectNode tokenm(String token) throws Exception {
        subscriptionService.checkToken(token);

        String json = Files.readString(Utils.getWebPath("pg", "lib", "tokentemplate.json"));

        ObjectNode objectNode = (ObjectNode) objectMapper.readTree(json);

        accountRepository.getFirstByMasterTrue().ifPresent(account -> {
            objectNode.put("token", account.getRefreshToken());
            objectNode.put("open_token", account.getOpenToken());
        });
        settingRepository.findById(Constants.OPEN_TOKEN_URL).map(Setting::getValue).ifPresent(url -> objectNode.put("open_api_url", url));
        driverAccountRepository.findByTypeAndMasterTrue(DriverType.QUARK).stream().findFirst().ifPresent(share -> objectNode.put("quark_cookie", share.getCookie()));
        driverAccountRepository.findByTypeAndMasterTrue(DriverType.PAN115).stream().findFirst().ifPresent(share -> {
            objectNode.put("pan115_cookie", share.getCookie());
            try {
                objectNode.put("pan115_delete_code", objectMapper.readTree(share.getAddition()).get("delete_code").asText());
            } catch (Exception e) {
                log.warn("", e);
            }
        });
        driverAccountRepository.findByTypeAndMasterTrue(DriverType.UC).stream().findFirst().ifPresent(share -> objectNode.put("uc_cookie", share.getCookie()));
        driverAccountRepository.findByTypeAndMasterTrue(DriverType.PAN123).stream().findFirst().ifPresent(share -> {
            objectNode.put("pan123_username", share.getUsername());
            objectNode.put("pan123_password", share.getPassword());
            objectNode.put("pan123_flags", "4kz");
        });
        driverAccountRepository.findByTypeAndMasterTrue(DriverType.CLOUD189).stream().findFirst().ifPresent(share -> {
            objectNode.put("pan189_username", share.getUsername());
            objectNode.put("pan189_password", share.getPassword());
            objectNode.put("pan189_flags", "4kz");
        });
        driverAccountRepository.findByTypeAndMasterTrue(DriverType.PAN139).stream().findFirst().ifPresent(share -> {
            objectNode.put("yd_auth", "Basic " + share.getToken());
            objectNode.put("yd_thread_limit", 4);
            objectNode.put("yd_flags", "auto|4kz");
            objectNode.put("yd_danmu", true);
        });
        driverAccountRepository.findByTypeAndMasterTrue(DriverType.THUNDER).stream().findFirst().ifPresent(share -> {
            objectNode.put("thunder_username", share.getUsername());
            objectNode.put("thunder_password", share.getPassword());
            objectNode.put("thunder_captchatoken", share.getToken());
        });
        pikPakAccountRepository.getFirstByMasterTrue().ifPresent(account -> {
            objectNode.put("pikpak_username", account.getUsername());
            objectNode.put("pikpak_password", account.getPassword());
        });

        Path path = Utils.getDataPath("tokenm.json");
        if (Files.exists(path)) {
            json = Files.readString(path);
            String address = subscriptionService.readHostAddress();
            json = json.replace("DOCKER_ADDRESS", address);
            json = json.replace("ATV_ADDRESS", address);
            ObjectNode override = (ObjectNode) objectMapper.readTree(json);
            objectNode.setAll(override);
        }

        return objectNode;
    }
}
