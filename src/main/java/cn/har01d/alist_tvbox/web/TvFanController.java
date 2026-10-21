package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.AccountRepository;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@Slf4j
@RestController
@RequestMapping("/tvfan")
public class TvFanController {
    private final SubscriptionService subscriptionService;
    private final AppProperties appProperties;
    private final AccountRepository accountRepository;
    private final DriverAccountRepository driverAccountRepository;
    private final ObjectMapper objectMapper;

    public TvFanController(SubscriptionService subscriptionService,
                           AppProperties appProperties,
                           AccountRepository accountRepository,
                           DriverAccountRepository driverAccountRepository,
                           ObjectMapper objectMapper) {
        this.subscriptionService = subscriptionService;
        this.appProperties = appProperties;
        this.accountRepository = accountRepository;
        this.driverAccountRepository = driverAccountRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 饭太硬主动拉取的配置。授权=持有合法订阅 token;凭证下发受矩阵 fty 行控制
     * (与 spring.jar 对饭太硬 spUtils 的兼容写入同一行,总开关关则全停,#1084)。
     */
    @GetMapping("/config")
    public ObjectNode config(String token) throws IOException {
        subscriptionService.requireSubscriptionToken(token);

        ObjectNode objectNode = objectMapper.createObjectNode();

        if (appProperties.allowPluginDrive("fty", "ali")) {
            accountRepository.getFirstByMasterTrue().ifPresent(account -> objectNode.put("token", account.getRefreshToken()));
        }
        if (appProperties.allowPluginDrive("fty", "quark")) {
            driverAccountRepository.findByTypeAndMasterTrue(DriverType.QUARK).stream().findFirst().ifPresent(share -> objectNode.put("quarkCookie", share.getCookie()));
        }
        if (appProperties.allowPluginDrive("fty", "baidu")) {
            driverAccountRepository.findByTypeAndMasterTrue(DriverType.BAIDU).stream().findFirst().ifPresent(share -> objectNode.put("bdCk", share.getCookie()));
        }
        if (appProperties.allowPluginDrive("fty", "uc")) {
            driverAccountRepository.findByTypeAndMasterTrue(DriverType.UC).stream().findFirst().ifPresent(share -> objectNode.put("ucCookie", share.getCookie()));
        }
        if (appProperties.allowPluginDrive("fty", "ucTv")) {
            driverAccountRepository.findByTypeAndMasterTrue(DriverType.UC_TV).stream().findFirst().ifPresent(share -> objectNode.put("ucToken", share.getToken()));
        }

        return objectNode;
    }
}
