package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.SiteDto;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.List;
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

    public List<Site> list() {
        Sort sort = Sort.by("order");
        return siteRepository.findAll(sort);
    }

    public Site create(SiteDto dto) {
        if (siteRepository.existsByName(dto.getName())) {
            throw new BadRequestException("站点名字重复");
        }
        if (siteRepository.existsByUrl(dto.getUrl())) {
            throw new BadRequestException("站点链接重复");
        }

        Site site = new Site();
        site.setName(dto.getName());
        site.setUrl(dto.getUrl());
        site.setOrder(dto.getOrder());
        site.setSearchable(dto.isSearchable());
        site.setIndexFile(dto.getIndexFile());
        return siteRepository.save(site);
    }

    public Site update(int id, SiteDto dto) {
        Site site = siteRepository.findById(id).orElseThrow(() -> new NotFoundException("站点不存在"));
        Optional<Site> other = siteRepository.findByName(dto.getName());
        if (other.isPresent() && other.get().getId() != id) {
            throw new BadRequestException("站点名字重复");
        }
        other = siteRepository.findByUrl(dto.getUrl());
        if (other.isPresent() && other.get().getId() != id) {
            throw new BadRequestException("站点链接重复");
        }

        site.setName(dto.getName());
        site.setUrl(dto.getUrl());
        site.setOrder(dto.getOrder());
        site.setSearchable(dto.isSearchable());
        site.setIndexFile(dto.getIndexFile());
        return siteRepository.save(site);
    }

    public void delete(int id) {
        siteRepository.deleteById(id);
    }
}
