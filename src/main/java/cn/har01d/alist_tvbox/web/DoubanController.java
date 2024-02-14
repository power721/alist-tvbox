package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.MetaDto;
import cn.har01d.alist_tvbox.dto.Versions;
import cn.har01d.alist_tvbox.entity.MetaRepository;
import cn.har01d.alist_tvbox.service.DoubanService;
import cn.har01d.alist_tvbox.service.IndexService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@Slf4j
@RestController
public class DoubanController {
    private final DoubanService service;
    private final IndexService indexService;
    private final MetaRepository metaRepository;

    public DoubanController(DoubanService service, IndexService indexService, MetaRepository metaRepository) {
        this.service = service;
        this.indexService = indexService;
        this.metaRepository = metaRepository;
    }

    @GetMapping("/api/meta")
    public Page<MetaDto> list(Pageable pageable, String q) {
        if (StringUtils.isNotBlank(q)) {
            return metaRepository.findByPathContains(q, pageable).map(MetaDto::new);
        }
        return metaRepository.findAll(pageable).map(MetaDto::new);
    }

    @PostMapping("/api/meta")
    public boolean addMeta(@RequestBody MetaDto dto) {
        return service.addMeta(dto);
    }

    @PostMapping("/api/meta/{id}")
    public boolean updateMetaMovie(@PathVariable Integer id, @RequestBody MetaDto dto) {
        return service.updateMetaMovie(id, dto);
    }

    @PostMapping("/api/fix-meta")
    public int fixUnique() {
        return service.fixUnique();
    }

    @PostMapping("/api/meta-scrape")
    public void scrape(Integer siteId, boolean force) throws IOException {
        service.scrape(siteId, force);
    }

    @PostMapping("/api/meta/{id}/scrape")
    public boolean scrape(@PathVariable Integer id, String name) {
        return service.scrape(id, name);
    }

    @DeleteMapping("/api/meta/{id}")
    public void delete(@PathVariable Integer id) {
        metaRepository.deleteById(id);
    }

    @PostMapping("/api/meta-batch-delete")
    public void batchDelete(@RequestBody List<Integer> ids) {
        metaRepository.findAllById(ids).forEach(meta -> {
            log.warn("delete {} {}", meta.getId(), meta.getPath());
            metaRepository.delete(meta);
        });
    }

    @GetMapping("/api/versions")
    public Versions getRemoteVersion() {
        Versions versions = new Versions();
        service.getRemoteVersion(versions);
        versions.setIndex(indexService.getRemoteVersion().trim());
        String appVersion = service.getAppRemoteVersion().trim();
        String[] parts = appVersion.split("\n");
        if (parts.length > 1) {
            appVersion = parts[0];
            versions.setChangelog(parts[1]);
        }
        versions.setApp(appVersion);
        return versions;
    }
}
