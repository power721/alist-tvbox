package cn.har01d.alist_tvbox.web;


import cn.har01d.alist_tvbox.dto.AListAliasDto;
import cn.har01d.alist_tvbox.entity.AListAlias;
import cn.har01d.alist_tvbox.entity.AListAliasRepository;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import cn.har01d.alist_tvbox.service.AListAliasService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Profile("xiaoya")
@RestController
@RequestMapping("/alist/alias")
public class AListAliasController {
    private final AListAliasRepository repository;
    private final AListAliasService service;

    public AListAliasController(AListAliasRepository repository, AListAliasService service) {
        this.repository = repository;
        this.service = service;
    }

    @GetMapping
    public List<AListAlias> list() {
        return repository.findAll();
    }

    @PostMapping
    public AListAlias create(@RequestBody AListAliasDto dto) {
        return service.create(dto);
    }

    @GetMapping("/{id}")
    public AListAliasDto get(@PathVariable Integer id) {
        return new AListAliasDto(repository.findById(id).orElseThrow(NotFoundException::new));
    }

    @PostMapping("/{id}")
    public AListAlias update(@PathVariable Integer id, @RequestBody AListAliasDto dto) {
        return service.update(id, dto);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Integer id) {
        service.delete(id);
    }

}
