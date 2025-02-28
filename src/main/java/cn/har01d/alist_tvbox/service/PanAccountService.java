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
import cn.har01d.alist_tvbox.model.SettingResponse;
import cn.har01d.alist_tvbox.util.Utils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class PanAccountService {
    public static final int IDX = 4000;
    private final PanAccountRepository panAccountRepository;
    private final DriverAccountRepository driverAccountRepository;
    private final SettingRepository settingRepository;
    private final ShareRepository shareRepository;
    private final AccountService accountService;
    private final AListLocalService aListLocalService;

    public PanAccountService(PanAccountRepository panAccountRepository,
                             DriverAccountRepository driverAccountRepository,
                             SettingRepository settingRepository,
                             ShareRepository shareRepository,
                             AccountService accountService,
                             AListLocalService aListLocalService) {
        this.panAccountRepository = panAccountRepository;
        this.driverAccountRepository = driverAccountRepository;
        this.settingRepository = settingRepository;
        this.shareRepository = shareRepository;
        this.accountService = accountService;
        this.aListLocalService = aListLocalService;
    }

    @PostConstruct
    public void init() {
        if (!settingRepository.existsByName("migrate_pan_account")) {
            migratePanAccounts();
        }
        if (!settingRepository.existsByName("migrate_driver_account")) {
            migrateDriverAccounts();
        }
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
            insertAList(account);
        }
    }

    private void insertAList(DriverAccount account) {
        int id = account.getId() + IDX;
        if (account.getType() == DriverType.QUARK) {
            String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'Quark',30,'work','{\"cookie\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"ASC\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',0,'native_proxy','');";
            int count = Utils.executeUpdate(String.format(sql, id, getMountPath(account), account.getCookie(), account.getFolder()));
            log.info("insert Quark account {} : {}, result: {}", id, getMountPath(account), count);
            Utils.executeUpdate("INSERT INTO x_setting_items VALUES('quark_cookie','" + account.getCookie() + "','','text','',1,0);");
        } else if (account.getType() == DriverType.UC) {
            String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'UC',30,'work','{\"cookie\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"ASC\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',0,'native_proxy','');";
            int count = Utils.executeUpdate(String.format(sql, id, getMountPath(account), account.getCookie(), account.getFolder()));
            log.info("insert UC account {} : {}, result: {}", id, getMountPath(account), count);
            Utils.executeUpdate("INSERT INTO x_setting_items VALUES('uc_cookie','" + account.getCookie() + "','','text','',1,0);");
        } else if (account.getType() == DriverType.THUNDER) {
            String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'ThunderBrowser',30,'work','{\"username\":\"%s\",\"password\":\"%s\",\"safe_password\":\"%s\",\"root_folder_id\":\"%s\",\"remove_way\":\"delete\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',0,'302_redirect','');";
            int count = Utils.executeUpdate(String.format(sql, id, getMountPath(account), account.getUsername(), account.getPassword(), account.getSafePassword(), account.getFolder()));
            log.info("insert ThunderBrowser account {} : {}, result: {}", id, getMountPath(account), count);
        } else if (account.getType() == DriverType.CLOUD189) {
            String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'189CloudPC',30,'work','{\"username\":\"%s\",\"password\":\"%s\",\"validate_code\":\"%s\",\"root_folder_id\":\"%s\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',0,'302_redirect','');";
            int count = Utils.executeUpdate(String.format(sql, id, getMountPath(account), account.getUsername(), account.getPassword(), account.getToken(), account.getFolder()));
            log.info("insert 189CloudPC account {} : {}, result: {}", id, getMountPath(account), count);
        } else if (account.getType() == DriverType.PAN115) {
            String sql;
            if (account.isUseProxy()) {
                sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'115 Cloud',30,'work','{\"cookie\":\"%s\",\"qrcode_token\":\"%s\",\"root_folder_id\":\"%s\",\"page_size\":56}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',1,'native_proxy','');";
            } else {
                sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'115 Cloud',30,'work','{\"cookie\":\"%s\",\"qrcode_token\":\"%s\",\"root_folder_id\":\"%s\",\"page_size\":56}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',0,'302_redirect','');";
            }
            int count = Utils.executeUpdate(String.format(sql, id, getMountPath(account), account.getCookie(), account.getToken(), account.getFolder()));
            log.info("insert 115 account {}: {}, result: {}", id, getMountPath(account), count);
            Utils.executeUpdate("INSERT INTO x_setting_items VALUES('115_cookie','" + account.getCookie() + "','','text','',1,0);");
        }
    }

    private void updateAList(DriverAccount account) {
        int id = account.getId() + IDX;
        if (account.getType() == DriverType.QUARK) {
            String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'Quark',30,'work','{\"cookie\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"ASC\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'native_proxy','',0);";
            int count = Utils.executeUpdate(String.format(sql, id, getMountPath(account), account.getCookie(), account.getFolder()));
            log.info("insert Quark account {} : {}, result: {}", id, getMountPath(account), count);
            Utils.executeUpdate("INSERT INTO x_setting_items VALUES('quark_cookie','" + account.getCookie() + "','','text','',1,0);");
        } else if (account.getType() == DriverType.UC) {
            String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'UC',30,'work','{\"cookie\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"ASC\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'native_proxy','',0);";
            int count = Utils.executeUpdate(String.format(sql, id, getMountPath(account), account.getCookie(), account.getFolder()));
            log.info("insert UC account {} : {}, result: {}", id, getMountPath(account), count);
            Utils.executeUpdate("INSERT INTO x_setting_items VALUES('uc_cookie','" + account.getCookie() + "','','text','',1,0);");
        } else if (account.getType() == DriverType.THUNDER) {
            String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'ThunderBrowser',30,'work','{\"username\":\"%s\",\"password\":\"%s\",\"safe_password\":\"%s\",\"root_folder_id\":\"%s\",\"remove_way\":\"delete\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','',0);";
            int count = Utils.executeUpdate(String.format(sql, id, getMountPath(account), account.getUsername(), account.getPassword(), account.getSafePassword(), account.getFolder()));
            log.info("insert ThunderBrowser account {} : {}, result: {}", id, getMountPath(account), count);
        } else if (account.getType() == DriverType.CLOUD189) {
            String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'189CloudPC',30,'work','{\"username\":\"%s\",\"password\":\"%s\",\"validate_code\":\"%s\",\"root_folder_id\":\"%s\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','',0);";
            int count = Utils.executeUpdate(String.format(sql, id, getMountPath(account), account.getUsername(), account.getPassword(), account.getToken(), account.getFolder()));
            log.info("insert 189CloudPC account {} : {}, result: {}", id, getMountPath(account), count);
        } else if (account.getType() == DriverType.PAN115) {
            String sql;
            if (account.isUseProxy()) {
                sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'115 Cloud',30,'work','{\"cookie\":\"%s\",\"qrcode_token\":\"%s\",\"root_folder_id\":\"%s\",\"page_size\":56}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',1,'native_proxy','',0);";
            } else {
                sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'115 Cloud',30,'work','{\"cookie\":\"%s\",\"qrcode_token\":\"%s\",\"root_folder_id\":\"%s\",\"page_size\":56}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','',0);";
            }
            int count = Utils.executeUpdate(String.format(sql, id, getMountPath(account), account.getCookie(), account.getToken(), account.getFolder()));
            log.info("insert 115 account {}: {}, result: {}", id, getMountPath(account), count);
            Utils.executeUpdate("INSERT INTO x_setting_items VALUES('115_cookie','" + account.getCookie() + "','','text','',1,0);");
        }
    }

    private Object getMountPath(DriverAccount account) {
        if (account.getName().startsWith("/")) {
            return account.getName();
        }
        if (account.getType() == DriverType.QUARK) {
            return "/\uD83C\uDF1E我的夸克网盘/" + account.getName();
        } else if (account.getType() == DriverType.UC) {
            return "/\uD83C\uDF1E我的UC网盘/" + account.getName();
        } else if (account.getType() == DriverType.PAN115) {
            return "/115网盘/" + account.getName();
        } else if (account.getType() == DriverType.THUNDER) {
            return "/我的迅雷云盘/" + account.getName();
        } else if (account.getType() == DriverType.CLOUD189) {
            return "/我的天翼云盘/" + account.getName();
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
            if (account.isMaster()) {
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
        if (dto.getType() == DriverType.THUNDER || dto.getType() == DriverType.CLOUD189) {
            if (StringUtils.isBlank(dto.getUsername())) {
                throw new BadRequestException("用户名不能为空");
            }
            if (StringUtils.isBlank(dto.getPassword())) {
                throw new BadRequestException("密码不能为空");
            }
        } else if (StringUtils.isBlank(dto.getCookie()) && StringUtils.isBlank(dto.getToken())) {
            throw new BadRequestException("Cookie和Token不能同时为空");
        }
        if (StringUtils.isBlank(dto.getFolder())) {
            if (dto.getType() == DriverType.QUARK || dto.getType() == DriverType.PAN115) {
                dto.setFolder("0");
            } else if (dto.getType() == DriverType.CLOUD189) {
                dto.setFolder("-11");
            }
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
            String key = switch (account.getType()) {
                case QUARK -> "quark_cookie";
                case PAN115 -> "115_cookie";
                case UC -> "uc_cookie";
                default -> "";
            };
            if (key.isEmpty()) {
                return;
            }
            aListLocalService.updateSetting(key, account.getCookie(), "string");
            driverAccountRepository.saveAll(list);
        }
    }

    private void updateStorage(DriverAccount account) {
        int status = aListLocalService.getAListStatus();
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

    @Scheduled(initialDelay = 1800_000, fixedDelay = 1800_000)
    public void syncCookies() {
        if (aListLocalService.getAListStatus() != 2) {
            return;
        }
        var cookie = aListLocalService.getSetting("quark_cookie");
        log.debug("quark_cookie={}", cookie);
        saveCookie(DriverType.QUARK, cookie);
        cookie = aListLocalService.getSetting("uc_cookie");
        log.debug("uc_cookie={}", cookie);
        saveCookie(DriverType.UC, cookie);
        cookie = aListLocalService.getSetting("115_cookie");
        log.debug("115_cookie={}", cookie);
        saveCookie(DriverType.PAN115, cookie);
    }

    private void saveCookie(DriverType type, SettingResponse response) {
        if (response.getCode() == 200) {
            driverAccountRepository.findByTypeAndMasterTrue(type).ifPresent(account -> {
                account.setCookie(response.getData().getValue());
                driverAccountRepository.save(account);
            });
        }
    }
}
