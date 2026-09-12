package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import cn.har01d.alist_tvbox.storage.OpenList;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class Index115SiteSeed implements ApplicationRunner {
    private static final String NAME = "115分享";
    private static final String TOKEN_SETTING = "alist_token";
    private static final int STORAGE_ID_BASE = 8000;

    private final SiteRepository siteRepository;
    private final SettingRepository settingRepository;
    private final AListLocalService aListLocalService;
    private final DriverAccountRepository driverAccountRepository;
    private final AccountService accountService;

    public Index115SiteSeed(SiteRepository siteRepository,
                            SettingRepository settingRepository,
                            AListLocalService aListLocalService,
                            DriverAccountRepository driverAccountRepository,
                            AccountService accountService) {
        this.siteRepository = siteRepository;
        this.settingRepository = settingRepository;
        this.aListLocalService = aListLocalService;
        this.driverAccountRepository = driverAccountRepository;
        this.accountService = accountService;
    }

    @Override
    public void run(ApplicationArguments args) {
        Site site = siteRepository.findAll().stream()
                .filter(s -> NAME.equals(s.getName()))
                .findFirst()
                .orElse(null);
        if (site == null) {
            String token = settingRepository.findById(TOKEN_SETTING).map(Setting::getValue).orElse("");
            site = new Site();
            site.setName(NAME);
            site.setUrl("http://localhost");
            site.setSearchable(true);
            site.setStorageVersion(1);
            site.setSortOrder(2);
            if (StringUtils.isNotBlank(token)) {
                site.setToken(token);
            }
            site = siteRepository.save(site);
            log.info("seeded index115 site (version 1, http://localhost)");
        }
        registerStorage(site);
    }

    private boolean has115Account() {
        return driverAccountRepository.findByTypeAndMasterTrue(DriverType.PAN115).isPresent();
    }

    private void registerStorage(Site site) {
        try {
            OpenList storage = new OpenList(site);
            storage.setDriver("OpenList");
            // 无 115 账号时索引挂载整体不可用,禁用后 AList 启动不加载=文件列表「我的套娃」里不可见
            storage.setDisabled(!has115Account());
            aListLocalService.saveStorage(storage);
            log.info("register index115 site success (disabled: {})", storage.isDisabled());
        } catch (Exception e) {
            log.warn("register index115 site failed: {}", e.getMessage());
        }
    }

    /**
     * 115 账号增删改后同步「我的套娃/115分享」挂载的可见性:
     * 运行中的 AList 只认自己的 admin API,改 DB 行不会热加载,须删了重挂再 enable/disable。
     */
    public void refreshStorage() {
        Site site = siteRepository.findAll().stream()
                .filter(s -> NAME.equals(s.getName()))
                .findFirst()
                .orElse(null);
        if (site == null) {
            return;
        }
        boolean hasAccount = has115Account();
        int storageId = STORAGE_ID_BASE + site.getId();
        int status = aListLocalService.checkStatus();
        if (status == 1) {
            log.info("skip refresh index115 storage: AList server starting");
            return;
        }
        try {
            if (status >= 2) {
                String token = accountService.login();
                try {
                    accountService.deleteStorage(storageId, token);
                } catch (Exception e) {
                    // AList 内存里尚未加载该挂载(启动竞态)时 delete 报错,继续落库重挂即可
                    log.debug("delete index115 storage before refresh: {}", e.getMessage());
                }
                OpenList storage = new OpenList(site);
                storage.setDriver("OpenList");
                storage.setDisabled(true);
                aListLocalService.saveStorage(storage);
                if (hasAccount) {
                    accountService.enableStorage(storageId, token);
                }
            } else {
                aListLocalService.executeUpdate("DELETE FROM x_storages WHERE id = " + storageId);
                registerStorage(site);
            }
            log.info("refresh index115 storage: hasAccount: {}", hasAccount);
        } catch (Exception e) {
            log.warn("refresh index115 storage failed: {}", e.getMessage());
        }
    }
}
