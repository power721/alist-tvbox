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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
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
        boolean sp = appProperties.isXiaoya();
        if (siteRepository.count() > 0) {
            if (sp) {
                siteRepository.findById(1).ifPresent(this::updateUserToken);
            }
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

        if (sp) {
            readAList(order);
        }
    }

    private void readAList(int order) {
        Path path = Paths.get("/data/alist_list.txt");
        if (Files.exists(path)) {
            try {
                log.info("loading site list from file");
                List<String> lines = Files.readAllLines(path);
                for (String line : lines) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length == 4) {
                        readAList(order++, parts);
                    }
                }
            } catch (Exception e) {
                log.warn("", e);
            }
        }
    }

    private void readAList(int order, String[] parts) {
        try {
            Site site = new Site();
            site.setName(parts[0]);
            site.setVersion(Integer.parseInt(parts[1].replace("v", "")));
            site.setUrl(parts[2]);
            site.setFolder(parts[3]);
            site.setOrder(order);
            siteRepository.save(site);
            log.info("save site to database: {}", site);
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    private void updateUserToken(Site site) {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:/opt/alist/data/data.db")) {
            Statement statement = connection.createStatement();
            String sql = "select value from x_setting_items where key = 'token'";
            ResultSet rs = statement.executeQuery(sql);
            String token = rs.getString(1);
            if (!token.equals(site.getToken())) {
                log.info("update user token: {}", token);
                site.setToken(token);
                siteRepository.save(site);
            }
        } catch (Exception e) {
            log.warn("", e);
        }
        // ignore
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
        site.setFolder(dto.getFolder());
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
        site.setFolder(dto.getFolder());
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
        if (id == 1 && appProperties.isXiaoya()) {
            throw new BadRequestException("不能删除默认站点");
        }
        siteRepository.deleteById(id);
    }

    public void save(Site site) {
        siteRepository.save(site);
    }
}
