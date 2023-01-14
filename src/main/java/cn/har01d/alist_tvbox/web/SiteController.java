package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.SiteDto;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.service.SiteService;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/sites")
public class SiteController {
    private final SiteService siteService;

    public SiteController(SiteService siteService) {
        this.siteService = siteService;
    }

    @GetMapping
    public List<Site> list() {
        return siteService.list();
    }

    @PostMapping
    public Site create(@RequestBody @Valid SiteDto dto) {
        return siteService.create(dto);
    }

    @GetMapping("/{id}")
    public Site get(@PathVariable int id) {
        return siteService.getById(id);
    }

    @PostMapping("/{id}")
    public Site update(@PathVariable int id, @RequestBody @Valid SiteDto dto) {
        return siteService.update(id, dto);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable int id) {
        siteService.delete(id);
    }
}
