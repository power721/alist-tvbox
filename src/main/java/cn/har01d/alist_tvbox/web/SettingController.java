package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.service.AListLocalService;
import cn.har01d.alist_tvbox.service.SettingService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingController {
    private final SettingRepository settingRepository;
    private final SettingService service;
    private final AListLocalService aListLocalService;

    public SettingController(SettingRepository settingRepository, SettingService service, AListLocalService aListLocalService) {
        this.settingRepository = settingRepository;
        this.service = service;
        this.aListLocalService = aListLocalService;
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
        setting = service.update(setting);
        if ("delete_delay_time".equals(setting.getName())) {
            aListLocalService.updateSetting("delete_delay_time", setting.getValue(), "number");
        }
        return setting;
    }

    @GetMapping("/export")
    public FileSystemResource exportDatabase(HttpServletResponse response) throws IOException {
        response.addHeader("Content-Disposition", "attachment; filename=\"database-" + LocalDate.now() + ".zip\"");
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        return service.exportDatabase();
    }

}
