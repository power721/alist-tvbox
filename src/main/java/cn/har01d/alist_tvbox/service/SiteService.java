package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.Optional;

@Slf4j
@Service
public class SiteService {
    private final AppProperties appProperties;
    private final SiteRepository siteRepository;

    public SiteService(AppProperties appProperties, SiteRepository siteRepository) {
        this.appProperties = appProperties;
        this.siteRepository = siteRepository;
    }

    @PostConstruct
    public void init() {
        if (siteRepository.count() > 0) {
            return;
        }

        for (cn.har01d.alist_tvbox.tvbox.Site s : appProperties.getSites()) {
            Site site = new Site();
            site.setName(s.getName());
            site.setUrl(s.getUrl());
            site.setSearchable(s.isSearchable());
            site.setSearchApi(s.getSearchApi());
            site.setIndexFile(s.getIndexFile());
            siteRepository.save(site);
            log.info("save site to database: {}", site);
        }
    }

    public Site getById(Integer id) {
        return siteRepository.findById(id).orElseThrow(() -> new NotFoundException("站点不存在"));
    }

    public Optional<Site> getByName(String name) {
        return siteRepository.findByName(name);
    }
}
