package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.Versions;
import cn.har01d.alist_tvbox.entity.Meta;
import cn.har01d.alist_tvbox.entity.MetaRepository;
import cn.har01d.alist_tvbox.service.DoubanService;
import cn.har01d.alist_tvbox.service.IndexService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping("/meta")
    public Page<Meta> list(Pageable pageable, String q) {
        if (StringUtils.isNotBlank(q)) {
            return metaRepository.findByPathContains(q, pageable);
        }
        return metaRepository.findAll(pageable);
    }

    @DeleteMapping("/meta/{id}")
    public void delete(@PathVariable Integer id) {
        metaRepository.deleteById(id);
    }

    @GetMapping("/versions")
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
