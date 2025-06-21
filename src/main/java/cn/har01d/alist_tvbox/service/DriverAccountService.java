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
import cn.har01d.alist_tvbox.util.BiliBiliUtils;
import cn.har01d.alist_tvbox.util.Constants;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Service
public class DriverAccountService {
    public static final int IDX = 4000;
    private static final Set<DriverType> TOKEN_TYPES = Set.of(DriverType.OPEN115, DriverType.PAN139, DriverType.BAIDU);
    private static final Set<DriverType> COOKIE_TYPES = Set.of(DriverType.PAN115, DriverType.QUARK, DriverType.UC);
    private final PanAccountRepository panAccountRepository;
    private final DriverAccountRepository driverAccountRepository;
    private final SettingRepository settingRepository;
    private final ShareRepository shareRepository;
    private final AccountService accountService;
    private final AListLocalService aListLocalService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, QuarkUCTV> drivers = new HashMap<>();

    public DriverAccountService(PanAccountRepository panAccountRepository,
                                DriverAccountRepository driverAccountRepository,
                                SettingRepository settingRepository,
                                ShareRepository shareRepository,
                                AccountService accountService,
                                AListLocalService aListLocalService,
                                RestTemplateBuilder builder,
                                ObjectMapper objectMapper) {
        this.panAccountRepository = panAccountRepository;
        this.driverAccountRepository = driverAccountRepository;
        this.settingRepository = settingRepository;
        this.shareRepository = shareRepository;
        this.accountService = accountService;
        this.aListLocalService = aListLocalService;
        this.restTemplate = builder.build();
        this.objectMapper = objectMapper;
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
        if (driverAccountRepository.count() == 0) {
            aListLocalService.startAListServer();
        }
        account.setId(null);
        if (driverAccountRepository.countByType(account.getType()) == 0) {
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
        if (TOKEN_TYPES.contains(account.getType())) {
            account.setToken(dto.getToken());
        } else {
            account.setCookie(dto.getToken());
        }
        driverAccountRepository.save(account);
    }

    public DriverAccount update(Integer id, DriverAccount dto) {
        validate(dto);
        var account = get(id);
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
        if (dto.getType() == DriverType.BAIDU && (StringUtils.isNotBlank(dto.getAddition())) && !"{}".equals(dto.getAddition())) {
            account.setToken("");
        }

        if (driverAccountRepository.countByType(account.getType()) <= 1) {
            account.setMaster(true);
        }

        if (changed && account.isMaster()) {
            updateMaster(account);
        }

        driverAccountRepository.save(account);

        updateStorage(account);

        return account;
    }

    public void delete(Integer id) {
        DriverAccount account = driverAccountRepository.findById(id).orElse(null);
        if (account != null) {
            driverAccountRepository.deleteById(id);
            String token = accountService.login();
            accountService.deleteStorage(IDX + account.getId(), token);
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
        } else if (StringUtils.isBlank(dto.getCookie()) && StringUtils.isBlank(dto.getToken())) {
            throw new BadRequestException("Cookie和Token不能同时为空");
        }
        if (StringUtils.isBlank(dto.getFolder())) {
            if (dto.getType() == DriverType.QUARK || dto.getType() == DriverType.UC || dto.getType() == DriverType.QUARK_TV || dto.getType() == DriverType.UC_TV || dto.getType() == DriverType.PAN115 || dto.getType() == DriverType.OPEN115 || dto.getType() == DriverType.PAN123) {
                dto.setFolder("0");
            } else if (dto.getType() == DriverType.CLOUD189) {
                dto.setFolder("-11");
            } else if (dto.getType() == DriverType.BAIDU) {
                dto.setFolder("/");
            }
        }
        if (dto.getCookie() != null) {
            dto.setCookie(dto.getCookie().trim());
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
                Utils.executeUpdate("DELETE FROM x_storages WHERE id = " + id);
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
        QuarkUCTV driver = drivers.get(type);
        if (driver == null) {
            throw new BadRequestException("不支持的类型");
        }
        return driver.getLoginCode();
    }

    private QuarkUCTV.LoginResponse getQuarkQr() throws IOException {
        long t = System.currentTimeMillis();
        var json = restTemplate.getForObject("https://uop.quark.cn/cas/ajax/getTokenForQrcodeLogin?client_id=532&v=1.2&request_id={t}", ObjectNode.class, t);
        String token = json.get("data").get("members").get("token").asText();
        String qr = BiliBiliUtils.getQrCode("https://su.quark.cn/4_eMHBJ?token=" + token + "&client_id=532&ssb=weblogin&uc_param_str=&uc_biz_str=S%3Acustom%7COPT%3ASAREA%400%7COPT%3AIMMERSIVE%401%7COPT%3ABACK_BTN_STYLE%400");
        var res = new QuarkUCTV.LoginResponse();
        res.setQueryToken(token);
        res.setQrData(qr);
        return res;
    }

    private QuarkUCTV.LoginResponse getUcQr() throws IOException {
        long t = System.currentTimeMillis();
        var json = restTemplate.getForObject("https://api.open.uc.cn/cas/ajax/getTokenForQrcodeLogin?client_id=381&v=1.2&request_id={t}", ObjectNode.class, t);
        String token = json.get("data").get("members").get("token").asText();
        String qr = BiliBiliUtils.getQrCode("https://su.uc.cn/1_n0ZCv?uc_param_str=dsdnfrpfbivesscpgimibtbmnijblauputogpintnwktprchmt&token=" + token + "&client_id=381&uc_biz_str=S%3Acustom%7CC%3Atitlebar_fix");
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
            case QUARK -> getQuarkUserInfo(account);
            case UC -> getUcUserInfo(account);
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
        var info = new AccountInfo();
        info.setName(json.get("user_info").get("username").asText());
        info.setId(String.valueOf(json.get("user_info").get("uk").asLong()));
        if (json.get("user_info").get("is_svip").asInt() > 0) {
            info.setVip("SVIP");
        } else if (json.get("user_info").get("is_vip").asInt() > 0) {
            info.setVip("VIP");
        }
        return info;
    }

    private AccountInfo get115UserInfo(DriverAccount account) {
        var pattern = Pattern.compile("UID=(\\d+)");
        var matcher = pattern.matcher(account.getCookie());
        if (!matcher.find()) {
            return null;
        }
        String uid = matcher.group(1);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, account.getCookie().trim());
        headers.set(HttpHeaders.REFERER, "https://115.com/");
        headers.set(HttpHeaders.USER_AGENT, Constants.USER_AGENT);
        HttpEntity<Void> entity = new HttpEntity<>(null, headers);
        String url = "https://my.115.com/proapi/3.0/index.php?method=user_info&uid=" + uid;
        var json = restTemplate.exchange(url, HttpMethod.GET, entity, ObjectNode.class).getBody();
        var info = new AccountInfo();
        info.setName(json.get("data").get("user_name").asText());
        info.setId(uid);
        info.setVip(String.valueOf(json.get("data").get("is_vip").asInt()));
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
        var info = new AccountInfo();
        info.setVip(json.get("data").get("member_type").asText());

        url = "https://pan.quark.cn/account/info?fr=pc&platform=pc";
        json = restTemplate.exchange(url, HttpMethod.GET, entity, ObjectNode.class).getBody();
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
        var info = new AccountInfo();
        info.setVip(json.get("data").get("member_type").asText());

        url = "https://drive.uc.cn/account/info?fr=pc&platform=pc";
        json = restTemplate.exchange(url, HttpMethod.GET, entity, ObjectNode.class).getBody();
        info.setId(String.valueOf(json.get("data").get("uid").asLong()));
        info.setName(json.get("data").get("nickname").asText());
        return info;
    }
}
