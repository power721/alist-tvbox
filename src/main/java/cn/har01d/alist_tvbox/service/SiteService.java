package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.SiteDto;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.net.URL;
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

        int order = 1;
        for (cn.har01d.alist_tvbox.tvbox.Site s : appProperties.getSites()) {
            Site site = new Site();
            site.setName(s.getName());
            site.setUrl(s.getUrl());
            site.setPassword(s.getPassword());
            site.setSearchable(s.isSearchable());
            site.setXiaoya(s.isXiaoya());
            site.setIndexFile(s.getIndexFile());
            site.setVersion(s.getVersion());
            site.setOrder(order++);
            siteRepository.save(site);
            log.info("save site to database: {}", site);
        }
    }

    public Site getById(Integer id) {
        return siteRepository.findById(id).orElseThrow(() -> new NotFoundException("站点不存在"));
    }

    public Site getByName(String name) {
        return siteRepository.findByName(name).orElseThrow(() -> new NotFoundException("站点不存在"));
    }

    public List<Site> findAll() {
        Sort sort = Sort.by("order");
        return siteRepository.findAll(sort);
    }

    public List<Site> list() {
        Sort sort = Sort.by("order");
        return siteRepository.findAllByDisabledFalse(sort);
    }

    public Site create(SiteDto dto) {
        validate(dto);
        if (siteRepository.existsByName(dto.getName())) {
            throw new BadRequestException("站点名字重复");
        }
        if (siteRepository.existsByUrl(dto.getUrl())) {
            throw new BadRequestException("站点地址重复");
        }

        Site site = new Site();
        site.setName(dto.getName());
        site.setUrl(dto.getUrl());
        site.setPassword(dto.getPassword());
        site.setToken(dto.getToken());
        site.setOrder(dto.getOrder());
        site.setSearchable(dto.isSearchable());
        site.setXiaoya(dto.isXiaoya());
        site.setIndexFile(dto.getIndexFile());
        site.setDisabled(dto.isDisabled());
        site.setVersion(dto.getVersion());
        return siteRepository.save(site);
    }

    public Site update(int id, SiteDto dto) {
        validate(dto);
        Site site = siteRepository.findById(id).orElseThrow(() -> new NotFoundException("站点不存在"));
        Optional<Site> other = siteRepository.findByName(dto.getName());
        if (other.isPresent() && other.get().getId() != id) {
            throw new BadRequestException("站点名字重复");
        }
        other = siteRepository.findByUrl(dto.getUrl());
        if (other.isPresent() && other.get().getId() != id) {
            throw new BadRequestException("站点地址重复");
        }

        site.setName(dto.getName());
        site.setUrl(dto.getUrl());
        site.setPassword(dto.getPassword());
        site.setToken(dto.getToken());
        site.setOrder(dto.getOrder());
        site.setSearchable(dto.isSearchable());
        site.setXiaoya(dto.isXiaoya());
        site.setIndexFile(dto.getIndexFile());
        site.setDisabled(dto.isDisabled());
        site.setVersion(dto.getVersion());
        return siteRepository.save(site);
    }

    private void validate(SiteDto dto) {
        if (StringUtils.isBlank(dto.getName())) {
            throw new BadRequestException("站点名称不能为空");
        }

        if (StringUtils.isBlank(dto.getUrl())) {
            throw new BadRequestException("站点地址不能为空");
        }

        try {
            new URL(dto.getUrl());
        } catch (Exception e) {
            throw new BadRequestException("站点地址不正确", e);
        }

        if (dto.isSearchable() && StringUtils.isNotBlank(dto.getIndexFile())) {
            if (dto.getIndexFile().startsWith("http")) {
                try {
                    new URL(dto.getIndexFile());
                } catch (Exception e) {
                    throw new BadRequestException("索引地址不正确", e);
                }
            } else if (dto.getIndexFile().startsWith("/")) {
                File file = new File(dto.getIndexFile());
                if (!file.exists()) {
                    throw new BadRequestException("索引文件不存在");
                }
            } else {
                throw new BadRequestException("索引文件不正确");
            }
        }
    }

    public void delete(int id) {
        siteRepository.deleteById(id);
    }

    public void save(Site site) {
        siteRepository.save(site);
    }
}
