package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.AccountRepository;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import com.fasterxml.jackson.core.JsonProcessingException;
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

@Slf4j
@RestController
@RequestMapping("/zx")
public class ZxConfigController {
    private final SubscriptionService subscriptionService;
    private final AccountRepository accountRepository;
    private final DriverAccountRepository driverAccountRepository;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;

    public ZxConfigController(SubscriptionService subscriptionService,
                              AccountRepository accountRepository,
                              DriverAccountRepository driverAccountRepository,
                              ObjectMapper objectMapper,
                              RestTemplateBuilder builder,
                              AppProperties appProperties
    ) {
        this.subscriptionService = subscriptionService;
        this.accountRepository = accountRepository;
        this.driverAccountRepository = driverAccountRepository;
        this.objectMapper = objectMapper;
        this.restTemplate = builder.build();
        this.appProperties = appProperties;
    }

    @GetMapping("/version")
    public Object version() throws IOException {
        String remote = restTemplate.getForObject("http://har01d.org/zx.version?system=" + appProperties.getSystemId(), String.class);
        String local = "";
        Path path = Utils.getDataPath("zx_version.txt");
        if (Files.exists(path)) {
            local = Files.readString(path);
        }

        String remote2 = restTemplate.getForObject("http://har01d.org/zx.base.version?system=" + appProperties.getSystemId(), String.class);
        String local2 = "";
        path = Utils.getDataPath("zx_base_version.txt");
        if (Files.exists(path)) {
            local2 = Files.readString(path);
        }
        return Map.of("local", local, "remote", remote, "local2", local2, "remote2", remote2);
    }

    @GetMapping("/config")
    public ObjectNode config(String token) throws IOException {
        subscriptionService.checkToken(token);

        String json = Files.readString(Utils.getWebPath("zx", "json", "peizhi.json"));

        ObjectNode objectNode = (ObjectNode) objectMapper.readTree(json);

        accountRepository.getFirstByMasterTrue().ifPresent(account -> {
            objectNode.put("aliToken", account.getRefreshToken());
        });
        driverAccountRepository.findByTypeAndMasterTrue(DriverType.QUARK).stream().findFirst().ifPresent(share -> objectNode.put("quarkCookie", share.getCookie()));
        driverAccountRepository.findByTypeAndMasterTrue(DriverType.PAN115).stream().findFirst().ifPresent(share -> {
            objectNode.put("115Cookie", share.getCookie());
            try {
                objectNode.put("pwdRb115", objectMapper.readTree(share.getAddition()).get("delete_code").asText());
            } catch (Exception e) {
                log.warn("", e);
            }
        });
        driverAccountRepository.findByTypeAndMasterTrue(DriverType.BAIDU).stream().findFirst().ifPresent(share -> objectNode.put("baiduCookie", share.getCookie()));
        driverAccountRepository.findByTypeAndMasterTrue(DriverType.UC).stream().findFirst().ifPresent(share -> objectNode.put("ucCookie", share.getCookie()));
        driverAccountRepository.findByTypeAndMasterTrue(DriverType.UC_TV).stream().findFirst().ifPresent(share -> objectNode.put("ucToken", share.getToken()));
        driverAccountRepository.findByTypeAndMasterTrue(DriverType.CLOUD189).stream().findFirst().ifPresent(share -> objectNode.put("tyAuth", share.getUsername() + "|" + share.getPassword()));
        driverAccountRepository.findByTypeAndMasterTrue(DriverType.PAN123).stream().findFirst().ifPresent(share -> objectNode.put("p123Auth", share.getUsername() + "|" + share.getPassword()));
        driverAccountRepository.findByTypeAndMasterTrue(DriverType.PAN139).stream().findFirst().ifPresent(share -> objectNode.put("ydAuth", share.getToken()));

        objectNode.put("exeAddr", subscriptionService.readHostAddress("/zx/lib/"));

        Path path = Utils.getDataPath("zx.json");
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
