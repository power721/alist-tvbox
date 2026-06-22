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

    public Index115SiteSeed(SiteRepository siteRepository,
                            SettingRepository settingRepository,
                            AListLocalService aListLocalService) {
        this.siteRepository = siteRepository;
        this.settingRepository = settingRepository;
        this.aListLocalService = aListLocalService;
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

    private void registerStorage(Site site) {
        try {
            OpenList storage = new OpenList(site);
            storage.setDriver("OpenList");
            aListLocalService.saveStorage(storage);
            log.info("register index115 site success");
        } catch (Exception e) {
            log.warn("register index115 site failed: {}", e.getMessage());
        }
    }
}
