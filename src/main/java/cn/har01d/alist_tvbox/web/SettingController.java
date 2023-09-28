package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.service.SettingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingController {
    private final SettingRepository settingRepository;
    private final SettingService service;

    public SettingController(SettingRepository settingRepository, SettingService service) {
        this.settingRepository = settingRepository;
        this.service = service;
    }

    @GetMapping
    public Map<String, String> findAll() {
        return service.findAll();
    }

    @GetMapping("/{name}")
    public Setting get(@PathVariable String name) {
        return settingRepository.findById(name).orElse(null);
    }

    @PostMapping
    public Setting update(@RequestBody Setting setting) {
        return service.update(setting);
    }

    @PostMapping("/export")
    public void exportDatabase() {
        service.exportDatabase();
    }

}
