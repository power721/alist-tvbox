package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.AccountRepository;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.service.FileDownloader;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/zx")
public class ZxConfigController {
    private final SubscriptionService subscriptionService;
    private final AppProperties appProperties;
    private final AccountRepository accountRepository;
    private final DriverAccountRepository driverAccountRepository;
    private final FileDownloader fileDownloader;
    private final ObjectMapper objectMapper;

    public ZxConfigController(SubscriptionService subscriptionService,
                              AppProperties appProperties,
                              AccountRepository accountRepository,
                              DriverAccountRepository driverAccountRepository,
                              FileDownloader fileDownloader,
                              ObjectMapper objectMapper) {
        this.subscriptionService = subscriptionService;
        this.appProperties = appProperties;
        this.accountRepository = accountRepository;
        this.driverAccountRepository = driverAccountRepository;
        this.fileDownloader = fileDownloader;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/version")
    public Object version() throws IOException {
        String remote = fileDownloader.getZxVersion();
        String local = "";
        Path path = Utils.getDataPath("zx_version.txt");
        if (Files.exists(path)) {
            local = Files.readString(path);
        }

        return Map.of("local", local, "remote", remote);
    }

    /**
     * 真心主动拉取的配置。授权=持有合法订阅 token;凭证下发受矩阵 zx 行控制
     * (zx 行默认开,总开关关则全停,#1084)。exeAddr 与 data/zx.json 覆盖段非凭证,保留。
     */
    @GetMapping("/config")
    public ObjectNode config(String token) throws IOException {
        subscriptionService.requireSubscriptionToken(token);

        String json = Files.readString(Utils.getWebPath("zx", "json", "peizhi.json"));

        ObjectNode objectNode = (ObjectNode) objectMapper.readTree(json);

        if (appProperties.allowPluginDrive("zx", "ali")) {
            accountRepository.getFirstByMasterTrue().ifPresent(account -> {
                objectNode.put("aliToken", account.getRefreshToken());
            });
        }
        if (appProperties.allowPluginDrive("zx", "quark")) {
            driverAccountRepository.findByTypeAndMasterTrue(DriverType.QUARK).stream().findFirst().ifPresent(share -> objectNode.put("quarkCookie", share.getCookie()));
        }
        if (appProperties.allowPluginDrive("zx", "115")) {
            driverAccountRepository.findByTypeAndMasterTrue(DriverType.PAN115).stream().findFirst().ifPresent(share -> {
                objectNode.put("115Cookie", share.getCookie());
                try {
                    objectNode.put("pwdRb115", objectMapper.readTree(share.getAddition()).get("delete_code").asText());
                } catch (Exception e) {
                    log.warn("", e);
                }
            });
        }
        if (appProperties.allowPluginDrive("zx", "baidu")) {
            driverAccountRepository.findByTypeAndMasterTrue(DriverType.BAIDU).stream().findFirst().ifPresent(share -> objectNode.put("baiduCookie", share.getCookie()));
        }
        if (appProperties.allowPluginDrive("zx", "uc")) {
            driverAccountRepository.findByTypeAndMasterTrue(DriverType.UC).stream().findFirst().ifPresent(share -> objectNode.put("ucCookie", share.getCookie()));
        }
        if (appProperties.allowPluginDrive("zx", "ucTv")) {
            driverAccountRepository.findByTypeAndMasterTrue(DriverType.UC_TV).stream().findFirst().ifPresent(share -> objectNode.put("ucToken", share.getToken()));
        }
        if (appProperties.allowPluginDrive("zx", "189")) {
            driverAccountRepository.findByTypeAndMasterTrue(DriverType.CLOUD189).stream().findFirst().ifPresent(share -> objectNode.put("tyAuth", share.getUsername() + "|" + share.getPassword()));
        }
        if (appProperties.allowPluginDrive("zx", "123")) {
            driverAccountRepository.findByTypeAndMasterTrue(DriverType.PAN123).stream().findFirst().ifPresent(share -> objectNode.put("p123Auth", share.getUsername() + "|" + share.getPassword()));
        }
        if (appProperties.allowPluginDrive("zx", "139")) {
            driverAccountRepository.findByTypeAndMasterTrue(DriverType.PAN139).stream().findFirst().ifPresent(share -> objectNode.put("ydAuth", share.getToken()));
        }

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
