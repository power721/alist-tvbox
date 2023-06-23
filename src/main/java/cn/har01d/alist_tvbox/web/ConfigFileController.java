package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.FileDto;
import cn.har01d.alist_tvbox.entity.ConfigFile;
import cn.har01d.alist_tvbox.entity.ConfigFileRepository;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import cn.har01d.alist_tvbox.service.ConfigFileService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@Profile("xiaoya")
@RestController
@RequestMapping("/files")
public class ConfigFileController {
    private final ConfigFileService service;
    private final ConfigFileRepository repository;

    public ConfigFileController(ConfigFileService service, ConfigFileRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    @GetMapping
    public List<ConfigFile> list() {
        return repository.findAll();
    }

    @PostMapping
    public ConfigFile create(@RequestBody FileDto dto) throws IOException {
        return service.create(dto);
    }

    @GetMapping("/{id}")
    public FileDto get(@PathVariable Integer id) {
        return new FileDto(repository.findById(id).orElseThrow(NotFoundException::new));
    }

    @PostMapping("/{id}")
    public ConfigFile update(@PathVariable Integer id, @RequestBody FileDto dto) throws IOException {
        return service.update(id, dto);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Integer id) throws IOException {
        service.delete(id);
    }
}
