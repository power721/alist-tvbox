package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.IndexTemplateDto;
import cn.har01d.alist_tvbox.entity.IndexTemplate;
import cn.har01d.alist_tvbox.service.IndexTemplateService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/index-templates")

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
