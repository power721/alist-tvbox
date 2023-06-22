package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/settings")
public class SettingController {
    private final SettingRepository settingRepository;

    public SettingController(SettingRepository settingRepository) {
        this.settingRepository = settingRepository;
    }

    @GetMapping
    public Map<String, String> findAll() {
        Map<String, String> map = settingRepository.findAll().stream().collect(Collectors.toMap(Setting::getName, Setting::getValue));
        map.remove("atv_password");
        return map;
    }

    @GetMapping("/{name}")
    public Setting get(@PathVariable String name) {
        return settingRepository.findById(name).orElse(null);
    }

    @PostMapping
    public Setting update(@RequestBody Setting setting) {
        return settingRepository.save(setting);
    }
}
