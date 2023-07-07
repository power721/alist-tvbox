package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.IndexTemplateDto;
import cn.har01d.alist_tvbox.entity.IndexTemplate;
import cn.har01d.alist_tvbox.service.IndexTemplateService;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/index-templates")
@Profile("xiaoya")
public class IndexTemplateController {
    private final IndexTemplateService indexTemplateService;

    public IndexTemplateController(IndexTemplateService indexTemplateService) {
        this.indexTemplateService = indexTemplateService;
    }

    @GetMapping
    public Page<IndexTemplate> list(Pageable pageable) {
        return indexTemplateService.list(pageable);
    }

    @PostMapping
    public IndexTemplate create(@RequestBody IndexTemplateDto dto) {
        return indexTemplateService.create(dto);
    }

    @GetMapping("/{id}")
    public IndexTemplate getById(@PathVariable Integer id) {
        return indexTemplateService.getById(id);
    }

    @PostMapping("/{id}")
    public IndexTemplate update(@PathVariable Integer id, @RequestBody IndexTemplateDto dto) {
        return indexTemplateService.update(id, dto);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Integer id) {
        indexTemplateService.delete(id);
    }
}
