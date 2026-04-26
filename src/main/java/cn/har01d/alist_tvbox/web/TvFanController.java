package cn.har01d.alist_tvbox.web;

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
    private final AccountRepository accountRepository;
    private final DriverAccountRepository driverAccountRepository;
    private final ObjectMapper objectMapper;

    public TvFanController(SubscriptionService subscriptionService,
                           AccountRepository accountRepository,
                           DriverAccountRepository driverAccountRepository,
                           ObjectMapper objectMapper) {
        this.subscriptionService = subscriptionService;
        this.accountRepository = accountRepository;
        this.driverAccountRepository = driverAccountRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/config")
    public ObjectNode config(String token) throws IOException {
        subscriptionService.checkToken(token);

        ObjectNode objectNode = objectMapper.createObjectNode();

        accountRepository.getFirstByMasterTrue().ifPresent(account -> objectNode.put("token", account.getRefreshToken()));
        driverAccountRepository.findByTypeAndMasterTrue(DriverType.QUARK).stream().findFirst().ifPresent(share -> objectNode.put("quarkCookie", share.getCookie()));
        driverAccountRepository.findByTypeAndMasterTrue(DriverType.BAIDU).stream().findFirst().ifPresent(share -> objectNode.put("bdCk", share.getCookie()));
        driverAccountRepository.findByTypeAndMasterTrue(DriverType.UC).stream().findFirst().ifPresent(share -> objectNode.put("ucCookie", share.getCookie()));
        driverAccountRepository.findByTypeAndMasterTrue(DriverType.UC_TV).stream().findFirst().ifPresent(share -> objectNode.put("ucToken", share.getToken()));

        return objectNode;
    }
}
