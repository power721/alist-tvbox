package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class Index115SiteSeed implements ApplicationRunner {
    private static final String NAME = "index115";
    private static final String TOKEN_SETTING = "alist_token";

    private final SiteRepository siteRepository;
    private final SettingRepository settingRepository;

    public Index115SiteSeed(SiteRepository siteRepository, SettingRepository settingRepository) {
        this.siteRepository = siteRepository;
        this.settingRepository = settingRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (siteRepository.findAll().stream().anyMatch(s -> NAME.equals(s.getName()))) {
            return;
        }
        String token = settingRepository.findById(TOKEN_SETTING).map(Setting::getValue).orElse("");
        Site site = new Site();
        site.setName(NAME);
        site.setUrl("http://localhost");
        site.setSearchable(true);
        site.setStorageVersion(1);
        if (StringUtils.isNotBlank(token)) {
            site.setToken(token);
        }
        siteRepository.save(site);
        log.info("seeded index115 site (version 1, http://localhost)");
    }
}
