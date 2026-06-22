package cn.har01d.alist_tvbox.service;

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

    private final SiteRepository siteRepository;
    private final SettingRepository settingRepository;
    private final AListLocalService aListLocalService;
    private final ShareService shareService;
    private final AccountService accountService;

    public Index115SiteSeed(SiteRepository siteRepository,
                            SettingRepository settingRepository,
                            AListLocalService aListLocalService,
                            ShareService shareService,
                            AccountService accountService) {
        this.siteRepository = siteRepository;
        this.settingRepository = settingRepository;
        this.aListLocalService = aListLocalService;
        this.shareService = shareService;
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

    // Register the site as an OpenList nested storage so the bundled alist
    // recognizes it (mounts at /我的套娃/index115). storageVersion=1 would otherwise
    // produce driver "AList V1", which OpenList rejects — force "OpenList".
    private void registerStorage(Site site) {
        try {
            OpenList storage = new OpenList(site);
            storage.setDriver("OpenList");
            storage.setDisabled(true);
            aListLocalService.saveStorage(storage);
            String token = accountService.login();
            String error = shareService.enableStorage(storage.getId(), token);
            if (error != null) {
                log.warn("enable index115 storage returned: {}", error);
            }
        } catch (Exception e) {
            log.warn("register index115 storage failed: {}", e.getMessage());
        }
    }
}
