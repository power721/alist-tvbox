package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.FileItem;
import cn.har01d.alist_tvbox.dto.SiteDto;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.service.AListService;
import cn.har01d.alist_tvbox.service.IndexService;
import cn.har01d.alist_tvbox.service.SiteService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/sites")
public class SiteController {
    private final SiteService siteService;
    private final IndexService indexService;
    private final AListService aListService;

    public SiteController(SiteService siteService, IndexService indexService, AListService aListService) {
        this.siteService = siteService;
        this.indexService = indexService;
        this.aListService = aListService;
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

    @GetMapping("/{id}/browse")
    public List<FileItem> browse(@PathVariable int id, @RequestParam(required = false, defaultValue = "") String path) {
        return aListService.browse(id, path);
    }

    @GetMapping("/{id}/index")
    public List<FileItem> listIndexFiles(@PathVariable int id) {
        return indexService.listIndexFiles(id);
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
