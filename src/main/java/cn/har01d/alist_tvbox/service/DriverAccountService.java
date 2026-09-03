package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.dto.AccountInfo;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.PanAccountRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Share;
import cn.har01d.alist_tvbox.entity.ShareRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import cn.har01d.alist_tvbox.storage.BaiduNetdisk;
import cn.har01d.alist_tvbox.storage.GuangYaPan;
import cn.har01d.alist_tvbox.storage.Open123;
import cn.har01d.alist_tvbox.storage.Pan115;
import cn.har01d.alist_tvbox.storage.Pan123;
import cn.har01d.alist_tvbox.storage.Pan139;
import cn.har01d.alist_tvbox.storage.Pan189;
import cn.har01d.alist_tvbox.storage.Quark;
import cn.har01d.alist_tvbox.storage.QuarkTV;
import cn.har01d.alist_tvbox.storage.Storage;
import cn.har01d.alist_tvbox.storage.ThunderBrowser;
import cn.har01d.alist_tvbox.storage.UC;
import cn.har01d.alist_tvbox.storage.UCTV;
import cn.har01d.alist_tvbox.util.Constants;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
public class DriverAccountService {
    public static final int IDX = 4000;
    private static final String GY_ACCOUNT_API = "https://account.guangyapan.com";
    private static final String GY_CLIENT_ID = "aMe-8VSlkrbQXpUR";
    private static final String GY_DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";
    private static final String PAN123_OAUTH_SERVER = "https://oauth.litepan.top";
    private static final String PAN123_OAUTH_DRIVER = "123云盘Open";
    private static final String THUNDER_CLIENT_ID = "Xp6vsxz_7IYVw2BB";
    private static final String THUNDER_CAPTCHA_CLIENT_VERSION = "8.03.0.9067";
    private static final String THUNDER_PACKAGE_NAME = "com.xunlei.downloadprovider";
    private static final String THUNDER_CAPTCHA_TIMESTAMP = "1735660800000";
    private static final String THUNDER_CAPTCHA_USER_AGENT = "ANDROID-com.xunlei.downloadprovider/8.56.0.1134 "
            + "netWorkType/WIFI appid/40 deviceName/Xiaomi_Mi 9 deviceModel/MI 9 OSVersion/9 "
            + "protocolVersion/301 platformVersion/10 sdkVersion/513006 Oauth2Client/0.9 "
            + "(Linux 4_4_146) (JAVA 0)";
    private static final String THUNDER_ABOUT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";
    private static final String[] THUNDER_CAPTCHA_ALGORITHMS = {
            "DPdLBvYvRkKewl6IvQTSKSV6ws7F9", "4ZnspAqakTEcghWtF9FRnZqtpxuACpAJq3jbiH",
            "GZ4iB0a30T1", "EjNYWJI/CQV4ovf", "042FPU6qgf94gDnNVeepvXIUZpOj7lltfg/I3T0wfbHKJPetx",
            "QFhWvh91aKcN3CvJUQ40HPxo", "jRxFmAZeiqg1Y", "qXF8/KOCx4/dTuz",
            "CMjDD2dxuV9touYldY2URt4vA7z47v1FcZ3k7DAr", "wN0P2x+N4BYQDS1fd"
    };
    private static final Set<DriverType> TOKEN_TYPES = Set.of(DriverType.OPEN115, DriverType.PAN123, DriverType.OPEN123, DriverType.PAN139, DriverType.BAIDU, DriverType.THUNDER);
    private static final Set<DriverType> COOKIE_TYPES = Set.of(DriverType.PAN115, DriverType.QUARK, DriverType.UC, DriverType.CLOUD189);
    private final PanAccountRepository panAccountRepository;
    private final DriverAccountRepository driverAccountRepository;
    private final SettingRepository settingRepository;
    private final ShareRepository shareRepository;
    private final AccountService accountService;
    private final AListLocalService aListLocalService;
    private final OfflineDownloadService offlineDownloadService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate alistJdbcTemplate;
    private final Map<String, QuarkUCTV> drivers = new HashMap<>();

    public DriverAccountService(PanAccountRepository panAccountRepository,
                                DriverAccountRepository driverAccountRepository,
                                SettingRepository settingRepository,
                                ShareRepository shareRepository,
                                AccountService accountService,
                                AListLocalService aListLocalService,
                                OfflineDownloadService offlineDownloadService,
                                RestTemplateBuilder builder,
                                ObjectMapper objectMapper,
                                @Qualifier("alistJdbcTemplate") JdbcTemplate alistJdbcTemplate) {
        this.panAccountRepository = panAccountRepository;
        this.driverAccountRepository = driverAccountRepository;
        this.settingRepository = settingRepository;
        this.shareRepository = shareRepository;
        this.accountService = accountService;
        this.aListLocalService = aListLocalService;
        this.offlineDownloadService = offlineDownloadService;
        this.restTemplate = builder.build();
        this.objectMapper = objectMapper;
        this.alistJdbcTemplate = alistJdbcTemplate;
    }

    @PostConstruct
    public void init() {
        if (!settingRepository.existsByName("migrate_pan_account")) {
            migratePanAccounts();
        }
        if (!settingRepository.existsByName("migrate_driver_account")) {
            migrateDriverAccounts();
        }
        if (!settingRepository.existsByName("fix_driver_concurrency")) {
            fixConcurrency();
        }
        if (!settingRepository.existsByName("fix_driverChunkSize")) {
            fixChunkSize();
        }
        if (!settingRepository.existsByName("migrate_115_delete_code")) {
            migrate115DeleteCode();
        }

        String deviceId = settingRepository.findById("quark_device_id").map(Setting::getValue).orElse(null);
        if (deviceId == null) {
            deviceId = QuarkUCTV.generateDeviceId();
            settingRepository.save(new Setting("quark_device_id", deviceId));
        }
        drivers.put("QUARK_TV", new QuarkUCTV(restTemplate, new QuarkUCTV.Conf("https://open-api-drive.quark.cn", "d3194e61504e493eb6222857bccfed94", "kw2dvtd7p4t3pjl2d9ed9yc8yej8kw2d", "1.5.6", "CP", "http://api.extscreen.com/quarkdrive", deviceId)));
        drivers.put("UC_TV", new QuarkUCTV(restTemplate, new QuarkUCTV.Conf("https://open-api-drive.uc.cn", "5acf882d27b74502b7040b0c65519aa7", "l3srvtd7p42l0d0x1u8d7yc8ye9kki4d", "1.6.5", "UCTVOFFICIALWEB", "http://api.extscreen.com/ucdrive", deviceId)));
    }

    private void fixConcurrency() {
        List<DriverAccount> accounts = driverAccountRepository.findAll();
        for (DriverAccount account : accounts) {
            switch (account.getType()) {
                case PAN115, OPEN115, BAIDU -> account.setConcurrency(2);
                case UC, UC_TV -> account.setConcurrency(8);
                case QUARK, QUARK_TV -> account.setConcurrency(10);
                default -> account.setConcurrency(1);
            }
        }
        driverAccountRepository.saveAll(accounts);
        settingRepository.save(new Setting("fix_driver_concurrency", ""));
    }

    private void fixChunkSize() {
        log.info("fix chunk size");
        List<DriverAccount> accounts = driverAccountRepository.findAll();
        for (var account : accounts) {
            int chunkSize = 256;
            switch (account.getType()) {
                case PAN115, OPEN115, BAIDU, THUNDER, CLOUD189 -> chunkSize = 1024;
            }
            String json = account.getAddition();
            if (StringUtils.isBlank(json)) {
                json = "{}";
            }
            try {
                ObjectNode object = objectMapper.readValue(json, ObjectNode.class);
                object.put("chunk_size", chunkSize);
                account.setAddition(objectMapper.writeValueAsString(object));
            } catch (Exception e) {
                log.warn("<UNK>", e);
            }
        }
        driverAccountRepository.saveAll(accounts);
        settingRepository.save(new Setting("fix_driverChunkSize", ""));
    }

    private void migrate115DeleteCode() {
        var driver = driverAccountRepository.findByTypeAndMasterTrue(DriverType.PAN115);
        driver.ifPresent(account -> {
            settingRepository.findById("delete_code_115").ifPresent(setting -> {
                try {
                    if (StringUtils.isBlank(account.getAddition())) {
                        account.setAddition("{}");
                    }
                    ObjectNode jsonNode = objectMapper.readValue(account.getAddition(), ObjectNode.class);
                    jsonNode.put("delete_code", setting.getValue());
                    account.setAddition(objectMapper.writeValueAsString(jsonNode));
                    driverAccountRepository.save(account);
                } catch (Exception e) {
                    log.warn("", e);
                }
            });
        });
        settingRepository.save(new Setting("migrate_115_delete_code", ""));
    }

    private void migrateDriverAccounts() {
        List<DriverAccount> accounts = new ArrayList<>();
        for (var item : panAccountRepository.findAll()) {
            var account = new DriverAccount();
            account.setId(item.getId());
            account.setName(item.getName());
            account.setType(item.getType());
            account.setCookie(item.getCookie());
            account.setToken(item.getToken());
            account.setMaster(item.isMaster());
            account.setUseProxy(item.isUseProxy());
            account.setFolder(item.getFolder());
            accounts.add(account);
        }
        driverAccountRepository.saveAll(accounts);
        log.info("migrated {} accounts", accounts.size());
        settingRepository.save(new Setting("migrate_driver_account", "true"));
    }

    private void migratePanAccounts() {
        List<DriverAccount> accounts = new ArrayList<>();
        List<Share> shares = shareRepository.findAll();
        List<Share> deleted = new ArrayList<>();
        boolean master2 = true;
        boolean master3 = true;
        boolean master6 = true;
        for (Share share : shares) {
            if (share.getType() == 2 || share.getType() == 3 || share.getType() == 6) {
                DriverAccount account = new DriverAccount();
                if (share.getType() == 2) {
                    account.setType(DriverType.QUARK);
                    account.setMaster(master2);
                    master2 = false;
                } else if (share.getType() == 3) {
                    account.setType(DriverType.PAN115);
                    account.setMaster(master3);
                    master3 = false;
                } else if (share.getType() == 6) {
                    account.setType(DriverType.UC);
                    account.setMaster(master6);
                    master6 = false;
                }
                account.setName(getNameFromPath(share.getPath()));
                account.setFolder(share.getFolderId());
                account.setCookie(share.getCookie());
                accounts.add(account);
                deleted.add(share);
            }
        }
        driverAccountRepository.saveAll(accounts);
        shareRepository.deleteAll(deleted);
        log.info("migrated {} accounts", accounts.size());
        settingRepository.save(new Setting("migrate_pan_account", "true"));
    }

    private String getNameFromPath(String path) {
        return path.substring(path.lastIndexOf("/") + 1);
    }

    public void loadStorages() {
        List<DriverAccount> accounts = driverAccountRepository.findAll();
        for (DriverAccount account : accounts) {
            if (account.isMaster()) {
                updateMasterToken(account, false);
            }
            if (!account.isDisabled()) {
                saveStorage(account, false);
            }
        }
        offlineDownloadService.syncConfiguredTempDirOnStartup();
    }

    private void saveStorage(DriverAccount account, boolean disabled) {
        String deviceId = settingRepository.findById("quark_device_id").map(Setting::getValue).orElse("");
        Storage storage = null;
        if (account.getType() == DriverType.QUARK) {
            storage = new Quark(account);
        } else if (account.getType() == DriverType.QUARK_TV) {
            storage = new QuarkTV(account, deviceId);
        } else if (account.getType() == DriverType.UC) {
            storage = new UC(account);
        } else if (account.getType() == DriverType.UC_TV) {
            storage = new UCTV(account, deviceId);
        } else if (account.getType() == DriverType.THUNDER) {
            storage = new ThunderBrowser(account);
        } else if (account.getType() == DriverType.CLOUD189) {
            storage = new Pan189(account);
        } else if (account.getType() == DriverType.PAN123) {
            storage = new Pan123(account);
        } else if (account.getType() == DriverType.PAN139) {
            storage = new Pan139(account);
        } else if (account.getType() == DriverType.PAN115) {
            storage = new Pan115(account);
        } else if (account.getType() == DriverType.OPEN115) {
            //storage = new Open115(account);
        } else if (account.getType() == DriverType.OPEN123) {
            storage = new Open123(account);
        } else if (account.getType() == DriverType.GUANGYA) {
            storage = new GuangYaPan(account);
        } else if (account.getType() == DriverType.BAIDU) {
            storage = new BaiduNetdisk(account);
        }

        if (storage != null) {
            storage.setDisabled(disabled);
            aListLocalService.saveStorage(storage);
        }
    }

    public List<DriverAccount> list() {
        return driverAccountRepository.findAll();
    }

    public DriverAccount get(int id) {
        return driverAccountRepository.findById(id).orElseThrow(NotFoundException::new);
    }

    public long countByType(DriverType type) {
        return driverAccountRepository.countByType(type);
    }

    public DriverAccount create(DriverAccount account) {
        validate(account);
        if (driverAccountRepository.existsByNameAndType(account.getName(), account.getType())) {
            throw new BadRequestException("账号名称已经存在");
        }
        account.setId(null);
        // 首个账号自动升 master 仅限全局账号:普通用户给未配置的盘型开首个个人账号不得抢占全局主账号位
        if (driverAccountRepository.countByType(account.getType()) == 0 && account.getOwnerUid() == 0) {
            account.setMaster(true);
        } else {
            updateMaster(account);
        }
        driverAccountRepository.save(account);

        updateStorage(account);

        return account;
    }

    public void updateToken(Integer id, DriverAccount dto) {
        log.debug("update token: {} {}", id - IDX, dto);
        var account = get(id - IDX);
        if (account.getType() == DriverType.OPEN123 || account.getType() == DriverType.GUANGYA) {
            // Go 刷新后同步回来的是 refresh_token(可能轮换),写回 addition.refresh_token,保留 access token。
            try {
                var add = Utils.readJson(account.getAddition());
                add.put("refresh_token", dto.getToken());
                account.setAddition(objectMapper.writeValueAsString(add));
            } catch (Exception e) {
                log.warn("sync {} refresh_token failed", account.getType(), e);
            }
        } else if (account.getType() == DriverType.THUNDER) {
            account.setToken(dto.getToken());
            account.setCookie(dto.getCookie());
        } else if (account.getType() == DriverType.UC_TV) {
            account.setUsername(dto.getUsername());
            account.setPassword(dto.getPassword());
        } else if (TOKEN_TYPES.contains(account.getType())) {
            account.setToken(dto.getToken());
        } else {
            account.setCookie(dto.getToken());
        }
        driverAccountRepository.save(account);
    }

    public DriverAccount update(Integer id, DriverAccount dto) {
        validate(dto);
        var account = get(id);
        String previousFolder = account.getFolder();
        var other = driverAccountRepository.findByNameAndType(dto.getName(), dto.getType());
        if (other != null && !other.getId().equals(id)) {
            throw new BadRequestException("账号名称已经存在");
        }

        boolean changed = account.isMaster() != dto.isMaster()
                || account.isUseProxy() != dto.isUseProxy()
                || account.isDisabled() != dto.isDisabled()
                || !Objects.equals(account.getType(), dto.getType())
                || !Objects.equals(account.getToken(), dto.getToken())
                || !Objects.equals(account.getCookie(), dto.getCookie())
                || !Objects.equals(account.getAddition(), dto.getAddition())
                || !Objects.equals(account.getFolder(), dto.getFolder())
                || !Objects.equals(account.getName(), dto.getName());

        account.setMaster(dto.isMaster());
        account.setUseProxy(dto.isUseProxy());
        account.setDisabled(dto.isDisabled());
        account.setName(dto.getName());
        account.setType(dto.getType());
        account.setCookie(dto.getCookie());
        account.setToken(dto.getToken());
        account.setUsername(dto.getUsername());
        account.setPassword(dto.getPassword());
        account.setSafePassword(dto.getSafePassword());
        account.setFolder(dto.getFolder());
        account.setConcurrency(dto.getConcurrency());
        account.setAddition(dto.getAddition());
        account.setShared(dto.isShared());
        if (dto.getType() == DriverType.BAIDU && (StringUtils.isNotBlank(dto.getAddition())) && !"{}".equals(dto.getAddition())) {
            account.setToken("");
        }

        // 仅剩一个账号时强制 master 仅限全局账号:普通用户编辑本人某盘型唯一账号,
        // 不得因此顶成全局主账号(updateMaster 是 owner 无关查询,会把全局选主一并改掉)
        if (driverAccountRepository.countByType(account.getType()) <= 1 && account.getOwnerUid() == 0) {
            account.setMaster(true);
        }

        if (changed && account.isMaster()) {
            updateMaster(account);
        }

        driverAccountRepository.save(account);
        if ((account.getType() == DriverType.PAN115 || account.getType() == DriverType.THUNDER)
                && !Objects.equals(previousFolder, account.getFolder())) {
            offlineDownloadService.syncSelectedAccountTempDir(account.getId());
        }

        updateStorage(account);

        return account;
    }

    public void delete(Integer id) {
        DriverAccount account = driverAccountRepository.findById(id).orElse(null);
        if (account != null) {
            // 先清 AList storage 再删本地行:AList 侧失败(服务未就绪等)时行保留,重试仍有据可查;
            // 反序会把活凭证遗留在 AList 里且失去重试入口
            int storageId = IDX + account.getId();
            int status = aListLocalService.checkStatus();
            if (status == 1) {
                throw new BadRequestException("AList服务启动中");
            }
            if (status >= 2) {
                accountService.deleteStorage(storageId, accountService.login());
            } else {
                aListLocalService.executeUpdate("DELETE FROM x_storages WHERE id = " + storageId);
            }
            driverAccountRepository.deleteById(id);
        }
    }

    private void validate(DriverAccount dto) {
        if (StringUtils.isBlank(dto.getName())) {
            throw new BadRequestException("名称不能为空");
        }
//        if (dto.getName().contains("/")) {
//            throw new BadRequestException("名称不能包含/");
//        }
        if (dto.getType() == null) {
            throw new BadRequestException("类型不能为空");
        }
        if (dto.getType() == DriverType.THUNDER || dto.getType() == DriverType.CLOUD189 || dto.getType() == DriverType.PAN123) {
            if (StringUtils.isBlank(dto.getUsername())) {
                throw new BadRequestException("用户名不能为空");
            }
            if (StringUtils.isBlank(dto.getPassword())) {
                throw new BadRequestException("密码不能为空");
            }
        } else if (dto.getType() == DriverType.PAN139) {
            if (StringUtils.isBlank(dto.getToken())) {
                throw new BadRequestException("Token不能为空");
            }
            if (dto.getToken().startsWith("Basic ")) {
                dto.setToken(dto.getToken().substring(6).trim());
            }
        } else if (dto.getType() == DriverType.GUANGYA) {
            Map<String, Object> addition = readAddition(dto.getAddition());
            String accessToken = StringUtils.defaultIfBlank(dto.getToken(), text(addition.get("access_token")));
            String refreshToken = text(addition.get("refresh_token"));
            if (StringUtils.isBlank(accessToken) && StringUtils.isBlank(refreshToken)) {
                throw new BadRequestException("Token不能为空");
            }
        } else if (dto.getType() == DriverType.OPEN123) {
            Map<String, Object> addition = readAddition(dto.getAddition());
            String refreshToken = text(addition.get("refresh_token"));
            if (StringUtils.isBlank(dto.getToken()) && StringUtils.isBlank(refreshToken)) {
                throw new BadRequestException("请扫码登录或填写 Access Token / Refresh Token");
            }
        } else if (StringUtils.isBlank(dto.getCookie()) && StringUtils.isBlank(dto.getToken())) {
            throw new BadRequestException("Cookie和Token不能同时为空");
        }
        if (StringUtils.isBlank(dto.getFolder())) {
            if (dto.getType() == DriverType.QUARK || dto.getType() == DriverType.UC || dto.getType() == DriverType.QUARK_TV || dto.getType() == DriverType.UC_TV || dto.getType() == DriverType.PAN115 || dto.getType() == DriverType.OPEN115 || dto.getType() == DriverType.OPEN123 || dto.getType() == DriverType.PAN123 || dto.getType() == DriverType.GUANGYA) {
                dto.setFolder("0");
            } else if (dto.getType() == DriverType.CLOUD189) {
                dto.setFolder("-11");
            } else if (dto.getType() == DriverType.BAIDU) {
                dto.setFolder("/");
            }
        }
        if (dto.getCookie() != null) {
            dto.setCookie(dto.getCookie().replace("\n", ";").trim());
        }
    }

    private void updateMaster(DriverAccount account) {
        if (account.isMaster()) {
            log.info("reset account master");
            List<DriverAccount> list = driverAccountRepository.findAll();
            list = list.stream().filter(e -> e.getType() == account.getType()).toList();
            for (DriverAccount a : list) {
                a.setMaster(false);
            }
            account.setMaster(true);
            driverAccountRepository.saveAll(list);
            updateMasterToken(account, true);
        }
    }

    private void updateMasterToken(DriverAccount account, boolean useApi) {
        int id = IDX + account.getId();
        if (useApi) {
            aListLocalService.updateSetting(account.getType() + "_id", String.valueOf(id), "number");
        } else {
            aListLocalService.setSetting(account.getType() + "_id", String.valueOf(id), "number");
        }
        String value;
        if (TOKEN_TYPES.contains(account.getType())) {
            value = account.getToken();
        } else if (COOKIE_TYPES.contains(account.getType())) {
            value = account.getCookie();
        } else {
            return;
        }
        if (useApi) {
            aListLocalService.updateToken(id, account.getType() + "_" + id, value);
        } else {
            aListLocalService.setToken(id, account.getType() + "_" + id, value);
        }
    }

    private void updateStorage(DriverAccount account) {
        int status = aListLocalService.checkStatus();
        try {
            int id = IDX + account.getId();
            String token = status >= 2 ? accountService.login() : "";
            if (status >= 2) {
                accountService.deleteStorage(id, token);
            } else {
                aListLocalService.executeUpdate("DELETE FROM x_storages WHERE id = " + id);
            }
            if (!account.isDisabled()) {
                saveStorage(account, true);
                if (status >= 2) {
                    accountService.enableStorage(id, token);
                }
            }
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
    }

    public QuarkUCTV.LoginResponse getQrCode(String type) throws IOException {
        if (DriverType.QUARK.name().equals(type)) {
            return getQuarkQr();
        }
        if (DriverType.UC.name().equals(type)) {
            return getUcQr();
        }
        if (DriverType.GUANGYA.name().equals(type)) {
            return getGuangYaQr();
        }
        if (DriverType.OPEN123.name().equals(type)) {
            return getPan123OpenQr();
        }
        QuarkUCTV driver = drivers.get(type);
        if (driver == null) {
            throw new BadRequestException("不支持的类型");
        }
        return driver.getLoginCode();
    }

    private QuarkUCTV.LoginResponse getPan123OpenQr() {
        // 用户无自有 client_id:经 oauth.litepan.top 代理(内置开发者凭据)走 123 官方授权页拿 access/refresh token。
        // litepan 是浏览器重定向流程,不是扫码流程:返回授权链接让前端新标签页打开,授权后轮询 session 取 token。
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = Map.of(
                "driver_type", PAN123_OAUTH_DRIVER,
                "callback_url", PAN123_OAUTH_SERVER + "/callback-popup"
        );
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ObjectNode json = restTemplate.postForObject(PAN123_OAUTH_SERVER + "/api/oauth/start", entity, ObjectNode.class);
        if (json == null) {
            throw new BadRequestException("123 Open 授权链接获取失败: empty response");
        }
        String sessionId = json.path("data").path("session_id").asText("");
        String oauthUrl = json.path("data").path("oauth_url").asText("");
        if (StringUtils.isAnyBlank(sessionId, oauthUrl)) {
            String error = json.path("data").path("error").asText(json.path("message").asText("invalid response"));
            throw new BadRequestException("123 Open 授权链接获取失败: " + error);
        }
        var res = new QuarkUCTV.LoginResponse();
        res.setAuthUrl(oauthUrl);
        res.setQueryToken(sessionId);
        return res;
    }

    private QuarkUCTV.LoginResponse getGuangYaQr() throws IOException {
        String deviceId = createGuangYaDeviceId();
        HttpHeaders headers = guangYaHeaders(deviceId);
        Map<String, Object> body = Map.of("scope", "user", "client_id", GY_CLIENT_ID);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ObjectNode json = restTemplate.postForObject(GY_ACCOUNT_API + "/v1/auth/device/code", entity, ObjectNode.class);
        if (json == null) {
            throw new BadRequestException("二维码生成失败: empty response");
        }
        String deviceCode = json.path("device_code").asText("");
        String verifyUrl = json.path("verification_uri_complete").asText(json.path("verification_url").asText(""));
        int expiresIn = json.path("expires_in").asInt(120);
        if (StringUtils.isAnyBlank(deviceCode, verifyUrl)) {
            String error = StringUtils.defaultIfBlank(json.path("error_description").asText(), json.path("message").asText("invalid response"));
            throw new BadRequestException("二维码生成失败: " + error);
        }

        var res = new QuarkUCTV.LoginResponse();
        res.setQrData(Utils.getQrCode(verifyUrl));
        res.setQueryToken(encodeGuangYaSession(deviceCode, deviceId, expiresIn));
        return res;
    }

    private QuarkUCTV.LoginResponse getQuarkQr() throws IOException {
        long t = System.currentTimeMillis();
        var json = restTemplate.getForObject("https://uop.quark.cn/cas/ajax/getTokenForQrcodeLogin?client_id=532&v=1.2&request_id={t}", ObjectNode.class, t);
        String token = json.get("data").get("members").get("token").asText();
        String qr = Utils.getQrCode("https://su.quark.cn/4_eMHBJ?token=" + token + "&client_id=532&ssb=weblogin&uc_param_str=&uc_biz_str=S%3Acustom%7COPT%3ASAREA%400%7COPT%3AIMMERSIVE%401%7COPT%3ABACK_BTN_STYLE%400");
        var res = new QuarkUCTV.LoginResponse();
        res.setQueryToken(token);
        res.setQrData(qr);
        return res;
    }

    private QuarkUCTV.LoginResponse getUcQr() throws IOException {
        long t = System.currentTimeMillis();
        var json = restTemplate.getForObject("https://api.open.uc.cn/cas/ajax/getTokenForQrcodeLogin?client_id=381&v=1.2&request_id={t}", ObjectNode.class, t);
        String token = json.get("data").get("members").get("token").asText();
        String qr = Utils.getQrCode("https://su.uc.cn/1_n0ZCv?uc_param_str=dsdnfrpfbivesscpgimibtbmnijblauputogpintnwktprchmt&token=" + token + "&client_id=381&uc_biz_str=S%3Acustom%7CC%3Atitlebar_fix");
        var res = new QuarkUCTV.LoginResponse();
        res.setQueryToken(token);
        res.setQrData(qr);
        return res;
    }

    public AccountInfo getRefreshToken(String type, String queryToken) {
        if (DriverType.QUARK.name().equals(type)) {
            return getQuarkCookie(queryToken);
        }
        if (DriverType.UC.name().equals(type)) {
            return getUcCookie(queryToken);
        }
        if (DriverType.GUANGYA.name().equals(type)) {
            return getGuangYaToken(queryToken);
        }
        if (DriverType.OPEN123.name().equals(type)) {
            return getPan123OpenToken(queryToken);
        }
        QuarkUCTV driver = drivers.get(type);
        if (driver == null) {
            throw new BadRequestException("不支持的类型");
        }
        String code = driver.getCode(queryToken);
        String token = driver.getRefreshToken(code);
        var info = new AccountInfo();
        info.setToken(token);
        return info;
    }

    private AccountInfo getPan123OpenToken(String sessionId) {
        ObjectNode json = restTemplate.getForObject(PAN123_OAUTH_SERVER + "/api/oauth/status/{sid}", ObjectNode.class, sessionId);
        if (json == null) {
            throw new BadRequestException("等待用户在新标签页完成授权...");
        }
        var data = json.path("data");
        var td = data.path("token_data");
        // 对齐 JS extractTokenData:token_data 优先、其次 data;access_token 兼容 access_token/accessToken/token。
        String accessToken = firstNonBlank(td.path("access_token"), td.path("accessToken"), td.path("token"),
                data.path("access_token"), data.path("accessToken"), data.path("token"));
        String refreshToken = firstNonBlank(td.path("refresh_token"), td.path("refreshToken"),
                data.path("refresh_token"), data.path("refreshToken"));
        if (StringUtils.isNotBlank(accessToken)) {
            // 对齐 JS:拿到 token 后通知 litepan「已收到」(无 body,仅 Accept),best-effort,失败不阻塞登录。
            try {
                restTemplate.postForObject(PAN123_OAUTH_SERVER + "/api/oauth/confirm-received/{sid}",
                        null, ObjectNode.class, sessionId);
            } catch (Exception ignore) {
            }
            var info = new AccountInfo();
            info.setToken(accessToken);
            info.getAddition().put("refresh_token", refreshToken);
            return info;
        }
        String status = data.path("status").asText("");
        if ("expired".equals(status) || "failed".equals(status)) {
            throw new BadRequestException("授权链接已过期，请重新获取！");
        }
        throw new BadRequestException("等待用户在新标签页完成授权...");
    }

    private AccountInfo getGuangYaToken(String queryToken) {
        Map<String, Object> session = decodeGuangYaSession(queryToken);
        if (session.isEmpty()) {
            throw new BadRequestException("二维码无效或已过期！");
        }
        String deviceCode = text(session.get("device_code"));
        String deviceId = text(session.get("device_id"));
        HttpHeaders headers = guangYaHeaders(deviceId);
        Map<String, Object> body = Map.of(
                "grant_type", GY_DEVICE_GRANT,
                "device_code", deviceCode,
                "client_id", GY_CLIENT_ID
        );
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ObjectNode json = restTemplate.postForObject(GY_ACCOUNT_API + "/v1/auth/token", entity, ObjectNode.class);
        if (json == null) {
            throw new BadRequestException("等待用户扫码...");
        }

        String accessToken = json.path("access_token").asText(json.path("accessToken").asText(""));
        String refreshToken = json.path("refresh_token").asText(json.path("refreshToken").asText(""));
        if (StringUtils.isNotBlank(accessToken) || StringUtils.isNotBlank(refreshToken)) {
            var info = new AccountInfo();
            info.setToken(accessToken);
            info.getAddition().put("access_token", accessToken);
            info.getAddition().put("refresh_token", refreshToken);
            info.getAddition().put("device_id", deviceId);
            return info;
        }

        String error = json.path("error").asText("");
        if ("access_denied".equals(error)) {
            throw new BadRequestException("用户已取消扫码");
        }
        if (error.contains("expired") || "invalid_grant".equals(error)) {
            throw new BadRequestException("二维码无效或已过期！");
        }
        throw new BadRequestException("等待用户扫码...");
    }

    private HttpHeaders guangYaHeaders(String deviceId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + ", text/plain, */*");
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Client-Id", GY_CLIENT_ID);
        headers.set("X-Client-Version", "0.0.1");
        headers.set("X-Device-Id", deviceId);
        headers.set("X-Device-Model", "chrome%2F147.0.0.0");
        headers.set("X-Device-Name", "PC-Chrome");
        headers.set("X-Device-Sign", "wdi10." + deviceId + "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        headers.set("X-Net-Work-Type", "NONE");
        headers.set("X-OS-Version", "Win32");
        headers.set("X-Platform-Version", "1");
        headers.set("X-Protocol-Version", "301");
        headers.set("X-Provider-Name", "NONE");
        headers.set("X-SDK-Version", "9.0.2");
        return headers;
    }

    private String encodeGuangYaSession(String deviceCode, String deviceId, int expiresIn) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("device_code", deviceCode);
        node.put("device_id", deviceId);
        node.put("expire_time", System.currentTimeMillis() + Math.max(1000, expiresIn * 1000L));
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(node));
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
    }

    private Map<String, Object> decodeGuangYaSession(String queryToken) {
        if (StringUtils.isBlank(queryToken)) {
            return Map.of();
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(queryToken);
            Map<String, Object> data = objectMapper.readValue(bytes, Map.class);
            Number expireTime = (Number) data.get("expire_time");
            if (expireTime == null || expireTime.longValue() < System.currentTimeMillis()) {
                return Map.of();
            }
            return data;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String createGuangYaDeviceId() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static Map<String, Object> readAddition(String addition) {
        if (StringUtils.isBlank(addition)) {
            return Map.of();
        }
        return Utils.readJson(addition);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static ObjectNode object(ObjectNode parent, String field) {
        if (parent == null) {
            return null;
        }
        JsonNode node = parent.path(field);
        return node.isObject() ? (ObjectNode) node : null;
    }

    private static ObjectNode responseData(ObjectNode response) {
        ObjectNode data = object(response, "data");
        return data == null ? response : data;
    }

    private static JsonNode firstPresent(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && !node.isMissingNode() && !node.isNull() && StringUtils.isNotBlank(node.asText())) {
                return node;
            }
        }
        return null;
    }

    private static boolean truthy(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        return node.asBoolean(false) || node.asInt(0) > 0 || "true".equalsIgnoreCase(node.asText());
    }

    private static Long parseExpireAt(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            long timestamp = node.asLong();
            return timestamp > 0 ? timestamp : null;
        }
        String value = node.asText("").trim();
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            long timestamp = Long.parseLong(value);
            return timestamp > 0 ? timestamp : null;
        } catch (NumberFormatException ignored) {
        }
        try {
            long timestamp = Instant.parse(value).getEpochSecond();
            return timestamp > 0 ? timestamp : null;
        } catch (Exception ignored) {
        }
        try {
            long timestamp = OffsetDateTime.parse(value).toEpochSecond();
            return timestamp > 0 ? timestamp : null;
        } catch (Exception ignored) {
        }
        try {
            long timestamp = LocalDate.parse(value).atStartOfDay(ZoneId.of(Constants.ZONE_ID)).toEpochSecond();
            return timestamp > 0 ? timestamp : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void putTraffic(AccountInfo info, String key, JsonNode total, JsonNode free) {
        long totalValue = total.asLong(0);
        if (totalValue <= 0) {
            return;
        }
        info.getAddition().put(key + "Total", totalValue);
        info.getAddition().put(key + "Used", Math.max(0, totalValue - free.asLong(0)));
    }

    // 对齐 JS extractTokenData 的多路径/多键容错:返回第一个非空白文本。
    private static String firstNonBlank(JsonNode... nodes) {
        for (var n : nodes) {
            if (n == null || n.isMissingNode() || n.isNull()) {
                continue;
            }
            String s = n.asText("");
            if (StringUtils.isNotBlank(s)) {
                return s.trim();
            }
        }
        return "";
    }

    private AccountInfo getQuarkCookie(String token) {
        long t = System.currentTimeMillis();
        var json = restTemplate.getForObject("https://uop.quark.cn/cas/ajax/getServiceTicketByQrcodeToken?client_id=532&v=1.2&token={token}&request_id={reqId}", ObjectNode.class, token, t);
        log.debug("getServiceTicketByQrcodeToken: {}", json);
        int status = json.get("status").asInt();
        String message = json.get("message").asText();
        if (status == 2000000) {
            String ticket = json.get("data").get("members").get("service_ticket").asText();
            var res = restTemplate.getForEntity("https://pan.quark.cn/account/info?st={st}&lw=scan", ObjectNode.class, ticket);
            log.debug("account info: {}", res.getBody());
            var info = new AccountInfo();
            info.setName(res.getBody().get("data").get("nickname").asText());
            List<String> cookies = new ArrayList<>(res.getHeaders().get(HttpHeaders.SET_COOKIE));
            String cookie = cookiesToString(cookies);
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.COOKIE, cookie);
            headers.set(HttpHeaders.REFERER, "https://pan.quark.cn");
            headers.set(HttpHeaders.USER_AGENT, Constants.QUARK_USER_AGENT);
            HttpEntity<Void> entity = new HttpEntity<>(null, headers);
            res = restTemplate.exchange("https://drive-pc.quark.cn/1/clouddrive/config?pr=ucpro&fr=pc&uc_param_str=", HttpMethod.GET, entity, ObjectNode.class);
            log.debug("config: {}", res.getBody());
            cookies.addAll(res.getHeaders().get(HttpHeaders.SET_COOKIE));
            cookie = cookiesToString(cookies);
            info.setCookie(cookie);
            log.debug("info: {}", info);
            return info;
        } else if (status == 50004002) {
            log.warn("{} {}", status, message);
            throw new BadRequestException("二维码无效或已过期！");
        } else if (status == 50004001) {
            log.warn("{} {}", status, message);
            throw new BadRequestException("等待用户扫码...");
        }
        throw new BadRequestException("未知错误： " + message);
    }

    private AccountInfo getUcCookie(String token) {
        long t = System.currentTimeMillis();
        var json = restTemplate.getForObject("https://api.open.uc.cn/cas/ajax/getServiceTicketByQrcodeToken?token={token}&__t={t}&client_id=381&v=1.2&request_id={t}", ObjectNode.class, token, t, t);
        log.debug("getServiceTicketByQrcodeToken: {}", json);
        int status = json.get("status").asInt();
        String message = json.get("message").asText();
        if (status == 2000000) {
            String ticket = json.get("data").get("members").get("service_ticket").asText();
            var res = restTemplate.getForEntity("https://drive.uc.cn/account/info?st={st}", ObjectNode.class, ticket);
            log.debug("account info: {}", res.getBody());
            var info = new AccountInfo();
            info.setName(res.getBody().get("data").get("nickname").asText());
            info.setId(String.valueOf(res.getBody().get("data").get("uid").asLong()));
            List<String> cookies = new ArrayList<>(res.getHeaders().get(HttpHeaders.SET_COOKIE));
            String cookie = cookiesToString(cookies);
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.COOKIE, cookie);
            headers.set(HttpHeaders.REFERER, "https://drive.uc.cn");
            headers.set(HttpHeaders.USER_AGENT, Constants.UC_USER_AGENT);
            HttpEntity<Void> entity = new HttpEntity<>(null, headers);
            res = restTemplate.exchange("https://pc-api.uc.cn/1/clouddrive/config?pr=UCBrowser&fr=pc&uc_param_str=", HttpMethod.GET, entity, ObjectNode.class);
            log.debug("config: {}", res.getBody());
            cookies.addAll(res.getHeaders().get(HttpHeaders.SET_COOKIE));
            cookie = cookiesToString(cookies);
            info.setCookie(cookie);
            log.debug("info: {}", info);
            return info;
        } else if (status == 50004002) {
            log.warn("{} {}", status, message);
            throw new BadRequestException("二维码无效或已过期！");
        } else if (status == 50004001) {
            log.warn("{} {}", status, message);
            throw new BadRequestException("等待用户扫码...");
        }
        throw new BadRequestException("未知错误： " + message);
    }

    private String cookiesToString(List<String> cookies) {
        List<String> cookieValues = new ArrayList<>();
        if (cookies != null) {
            for (String setCookie : cookies) {
                String cookie = setCookie.split(";")[0];
                cookieValues.add(cookie.trim());
            }
        }

        return String.join("; ", cookieValues);
    }

    public AccountInfo getInfo(DriverAccount account) {
        return switch (account.getType()) {
            case BAIDU -> getBaiduUserInfo(account);
            case PAN115 -> get115UserInfo(account);
            case OPEN123 -> get123UserInfo(account);
            case PAN123 -> get123WebUserInfo(account);
            case PAN139 -> get139UserInfo(account);
            case THUNDER -> getThunderUserInfo(account);
            case QUARK, QUARK_TV -> getQuarkUserInfo(account);
            case UC, UC_TV -> getUcUserInfo(account);
            case CLOUD189 -> get189UserInfo(account);
            case GUANGYA -> getGuangYaUserInfo(account);
            default -> null;
        };
    }

    private AccountInfo getBaiduUserInfo(DriverAccount account) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, account.getCookie().trim());
        headers.set(HttpHeaders.REFERER, "https://pan.baidu.com/disk/main");
        headers.set(HttpHeaders.USER_AGENT, "netdisk");
        HttpEntity<Void> entity = new HttpEntity<>(null, headers);
        String url = "https://pan.baidu.com/rest/2.0/membership/user/info?method=query&clienttype=0&app_id=250528&web=1&dp-logid=36187900205107340023";
        var json = restTemplate.exchange(url, HttpMethod.GET, entity, ObjectNode.class).getBody();
        log.debug("baidu user info: {}", json);
        var info = new AccountInfo();
        info.setName(json.get("user_info").get("username").asText());
        info.setId(String.valueOf(json.get("user_info").get("uk").asLong()));
        if (json.get("user_info").get("is_svip").asInt() > 0) {
            info.setVip("SVIP");
        } else if (json.get("user_info").get("is_vip").asInt() > 0) {
            info.setVip("VIP");
        }
        try {
            var quota = restTemplate.exchange("https://pan.baidu.com/api/quota?checkfree=1&checkexpire=1",
                    HttpMethod.GET, entity, ObjectNode.class).getBody();
            log.debug("baidu quota: {}", quota);
            if (quota != null && quota.path("errno").asInt(-1) == 0) {
                info.setUsedCapacity(quota.path("used").asLong(0));
                info.setTotalCapacity(quota.path("total").asLong(0));
            }
        } catch (Exception e) {
            log.warn("baidu quota failed", e);
        }
        return info;
    }

    private AccountInfo get115UserInfo(DriverAccount account) {
        var pattern = Pattern.compile("UID=(\\d+)");
        var matcher = pattern.matcher(account.getCookie());
        if (!matcher.find()) {
            return null;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, account.getCookie().trim());
        headers.set(HttpHeaders.REFERER, "https://115.com/");
        headers.set(HttpHeaders.ORIGIN, "https://115.com");
        headers.set(HttpHeaders.ACCEPT, Constants.ACCEPT);
        headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/111.0.0.0 Safari/537.36 115Browser/26.0.7.2");
        HttpEntity<Void> entity = new HttpEntity<>(null, headers);
        var json = restTemplate.exchange("https://my.115.com/?ct=ajax&ac=nav", HttpMethod.GET, entity, ObjectNode.class).getBody();
        log.debug("115 info: {}", json);
        ObjectNode data = json == null ? null : object(json, "data");
        if (data == null) {
            throw new BadRequestException("115云盘账号信息获取失败");
        }
        var info = new AccountInfo();
        info.setName(data.path("user_name").asText());
        info.setId(data.path("user_id").asText(matcher.group(1)));
        info.setVip(truthy(data.path("vip")) ? "VIP" : "普通用户");
        info.setExpireAt(parseExpireAt(data.path("expire")));
        try {
            ObjectNode spaceJson = restTemplate.exchange("https://proapi.115.com/android/user/space_info", HttpMethod.GET,
                    entity, ObjectNode.class).getBody();
            log.debug("115 space info: {}", json);
            ObjectNode space = spaceJson == null ? null : object(spaceJson, "data");
            if (space != null) {
                info.setTotalCapacity(space.path("all_total").path("size").asLong(0));
                info.setUsedCapacity(space.path("all_use").path("size").asLong(0));
            }
        } catch (Exception e) {
            log.warn("115 capacity query failed: {}", e.getMessage());
        }
        return info;
    }

    private AccountInfo getQuarkUserInfo(DriverAccount account) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, account.getCookie().trim());
        headers.set(HttpHeaders.REFERER, "https://pan.quark.cn/");
        headers.set(HttpHeaders.USER_AGENT, Constants.QUARK_USER_AGENT);
        HttpEntity<Void> entity = new HttpEntity<>(null, headers);
        String url = "https://drive-pc.quark.cn/1/clouddrive/member?pr=ucpro&fr=pc&uc_param_str=&fetch_subscribe=true&_ch=home&fetch_identity=true";
        var json = restTemplate.exchange(url, HttpMethod.GET, entity, ObjectNode.class).getBody();
        log.debug("quark info: {}", json);
        var info = new AccountInfo();
        var data = json.get("data");
        String memberType = data.get("member_type").asText();
        info.setVip(memberType);
        info.setUsedCapacity(data.path("use_capacity").asLong(0));
        info.setTotalCapacity(data.path("total_capacity").asLong(0));
        long exp = "SUPER_VIP".equals(memberType) ? data.path("super_vip_exp_at").asLong(0) : data.path("exp_at").asLong(0);
        info.setExpireAt(exp > 0 ? exp : null);

        url = "https://pan.quark.cn/account/info?fr=pc&platform=pc";
        json = restTemplate.exchange(url, HttpMethod.GET, entity, ObjectNode.class).getBody();
        log.debug("quark user info: {}", json);
        info.setName(json.get("data").get("nickname").asText());
        return info;
    }

    private AccountInfo getUcUserInfo(DriverAccount account) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, account.getCookie().trim());
        headers.set(HttpHeaders.REFERER, "https://drive.uc.cn/");
        headers.set(HttpHeaders.USER_AGENT, Constants.USER_AGENT);
        HttpEntity<Void> entity = new HttpEntity<>(null, headers);
        String url = "https://pc-api.uc.cn/1/clouddrive/member?pr=UCBrowser&fr=pc&fetch_subscribe=true&_ch=home";
        var json = restTemplate.exchange(url, HttpMethod.GET, entity, ObjectNode.class).getBody();
        log.debug("UC info: {}", json);
        var info = new AccountInfo();
        var data = json.get("data");
        String memberType = data.get("member_type").asText();
        info.setVip(memberType);
        info.setUsedCapacity(data.path("use_capacity").asLong(0));
        info.setTotalCapacity(data.path("total_capacity").asLong(0));
        if (!"NORMAL".equals(memberType)) {
            long exp = data.path("super_vip_exp_at").asLong(0);
            info.setExpireAt(exp > 0 ? exp : null);
        }

        url = "https://drive.uc.cn/account/info?fr=pc&platform=pc";
        json = restTemplate.exchange(url, HttpMethod.GET, entity, ObjectNode.class).getBody();
        log.debug("UC user info: {}", json);
        info.setId(String.valueOf(json.get("data").get("uid").asLong()));
        info.setName(json.get("data").get("nickname").asText());
        return info;
    }

    private AccountInfo get123UserInfo(DriverAccount account) {
        Map<String, Object> addition = readAddition(account.getAddition());
        String token = StringUtils.defaultIfBlank(getOpen123RuntimeAccessToken(account),
                StringUtils.defaultIfBlank(account.getToken(), text(addition.get("access_token"))));
        if (StringUtils.isBlank(token)) {
            throw new BadRequestException("123 Open账号信息需要 Access Token");
        }
        if (token.startsWith("Bearer ")) {
            token = token.substring("Bearer ".length()).trim();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set(HttpHeaders.ACCEPT, Constants.ACCEPT);
        headers.set("platform", "open_platform");
        headers.setContentType(MediaType.APPLICATION_JSON);
        ObjectNode json = restTemplate.exchange("https://open-api.123pan.com/api/v1/user/info", HttpMethod.GET,
                new HttpEntity<Void>(headers), ObjectNode.class).getBody();
        ObjectNode data = json == null ? null : object(json, "data");
        if (data == null) {
            String message = json == null ? "empty response" : json.path("message").asText("invalid response");
            throw new BadRequestException("123 Open账号信息获取失败: " + message);
        }
        return build123UserInfo(data);
    }

    // 网页版 123云盘(PAN123):凭证在内嵌 AList 侧登录维护(x_storages addition 的 accesstoken/loginuuid),
    // 对齐官网前端契约(api.123278.com/b/api/user/info,Bearer + loginuuid + platform=web)。
    private AccountInfo get123WebUserInfo(DriverAccount account) {
        String token = getStorageAdditionValue(account, "accesstoken");
        if (StringUtils.isBlank(token)) {
            token = StringUtils.defaultIfBlank(account.getToken(), text(readAddition(account.getAddition()).get("access_token")));
        }
        if (StringUtils.isBlank(token)) {
            throw new BadRequestException("123网盘账号信息获取失败: 未获取到 AccessToken,请确认账号已在网盘账号页保存并登录成功");
        }
        if (token.startsWith("Bearer ")) {
            token = token.substring("Bearer ".length()).trim();
        }
        String loginUuid = getStorageAdditionValue(account, "loginuuid");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set(HttpHeaders.ACCEPT, Constants.ACCEPT);
        headers.set(HttpHeaders.USER_AGENT, Constants.USER_AGENT);
        headers.set(HttpHeaders.ORIGIN, "https://yun.123pan.cn");
        headers.set(HttpHeaders.REFERER, "https://yun.123pan.cn/");
        headers.set("platform", "web");
        headers.set("app-version", "3");
        if (StringUtils.isNotBlank(loginUuid)) {
            headers.set("loginuuid", loginUuid);
        }
        String url = "https://api.123278.com/b/api/user/info?1597486751=" + generate123AuthKey();
        ObjectNode json = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<Void>(headers), ObjectNode.class).getBody();
        ObjectNode data = json == null ? null : object(json, "data");
        if (data == null) {
            String message = json == null ? "empty response" : json.path("message").asText("invalid response");
            throw new BadRequestException("123网盘账号信息获取失败: " + message);
        }
        return build123UserInfo(data);
    }

    // 对齐官网前端的 auth-key 形参(名即固定数字串):秒级时间戳-9位随机-32位hex
    private static String generate123AuthKey() {
        int random = SECURE_RANDOM.nextInt(900000000) + 100000000;
        return System.currentTimeMillis() / 1000 + "-" + random + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    // open-api(camelCase)与 web API(PascalCase)双口径兼容解析
    private AccountInfo build123UserInfo(ObjectNode data) {
        var info = new AccountInfo();
        info.setId(firstNonBlank(data.path("UID"), data.path("uid"), data.path("user_id"), data.path("userId")));
        info.setName(firstNonBlank(data.path("Nickname"), data.path("NickName"), data.path("UserName"), data.path("displayName"),
                data.path("username"), data.path("Passport"), data.path("passport"), data.path("Mail"),
                data.path("mail"), data.path("Phone"), data.path("phone")));
        boolean vip = truthy(data.path("IsVip")) || truthy(data.path("isVip")) || truthy(data.path("VIP"))
                || truthy(data.path("vip")) || truthy(data.path("Vip")) || truthy(data.path("IsMember")) || truthy(data.path("isMember"));
        info.setVip(StringUtils.defaultIfBlank(firstNonBlank(data.path("VipName"), data.path("vipName")), vip ? "VIP" : "普通用户"));
        info.setExpireAt(parseExpireAt(firstPresent(data.path("VipExpire"), data.path("vipExpire"),
                data.path("ExpireTime"), data.path("expireTime"), data.path("Expire"), data.path("expire"))));
        info.setUsedCapacity(data.path("SpaceUsed").asLong(data.path("spaceUsed").asLong(data.path("UsedSize").asLong(data.path("usedSize").asLong(0)))));
        long permanent = data.path("SpacePermanent").asLong(data.path("spacePermanent").asLong(data.path("PermanentSpace").asLong(data.path("permanentSpace").asLong(0))));
        long temporary = data.path("SpaceTemp").asLong(data.path("spaceTemp").asLong(data.path("TempSpace").asLong(data.path("tempSpace").asLong(0))));
        long total = data.path("SpaceTotal").asLong(data.path("spaceTotal").asLong(data.path("TotalSize").asLong(data.path("totalSize").asLong(data.path("Quota").asLong(data.path("quota").asLong(0))))));
        info.setTotalCapacity(total > 0 ? total : permanent + temporary);
        info.getAddition().put("permanentCapacity", permanent);
        info.getAddition().put("temporaryCapacity", temporary);
        info.getAddition().put("temporaryExpireAt", parseExpireAt(firstPresent(data.path("SpaceTempExpr"), data.path("spaceTempExpr"))));
        info.getAddition().put("fileCount", data.path("FileCount").asLong(data.path("fileCount").asLong(0)));
        return info;
    }

    private String getOpen123RuntimeAccessToken(DriverAccount account) {
        return getStorageAdditionValue(account, "AccessToken", "access_token");
    }

    // 读内嵌 AList x_storages addition 里驱动回写的运行时字段(如 123 系的 accesstoken/loginuuid)
    private String getStorageAdditionValue(DriverAccount account, String... keys) {
        if (account.getId() == null) {
            return "";
        }
        try {
            String addition = alistJdbcTemplate.queryForObject("SELECT addition FROM x_storages WHERE id = ?",
                    String.class, IDX + account.getId());
            Map<String, Object> values = readAddition(addition);
            for (String key : keys) {
                String value = text(values.get(key));
                if (StringUtils.isNotBlank(value)) {
                    return value;
                }
            }
        } catch (Exception e) {
            log.debug("storage addition unavailable for account {}: {}", account.getId(), e.getMessage());
        }
        return "";
    }

    private AccountInfo getThunderUserInfo(DriverAccount account) {
        if (StringUtils.isBlank(account.getToken())) {
            throw new BadRequestException("迅雷云盘账号信息需要 Token");
        }
        String token = account.getToken().trim();
        if (token.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            token = token.substring("Bearer ".length()).trim();
        }
        String deviceId = StringUtils.defaultIfBlank(text(readAddition(account.getAddition()).get("device_id")),
                Utils.md5(account.getUsername() + account.getPassword()));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set(HttpHeaders.ACCEPT, "application/json;charset=UTF-8");
        headers.set(HttpHeaders.USER_AGENT, THUNDER_ABOUT_USER_AGENT);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Client-Id", THUNDER_CLIENT_ID);
        headers.set("X-Device-Id", deviceId);

        var info = new AccountInfo();
        info.setName(account.getUsername());
        info.setVip("普通用户");
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        try {
            headers.set("X-Captcha-Token", requestThunderCaptchaToken(token, deviceId));
            entity = new HttpEntity<>(headers);
            ObjectNode json = restTemplate.exchange("https://api-pan.xunlei.com/drive/v1/about", HttpMethod.GET,
                    entity, ObjectNode.class).getBody();
            log.debug("thunder drive info: {}", json);
            ObjectNode about = responseData(json);
            if (about != null) {
                ObjectNode quota = object(about, "quota");
                if (quota != null) {
                    info.setTotalCapacity(quota.path("limit").asLong(0));
                    info.setUsedCapacity(quota.path("usage").asLong(0));
                }
                ObjectNode expires = object(about, "expires_at");
                if (expires != null) {
                    info.setExpireAt(parseExpireAt(expires.path("value")));
                }
            }
        } catch (Exception e) {
            log.warn("thunder capacity query failed: {}", e.getMessage());
        }
        try {
            ObjectNode json = restTemplate.exchange("https://xluser-ssl.xunlei.com/v1/user/me", HttpMethod.GET,
                    entity, ObjectNode.class).getBody();
            log.debug("thunder user info: {}", json);
            ObjectNode user = responseData(json);
            if (user != null) {
                info.setId(firstNonBlank(user.path("user_id"), user.path("id")));
                info.setName(StringUtils.defaultIfBlank(user.path("name").asText(), account.getUsername()));
                info.setVip(StringUtils.defaultIfBlank(user.path("vip_type").asText(), "普通用户"));
                String phone = user.path("phone_number").asText("");
                if (StringUtils.isNotBlank(phone)) {
                    info.getAddition().put("phone", phone);
                }
            }
        } catch (Exception e) {
            log.warn("thunder user query failed: {}", e.getMessage());
        }
        return info;
    }

    private String requestThunderCaptchaToken(String token, String deviceId) {
        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("package_name", THUNDER_PACKAGE_NAME);
        meta.put("client_version", THUNDER_CAPTCHA_CLIENT_VERSION);
        meta.put("captcha_sign", thunderCaptchaSign(deviceId));
        meta.put("timestamp", THUNDER_CAPTCHA_TIMESTAMP);
        meta.put("user_id", thunderUserId(token));

        ObjectNode body = objectMapper.createObjectNode();
        body.put("client_id", THUNDER_CLIENT_ID);
        body.put("action", "get:drive/v1/about");
        body.put("device_id", deviceId);
        body.put("captcha_token", "");
        body.set("meta", meta);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.USER_AGENT, THUNDER_CAPTCHA_USER_AGENT);
        ObjectNode response = restTemplate.exchange("https://xluser-ssl.xunlei.com/v1/shield/captcha/init",
                HttpMethod.POST, new HttpEntity<>(body.toString(), headers), ObjectNode.class).getBody();
        String captchaToken = response == null ? "" : response.path("captcha_token").asText();
        if (StringUtils.isBlank(captchaToken)) {
            throw new BadRequestException("迅雷云盘验证码令牌获取失败");
        }
        return captchaToken;
    }

    private String thunderUserId(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return "";
            }
            return objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1])).path("sub").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private String thunderCaptchaSign(String deviceId) {
        String value = THUNDER_CLIENT_ID + THUNDER_CAPTCHA_CLIENT_VERSION + THUNDER_PACKAGE_NAME + deviceId
                + THUNDER_CAPTCHA_TIMESTAMP;
        for (String algorithm : THUNDER_CAPTCHA_ALGORITHMS) {
            value = Utils.md5(value + algorithm);
        }
        return "1." + value;
    }

    private AccountInfo getGuangYaUserInfo(DriverAccount account) {
        Map<String, Object> addition = readAddition(account.getAddition());
        String accessToken = StringUtils.defaultIfBlank(account.getToken(), text(addition.get("access_token")));
        if (StringUtils.isBlank(accessToken)) {
            return null;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.REFERER, "https://www.guangyapan.com/");
        HttpEntity<Void> entity = new HttpEntity<>(null, headers);
        String url = GY_ACCOUNT_API + "/v1/user/me";
        var json = restTemplate.exchange(url, HttpMethod.GET, entity, ObjectNode.class).getBody();
        log.debug("GuangYa user info: {}", json);
        var info = new AccountInfo();
        info.setVip("普通用户");
        info.setName(firstNonBlank(json.path("name"), json.path("phone"), json.path("sub")));
        info.setId(json.path("sub").asText());
        try {
            String deviceId = text(addition.get("device_id"));
            String clientId = extractGuangYaClientId(accessToken);
            HttpHeaders assetHeaders = guangYaHeaders(deviceId);
            assetHeaders.setBearerAuth(accessToken);
            assetHeaders.set("X-Client-Id", clientId);
            assetHeaders.set(HttpHeaders.REFERER, "https://www.guangyapan.com/");
            assetHeaders.set(HttpHeaders.USER_AGENT, "ANDROID-com.guangshanyun.pan/1.0.0 protocolversion/200 accesstype/ clientid/"
                    + clientId + " clientversion/1.0.0 deviceid/" + deviceId + " sdkversion/2.0.7");
            assetHeaders.set("app", "com.guangshanyun.pan");
            assetHeaders.set("client_id", clientId);
            assetHeaders.set("dt", "1");
            assetHeaders.set("nt", "1");
            assetHeaders.set("vc", "1012");
            assetHeaders.set("did", deviceId);
            ObjectNode assetsJson = restTemplate.exchange("https://api.guangyapan.com/assets/v1/get_assets",
                    HttpMethod.POST, new HttpEntity<>(Map.of(), assetHeaders), ObjectNode.class).getBody();
            log.debug("GuangYa space info: {}", assetsJson);
            ObjectNode assets = object(assetsJson, "data");
            if (assets == null) {
                assets = assetsJson;
            }
            if (assets != null) {
                boolean svip = truthy(assets.path("svipStatus"));
                boolean vip = truthy(assets.path("vipStatus"));
                long expireAt = assets.path("vipExpireTime").asLong(0);
                long systemTime = assets.path("systemTime").asLong(System.currentTimeMillis() / 1000);
                boolean activeMembership = expireAt > systemTime;
                info.setVip(activeMembership && svip ? "SVIP" : activeMembership && vip ? "VIP" : "普通用户");
                info.setExpireAt(activeMembership ? expireAt : null);
                info.setTotalCapacity(assets.path("totalSpaceSize").asLong(0));
                info.setUsedCapacity(assets.path("usedSpaceSize").asLong(0));
                ObjectNode highSpeedTraffic = object(assets, "highSpeedTraffic");
                if (highSpeedTraffic != null) {
                    putTraffic(info, "highSpeedTraffic", highSpeedTraffic.path("total"), highSpeedTraffic.path("remained"));
                }
                putTraffic(info, "directLinkTraffic", assets.path("totalDirectLinkTraffic"), assets.path("freeDirectLinkTraffic"));
                putTraffic(info, "shareGuestTraffic", assets.path("totalShareGuestTraffic"), assets.path("freeShareGuestTraffic"));
            }
        } catch (Exception e) {
            log.warn("GuangYa assets query failed: {}", e.getMessage());
        }
        return info;
    }

    private String extractGuangYaClientId(String accessToken) {
        try {
            String[] parts = accessToken.split("\\.");
            if (parts.length < 2) {
                return GY_CLIENT_ID;
            }
            JsonNode claims = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            return StringUtils.defaultIfBlank(claims.path("aud").asText(), GY_CLIENT_ID);
        } catch (Exception e) {
            return GY_CLIENT_ID;
        }
    }

    private AccountInfo get189UserInfo(DriverAccount account) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, account.getCookie().trim());
        headers.set(HttpHeaders.ACCEPT, "application/json;charset=UTF-8");
        HttpEntity<Void> entity = new HttpEntity<>(null, headers);
        var info = new AccountInfo();
        info.setName(account.getUsername());
        info.setVip("普通用户");
        try {
            String url = "https://cloud.189.cn/api/open/user/getUserInfoForPortal.action?noCache=" + System.currentTimeMillis();
            var json = restTemplate.exchange(url, HttpMethod.GET, entity, ObjectNode.class).getBody();
            log.debug("189 user info: {}", json);
            var user = json == null ? null : object(json, "userExtResp");
            if (json != null) {
                String name = firstNonBlank(user == null ? null : user.path("nickName"), user == null ? null : user.path("nickname"),
                        user == null ? null : user.path("account"), json.path("nickName"), json.path("nickname"), json.path("account"));
                if (StringUtils.isNotBlank(name)) {
                    info.setName(name);
                }
                info.setId(firstNonBlank(user == null ? null : user.path("account"), json.path("account")));
            }
        } catch (Exception e) {
            log.warn("189 user profile query failed: {}", e.getMessage());
        }
        try {
            var json = restTemplate.exchange("https://cloud.189.cn/api/portal/getUserSizeInfo.action",
                    HttpMethod.GET, entity, ObjectNode.class).getBody();
            log.debug("189 user profile size: {}", json);
            var capacity = json == null ? null : object(json, "cloudCapacityInfo");
            if (capacity != null) {
                info.setTotalCapacity(capacity.path("totalSize").asLong(0));
                info.setUsedCapacity(capacity.path("usedSize").asLong(0));
            }
        } catch (Exception e) {
            log.warn("189 capacity query failed: {}", e.getMessage());
        }
        try {
            var json = restTemplate.exchange("https://cloud.189.cn/api/order/queryUserLogo.action",
                    HttpMethod.GET, entity, ObjectNode.class).getBody();
            var vipList = json == null ? null : json.path("data").path("vipInfoList");
            if (vipList != null && vipList.isArray()) {
                for (var vip : vipList) {
                    if (!truthy(vip.path("isVip"))) {
                        continue;
                    }
                    info.setVip(StringUtils.defaultIfBlank(firstNonBlank(vip.path("vipName"), vip.path("vipTypeName"),
                            vip.path("memberName"), vip.path("productName"), vip.path("name"), vip.path("title")), "VIP"));
                    info.setExpireAt(parseExpireAt(firstPresent(vip.path("expire_time"), vip.path("expireTime"),
                            vip.path("expireDate"), vip.path("endTime"), vip.path("endDate"))));
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("189 membership query failed: {}", e.getMessage());
        }
        return info;
    }

    private AccountInfo get139UserInfo(DriverAccount account) {
        String token = account.getToken();
        String phone = extract139Account(token);
        var info = new AccountInfo();
        if (StringUtils.isBlank(phone)) {
            return info;
        }
        info.setId(phone);
        info.setName(maskPhone(phone));
        try {
            // user-njs 接口标准 body:commonAccountInfo 携带账号。userDomainId 由服务端按账号解析(缺失时容错)。
            ObjectNode bodyNode = objectMapper.createObjectNode();
            ObjectNode common = bodyNode.putObject("commonAccountInfo");
            common.put("account", phone);
            common.put("accountType", 1);
            String body = objectMapper.writeValueAsString(bodyNode);
            ObjectNode data = post139(token, "https://user-njs.yun.139.com/user/disk/quota/detail", body);
            if (data != null) {
                long diskSize = data.path("diskSize").asLong(0);      // MB
                long free = data.path("freeDiskSize").asLong(0);      // MB
                if (diskSize > 0) {
                    info.setTotalCapacity(diskSize * 1024L * 1024L);
                    info.setUsedCapacity(Math.max(0, diskSize - free) * 1024L * 1024L);
                }
            }
        } catch (Exception e) {
            log.warn("139 quota failed", e);
        }
        return info;
    }

    private ObjectNode post139(String token, String url, String body) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String rand = randomAlnum(16);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Basic " + token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.ACCEPT, "application/json, text/plain, */*");
        headers.set("caller", "web");
        headers.set("mcloud-channel", "1000101");
        headers.set("mcloud-client", "10701");
        headers.set("mcloud-version", "7.17.4");
        headers.set("mcloud-sign", ts + "," + rand + "," + calSign139(body, ts, rand));
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        var json = restTemplate.postForObject(url, entity, ObjectNode.class);
        log.debug("139 {}: {}", url, json);
        if (json == null) {
            return null;
        }
        var data = json.path("data");
        return data.isObject() ? (ObjectNode) data : null;
    }

    // 139 token(base64)=pc:<phone>:<secret>|...;取第二段为账号。
    private static String extract139Account(String token) {
        if (StringUtils.isBlank(token)) {
            return "";
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(token.trim()), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":");
            return parts.length > 1 ? parts[1].trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    // 对齐 AList drivers/139/util.go calSign:encodeURIComponent→排序→base64→两次 md5 拼接再 md5 大写。
    private static String calSign139(String body, String ts, String rand) {
        String enc = encodeURIComponent139(body);
        char[] chars = enc.toCharArray();
        Arrays.sort(chars);
        String b64 = Base64.getEncoder().encodeToString(new String(chars).getBytes(StandardCharsets.UTF_8));
        String res = Utils.md5(b64) + Utils.md5(ts + ":" + rand);
        return Utils.md5(res).toUpperCase();
    }

    // JS encodeURIComponent 语义:URLEncoder 再把 +→%20 并恢复 !'()~。
    private static String encodeURIComponent139(String s) {
        String result = URLEncoder.encode(s, StandardCharsets.UTF_8);
        return result.replace("+", "%20")
                .replace("%21", "!").replace("%27", "'").replace("%28", "(")
                .replace("%29", ")").replace("%7E", "~");
    }

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static String randomAlnum(int len) {
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(chars.charAt(SECURE_RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
