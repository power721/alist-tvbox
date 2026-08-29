package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.Account;
import cn.har01d.alist_tvbox.entity.AccountRepository;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.PikPakAccount;
import cn.har01d.alist_tvbox.entity.PikPakAccountRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.service.FileDownloader;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.util.Constants;
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
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/pg")
public class PgTokenController {
    private final SubscriptionService subscriptionService;
    private final AccountRepository accountRepository;
    private final SettingRepository settingRepository;
    private final DriverAccountRepository driverAccountRepository;
    private final PikPakAccountRepository pikPakAccountRepository;
    private final FileDownloader fileDownloader;
    private final ObjectMapper objectMapper;

    public PgTokenController(SubscriptionService subscriptionService,
                             AccountRepository accountRepository,
                             SettingRepository settingRepository,
                             DriverAccountRepository driverAccountRepository,
                             PikPakAccountRepository pikPakAccountRepository,
                             FileDownloader fileDownloader,
                             ObjectMapper objectMapper) {
        this.subscriptionService = subscriptionService;
        this.accountRepository = accountRepository;
        this.settingRepository = settingRepository;
        this.driverAccountRepository = driverAccountRepository;
        this.pikPakAccountRepository = pikPakAccountRepository;
        this.fileDownloader = fileDownloader;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/version")
    public Object version() throws IOException {
        String remote = fileDownloader.getPgVersion();
        String local = "";
        Path path = Utils.getDataPath("pg_version.txt");
        if (Files.exists(path)) {
            local = Files.readString(path);
        }
        return Map.of("local", local, "remote", remote);
    }

    @GetMapping("/lib/tokenm")
    public ObjectNode tokenm(String token) throws Exception {
        // 按 token 归属过滤:共享 token=管理员设备→全部 master 凭证;
        // u- 形态须带密钥(u-{username}-{vod_secret})验真才回本人账号凭证 —— 裸 u-{username}
        // 用户名可猜测,不能当授权(全局账号凭证也绝不下发给普通用户,只能经服务端代理使用)
        int uid = subscriptionService.credentialAuthorityUidFor(token);
        if (uid < 0) {
            subscriptionService.requireSubscriptionToken(token);
            uid = 0;
        }

        String json = Files.readString(Utils.getWebPath("pg", "lib", "tokentemplate.json"));

        ObjectNode objectNode = (ObjectNode) objectMapper.readTree(json);

        aliAccount(uid).ifPresent(account -> {
            objectNode.put("token", account.getRefreshToken());
            objectNode.put("open_token", account.getOpenToken());
        });
        if (uid == 0) {
            settingRepository.findById(Constants.OPEN_TOKEN_URL).map(Setting::getValue).ifPresent(url -> objectNode.put("open_api_url", url));
        }
        account(DriverType.QUARK, uid).ifPresent(share -> objectNode.put("quark_cookie", share.getCookie()));
        account(DriverType.PAN115, uid).ifPresent(share -> {
            objectNode.put("pan115_cookie", share.getCookie());
            try {
                objectNode.put("pan115_delete_code", objectMapper.readTree(share.getAddition()).get("delete_code").asText());
            } catch (Exception e) {
                log.warn("", e);
            }
        });
        account(DriverType.UC, uid).ifPresent(share -> objectNode.put("uc_cookie", share.getCookie()));
        account(DriverType.BAIDU, uid).ifPresent(share -> objectNode.put("baidu_cookie", share.getCookie()));
        account(DriverType.PAN123, uid).ifPresent(share -> {
            objectNode.put("pan123_username", share.getUsername());
            objectNode.put("pan123_password", share.getPassword());
            objectNode.put("pan123_flags", "4kz");
        });
        account(DriverType.CLOUD189, uid).ifPresent(share -> {
            objectNode.put("pan189_username", share.getUsername());
            objectNode.put("pan189_password", share.getPassword());
            objectNode.put("pan189_flags", "4kz");
        });
        account(DriverType.PAN139, uid).ifPresent(share -> {
            objectNode.put("yd_auth", "Basic " + share.getToken());
            objectNode.put("yd_thread_limit", 4);
            objectNode.put("yd_flags", "auto|4kz");
            objectNode.put("yd_danmu", true);
        });
        account(DriverType.THUNDER, uid).ifPresent(share -> {
            objectNode.put("thunder_username", share.getUsername());
            objectNode.put("thunder_password", share.getPassword());
            objectNode.put("thunder_captchatoken", share.getToken());
        });
        pikpakAccount(uid).ifPresent(account -> {
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

    private Optional<Account> aliAccount(int uid) {
        return uid == 0 ? accountRepository.getFirstByMasterTrue()
                : accountRepository.findFirstByOwnerUidOrderByIdAsc(uid);
    }

    private Optional<PikPakAccount> pikpakAccount(int uid) {
        return uid == 0 ? pikPakAccountRepository.getFirstByMasterTrue()
                : pikPakAccountRepository.findFirstByOwnerUidOrderByIdAsc(uid);
    }

    /** uid=0(共享 token)取全局 master 账号;否则取该用户自己的账号。 */
    private Optional<DriverAccount> account(DriverType type, int uid) {
        return uid == 0 ? driverAccountRepository.findByTypeAndMasterTrue(type)
                : driverAccountRepository.findFirstByOwnerUidAndTypeOrderByIdAsc(uid, type);
    }
}
