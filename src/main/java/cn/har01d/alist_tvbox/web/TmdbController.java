package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.MetaDto;
import cn.har01d.alist_tvbox.entity.TmdbMetaRepository;
import cn.har01d.alist_tvbox.service.TmdbService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/tmdb")
public class TmdbController {
    private final TmdbService service;
    private final TmdbMetaRepository metaRepository;

    public TmdbController(TmdbService service, TmdbMetaRepository metaRepository) {
        this.service = service;
        this.metaRepository = metaRepository;
    }

    @GetMapping("/meta")
    public Page<MetaDto> list(Pageable pageable, String q) {
        if (StringUtils.isNotBlank(q)) {
            return metaRepository.findByPathContains(q, pageable).map(MetaDto::new);
        }
        return metaRepository.findAll(pageable).map(MetaDto::new);
    }

    @PostMapping("/meta")
    public boolean addMeta(@RequestBody MetaDto dto) {
        return service.addMeta(dto);
    }

    @PostMapping("/meta/{id}")
    public boolean updateMetaMovie(@PathVariable Integer id, @RequestBody MetaDto dto) {
        return service.updateMetaMovie(id, dto);
    }

    @PostMapping("/meta-scrape")
    public void scrape(Integer siteId, String indexName, boolean force) throws IOException {
        service.scrape(siteId, indexName, force);
    }

    @PostMapping("/meta-sync")
    public void sync() {
        service.syncMeta();
    }

    @PostMapping("/meta/{id}/scrape")
    public boolean scrape(@PathVariable Integer id, String type, String name) {
        return service.scrape(id, type, name);
    }

    @DeleteMapping("/meta/{id}")
    public void delete(@PathVariable Integer id) {
        metaRepository.deleteById(id);
    }

    @PostMapping("/meta-batch-delete")
    public void batchDelete(@RequestBody List<Integer> ids) {
        metaRepository.findAllById(ids).forEach(meta -> {
            log.warn("delete {} {}", meta.getId(), meta.getPath());
            metaRepository.delete(meta);
        });
    }
}
