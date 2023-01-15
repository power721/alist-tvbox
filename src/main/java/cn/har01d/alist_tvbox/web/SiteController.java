package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.SiteDto;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.service.IndexService;
import cn.har01d.alist_tvbox.service.SiteService;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/sites")
public class SiteController {
    private final SiteService siteService;
    private final IndexService indexService;

    public SiteController(SiteService siteService, IndexService indexService) {
        this.siteService = siteService;
        this.indexService = indexService;
    }

    @GetMapping
    public List<Site> list() {
        return siteService.findAll();
    }

    @PostMapping
    public Site create(@RequestBody SiteDto dto) {
        return siteService.create(dto);
    }

    @GetMapping("/{id}")
    public Site get(@PathVariable int id) {
        return siteService.getById(id);
    }

    @PostMapping("/{id}")
    public Site update(@PathVariable int id, @RequestBody SiteDto dto) {
        return siteService.update(id, dto);
    }

    @PostMapping("/{id}/updateIndexFile")
    public void updateIndexFile(@PathVariable int id) throws IOException {
        indexService.updateIndexFile(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable int id) {
        siteService.delete(id);
    }
}
