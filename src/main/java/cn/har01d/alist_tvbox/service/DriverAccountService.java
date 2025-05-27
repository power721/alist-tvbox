package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.PanAccountRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Share;
import cn.har01d.alist_tvbox.entity.ShareRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import cn.har01d.alist_tvbox.util.BiliBiliUtils;
import cn.har01d.alist_tvbox.util.Constants;
import cn.har01d.alist_tvbox.util.Utils;
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
import java.util.Set;

@Slf4j
@Service
public class DriverAccountService {
    public static final int IDX = 4000;
    private static final Set<DriverType> TOKEN_TYPES = Set.of(DriverType.OPEN115, DriverType.PAN139);
    private static final Set<DriverType> COOKIE_TYPES = Set.of(DriverType.PAN115, DriverType.QUARK, DriverType.UC);
    private final PanAccountRepository panAccountRepository;
    private final DriverAccountRepository driverAccountRepository;
    private final SettingRepository settingRepository;
    private final ShareRepository shareRepository;
    private final AccountService accountService;
    private final AListLocalService aListLocalService;
    private final RestTemplate restTemplate;
    private final Map<String, QuarkUCTV> drivers = new HashMap<>();

    public DriverAccountService(PanAccountRepository panAccountRepository,
                                DriverAccountRepository driverAccountRepository,
                                SettingRepository settingRepository,
                                ShareRepository shareRepository,
                                AccountService accountService,
                                AListLocalService aListLocalService,
                                RestTemplateBuilder builder) {
        this.panAccountRepository = panAccountRepository;
        this.driverAccountRepository = driverAccountRepository;
        this.settingRepository = settingRepository;
        this.shareRepository = shareRepository;
        this.accountService = accountService;
        this.aListLocalService = aListLocalService;
        this.restTemplate = builder.build();
    }

    @PostConstruct
    public void init() {
        if (!settingRepository.existsByName("migrate_pan_account")) {
            migratePanAccounts();
        }
        if (!settingRepository.existsByName("migrate_driver_account")) {
            migrateDriverAccounts();
        }

        String deviceId = settingRepository.findById("quark_device_id").map(Setting::getValue).orElse(null);
        if (deviceId == null) {
            deviceId = QuarkUCTV.generateDeviceId();
            settingRepository.save(new Setting("quark_device_id", deviceId));
        }
        drivers.put("QUARK_TV", new QuarkUCTV(restTemplate, new QuarkUCTV.Conf("https://open-api-drive.quark.cn", "d3194e61504e493eb6222857bccfed94", "kw2dvtd7p4t3pjl2d9ed9yc8yej8kw2d", "1.5.6", "CP", "http://api.extscreen.com/quarkdrive", deviceId)));
        drivers.put("UC_TV", new QuarkUCTV(restTemplate, new QuarkUCTV.Conf("https://open-api-drive.uc.cn", "5acf882d27b74502b7040b0c65519aa7", "l3srvtd7p42l0d0x1u8d7yc8ye9kki4d", "1.6.5", "UCTVOFFICIALWEB", "http://api.extscreen.com/ucdrive", deviceId)));
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
            insertAList(account);
        }
    }

    private void insertAList(DriverAccount account) {
        String deviceId = settingRepository.findById("quark_device_id").map(Setting::getValue).orElse("");
        int id = account.getId() + IDX;
        if (account.getType() == DriverType.QUARK) {
            String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'Quark',30,'work','{\"cookie\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"file_name\",\"order_direction\":\"asc\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',0,'native_proxy','');";
            int count = Utils.executeUpdate(String.format(sql, id, getMountPath(account), account.getCookie(), account.getFolder()));
            log.info("insert Quark account {} : {}, result: {}", id, getMountPath(account), count);
        } else if (account.getType() == DriverType.QUARK_TV) {
            String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'QuarkTV',30,'work','{\"refresh_token\":\"%s\",\"device_id\":\"%s\",\"root_folder_id\":\"%s\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',0,'302_redirect','');";
            int count = Utils.executeUpdate(String.format(sql, id, getMountPath(account), account.getToken(), deviceId, account.getFolder()));
            log.info("insert QuarkTV account {} : {}, result: {}", id, getMountPath(account), count);
        } else if (account.getType() == DriverType.UC) {
            String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'UC',30,'work','{\"cookie\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"file_name\",\"order_direction\":\"asc\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',0,'native_proxy','');";
            int count = Utils.executeUpdate(String.format(sql, id, getMountPath(account), account.getCookie(), account.getFolder()));
            log.info("insert UC account {} : {}, result: {}", id, getMountPath(account), count);
        } else if (account.getType() == DriverType.UC_TV) {
            String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'UCTV',30,'work','{\"refresh_token\":\"%s\",\"device_id\":\"%s\",\"root_folder_id\":\"%s\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',0,'302_redirect','');";
            int count = Utils.executeUpdate(String.format(sql, id, getMountPath(account), account.getToken(), deviceId, account.getFolder()));
            log.info("insert UCTV account {} : {}, result: {}", id, getMountPath(account), count);
        } else if (account.getType() == DriverType.THUNDER) {
            String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'ThunderBrowser',30,'work','{\"username\":\"%s\",\"password\":\"%s\",\"safe_password\":\"%s\",\"root_folder_id\":\"%s\",\"remove_way\":\"delete\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',0,'302_redirect','');";
            int count = Utils.executeUpdate(String.format(sql, id, getMountPath(account), account.getUsername(), account.getPassword(), account.getSafePassword(), account.getFolder()));
            log.info("insert ThunderBrowser account {} : {}, result: {}", id, getMountPath(account), count);
        } else if (account.getType() == DriverType.CLOUD189) {
            String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'189CloudPC',30,'work','{\"username\":\"%s\",\"password\":\"%s\",\"validate_code\":\"%s\",\"root_folder_id\":\"%s\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',0,'302_redirect','');";
            int count = Utils.executeUpdate(String.format(sql, id, getMountPath(account), account.getUsername(), account.getPassword(), account.getToken(), account.getFolder()));
            log.info("insert 189CloudPC account {} : {}, result: {}", id, getMountPath(account), count);
        } else if (account.getType() == DriverType.PAN123) {
            String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'123Pan',30,'work','{\"username\":\"%s\",\"password\":\"%s\",\"root_folder_id\":\"%s\",\"platformType\":\"android\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',0,'302_redirect','');";
            int count = Utils.executeUpdate(String.format(sql, id, getMountPath(account), account.getUsername(), account.getPassword(), account.getFolder()));
            log.info("insert 123Pan account {} : {}, result: {}", id, getMountPath(account), count);
        } else if (account.getType() == DriverType.PAN139) {
            String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'139Yun',30,'work','{\"authorization\":\"%s\",\"root_folder_id\":\"%s\",\"type\":\"personal_new\",\"cloud_id\":\"\",\"custom_upload_part_size\":0}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',0,'302_redirect','');";
            int count = Utils.executeUpdate(String.format(sql, id, getMountPath(account), account.getToken(), account.getFolder()));
            log.info("insert 139Yun account {} : {}, result: {}", id, getMountPath(account), count);
        } else if (account.getType() == DriverType.PAN115) {
            String sql;
            sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'115 Cloud',30,'work','{\"cookie\":\"%s\",\"qrcode_token\":\"%s\",\"root_folder_id\":\"%s\",\"page_size\":1000}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',1,'%s','');";
            int count = Utils.executeUpdate(String.format(sql, id, getMountPath(account), account.getCookie(), account.getToken(), account.getFolder(), account.isUseProxy() ? "native_proxy" : "302_redirect"));
            log.info("insert 115 account {}: {}, result: {}", id, getMountPath(account), count);
        } else if (account.getType() == DriverType.OPEN115) {
            String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'115 Open',30,'work','{\"refresh_token\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"file_name\",\"order_direction\":\"asc\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',0,'302_redirect','');";
            int count = Utils.executeUpdate(String.format(sql, id, getMountPath(account), account.getToken(), account.getFolder()));
            log.info("insert 115 Open account {} : {}, result: {}", id, getMountPath(account), count);
        }
    }

    private void updateAList(DriverAccount account) {
        int id = account.getId() + IDX;
        if (account.getType() == DriverType.QUARK) {
            String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'Quark',30,'work','{\"cookie\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"file_name\",\"order_direction\":\"asc\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'native_proxy','',0,0,0);";
            int count = Utils.executeUpdate(String.format(sql, id, getMountPath(account), account.getCookie(), account.getFolder()));
            log.info("insert Quark account {} : {}, result: {}", id, getMountPath(account), count);
        } else if (account.getType() == DriverType.QUARK_TV) {
            String deviceId = settingRepository.findById("quark_device_id").map(Setting::getValue).orElse("");
            String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'QuarkTV',30,'work','{\"refresh_token\":\"%s\",\"device_id\":\"%s\",\"root_folder_id\":\"%s\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','',0,0,0);";
            int count = Utils.executeUpdate(String.format(sql, id, getMountPath(account), account.getToken(), deviceId, account.getFolder()));
            log.info("insert QuarkTV account {} : {}, result: {}", id, getMountPath(account), count);
        } else if (account.getType() == DriverType.UC) {
            String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'UC',30,'work','{\"cookie\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"file_name\",\"order_direction\":\"asc\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'native_proxy','',0,0,0);";
            int count = Utils.executeUpdate(String.format(sql, id, getMountPath(account), account.getCookie(), account.getFolder()));
            log.info("insert UC account {} : {}, result: {}", id, getMountPath(account), count);
        } else if (account.getType() == DriverType.UC_TV) {
            String deviceId = settingRepository.findById("quark_device_id").map(Setting::getValue).orElse("");
            String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'UCTV',30,'work','{\"refresh_token\":\"%s\",\"device_id\":\"%s\",\"root_folder_id\":\"%s\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','',0,0,0);";
            int count = Utils.executeUpdate(String.format(sql, id, getMountPath(account), account.getToken(), deviceId, account.getFolder()));
            log.info("insert UCTV account {} : {}, result: {}", id, getMountPath(account), count);
        } else if (account.getType() == DriverType.THUNDER) {
            String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'ThunderBrowser',30,'work','{\"username\":\"%s\",\"password\":\"%s\",\"safe_password\":\"%s\",\"root_folder_id\":\"%s\",\"remove_way\":\"delete\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','',0,0,0);";
            int count = Utils.executeUpdate(String.format(sql, id, getMountPath(account), account.getUsername(), account.getPassword(), account.getSafePassword(), account.getFolder()));
            log.info("insert ThunderBrowser account {} : {}, result: {}", id, getMountPath(account), count);
        } else if (account.getType() == DriverType.CLOUD189) {
            String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'189CloudPC',30,'work','{\"username\":\"%s\",\"password\":\"%s\",\"validate_code\":\"%s\",\"root_folder_id\":\"%s\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','',0,0,0);";
            int count = Utils.executeUpdate(String.format(sql, id, getMountPath(account), account.getUsername(), account.getPassword(), account.getToken(), account.getFolder()));
            log.info("insert 189CloudPC account {} : {}, result: {}", id, getMountPath(account), count);
        } else if (account.getType() == DriverType.PAN123) {
            String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'123Pan',30,'work','{\"username\":\"%s\",\"password\":\"%s\",\"root_folder_id\":\"%s\",\"platformType\":\"android\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','',0,0,0);";
            int count = Utils.executeUpdate(String.format(sql, id, getMountPath(account), account.getUsername(), account.getPassword(), account.getFolder()));
            log.info("insert 123Pan account {} : {}, result: {}", id, getMountPath(account), count);
        } else if (account.getType() == DriverType.PAN139) {
            String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'139Yun',30,'work','{\"authorization\":\"%s\",\"root_folder_id\":\"%s\",\"type\":\"personal_new\",\"cloud_id\":\"\",\"custom_upload_part_size\":0}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','',0,0,0);";
            int count = Utils.executeUpdate(String.format(sql, id, getMountPath(account), account.getToken(), account.getFolder()));
            log.info("insert 139Yun account {} : {}, result: {}", id, getMountPath(account), count);
        } else if (account.getType() == DriverType.PAN115) {
            String sql;
            sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'115 Cloud',30,'work','{\"cookie\":\"%s\",\"qrcode_token\":\"%s\",\"root_folder_id\":\"%s\",\"page_size\":1000}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',1,'%s','',0,0,0);";
            int count = Utils.executeUpdate(String.format(sql, id, getMountPath(account), account.getCookie(), account.getToken(), account.getFolder(), account.isUseProxy() ? "native_proxy" : "302_redirect"));
            log.info("insert 115 account {}: {}, result: {}", id, getMountPath(account), count);
        } else if (account.getType() == DriverType.OPEN115) {
            String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'115 Open',30,'work','{\"refresh_token\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"file_name\",\"order_direction\":\"asc\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','',0,0,0);";
            int count = Utils.executeUpdate(String.format(sql, id, getMountPath(account), account.getToken(), account.getFolder()));
            log.info("insert 115 Open account {} : {}, result: {}", id, getMountPath(account), count);
        }
    }

    private String getMountPath(DriverAccount account) {
        if (account.getName().startsWith("/")) {
            return account.getName();
        }
        if (account.getType() == DriverType.QUARK) {
            return "/\uD83C\uDF1E我的夸克网盘/" + account.getName();
        } else if (account.getType() == DriverType.UC) {
            return "/\uD83C\uDF1E我的UC网盘/" + account.getName();
        } else if (account.getType() == DriverType.QUARK_TV) {
            return "/我的夸克网盘/" + account.getName();
        } else if (account.getType() == DriverType.UC_TV) {
            return "/我的UC网盘/" + account.getName();
        } else if (account.getType() == DriverType.PAN115) {
            return "/115云盘/" + account.getName();
        } else if (account.getType() == DriverType.OPEN115) {
            return "/115网盘/" + account.getName();
        } else if (account.getType() == DriverType.THUNDER) {
            return "/我的迅雷云盘/" + account.getName();
        } else if (account.getType() == DriverType.CLOUD189) {
            return "/我的天翼云盘/" + account.getName();
        } else if (account.getType() == DriverType.PAN139) {
            return "/我的移动云盘/" + account.getName();
        } else if (account.getType() == DriverType.PAN123) {
            return "/我的123网盘/" + account.getName();
        }
        return "/网盘" + account.getName();
        // cn.har01d.alist_tvbox.service.TvBoxService.addMyFavorite
    }

    public List<DriverAccount> list() {
        return driverAccountRepository.findAll();
    }

    public DriverAccount get(int id) {
        return driverAccountRepository.findById(id).orElseThrow(NotFoundException::new);
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
                || !account.getType().equals(dto.getType())
                || !account.getToken().equals(dto.getToken())
                || !account.getCookie().equals(dto.getCookie())
                || !account.getFolder().equals(dto.getFolder())
                || !account.getName().equals(dto.getName());

        account.setMaster(dto.isMaster());
        account.setUseProxy(dto.isUseProxy());
        account.setName(dto.getName());
        account.setType(dto.getType());
        account.setCookie(dto.getCookie());
        account.setToken(dto.getToken());
        account.setUsername(dto.getUsername());
        account.setPassword(dto.getPassword());
        account.setSafePassword(dto.getSafePassword());
        account.setFolder(dto.getFolder());

        if (driverAccountRepository.countByType(account.getType()) == 0) {
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
            if (account.isMaster() && account.getType() != DriverType.UC_TV && account.getType() != DriverType.QUARK_TV) {
                throw new BadRequestException("不能删除主账号");
            }
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
        } else if (StringUtils.isBlank(dto.getCookie()) && StringUtils.isBlank(dto.getToken())) {
            throw new BadRequestException("Cookie和Token不能同时为空");
        }
        if (StringUtils.isBlank(dto.getFolder())) {
            if (dto.getType() == DriverType.QUARK || dto.getType() == DriverType.UC || dto.getType() == DriverType.QUARK_TV || dto.getType() == DriverType.UC_TV || dto.getType() == DriverType.PAN115 || dto.getType() == DriverType.OPEN115 || dto.getType() == DriverType.PAN123) {
                dto.setFolder("0");
            } else if (dto.getType() == DriverType.CLOUD189) {
                dto.setFolder("-11");
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
            String token = status == 2 ? accountService.login() : "";
            if (status == 2) {
                accountService.deleteStorage(id, token);
            }
            updateAList(account);
            if (status == 2) {
                accountService.enableStorage(id, token);
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

    public String getRefreshToken(String type, String queryToken) {
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
        return driver.getRefreshToken(code);
    }

    private String getQuarkCookie(String token) {
        long t = System.currentTimeMillis();
        var json = restTemplate.getForObject("https://uop.quark.cn/cas/ajax/getServiceTicketByQrcodeToken?client_id=532&v=1.2&token={token}&request_id={reqId}", ObjectNode.class, token, t);
        log.debug("getServiceTicketByQrcodeToken: {}", json);
        int status = json.get("status").asInt();
        String message = json.get("message").asText();
        if (status == 2000000) {
            String ticket = json.get("data").get("members").get("service_ticket").asText();
            var res = restTemplate.getForEntity("https://pan.quark.cn/account/info?st={st}&lw=scan", ObjectNode.class, ticket);
            log.debug("account info: {}", res.getBody());
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
            log.debug("cookie: {}", cookie);
            return cookie;
        } else if (status == 50004002) {
            log.warn("{} {}", status, message);
            throw new BadRequestException("二维码无效或已过期！");
        } else if (status == 50004001) {
            log.warn("{} {}", status, message);
            throw new BadRequestException("等待用户扫码...");
        }
        throw new BadRequestException("未知错误： " + message);
    }

    private String getUcCookie(String token) {
        long t = System.currentTimeMillis();
        var json = restTemplate.getForObject("https://api.open.uc.cn/cas/ajax/getServiceTicketByQrcodeToken?token={token}&__t={t}&client_id=381&v=1.2&request_id={t}", ObjectNode.class, token, t, t);
        log.debug("getServiceTicketByQrcodeToken: {}", json);
        int status = json.get("status").asInt();
        String message = json.get("message").asText();
        if (status == 2000000) {
            String ticket = json.get("data").get("members").get("service_ticket").asText();
            var res = restTemplate.getForEntity("https://drive.uc.cn/account/info?st={st}", ObjectNode.class, ticket);
            log.debug("account info: {}", res.getBody());
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
            log.debug("cookie: {}", cookie);
            return cookie;
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
}
