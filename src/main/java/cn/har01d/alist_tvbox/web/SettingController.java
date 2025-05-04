package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.service.SettingService;
import cn.har01d.alist_tvbox.util.Utils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
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
    private final SettingService service;

    public SettingController(SettingService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, String> findAll() {
        return service.findAll();
    }

    @GetMapping("/{name}")
    public Setting get(@PathVariable String name) {
        return service.get(name);
    }

    @PostMapping
    public Setting update(@RequestBody Setting setting, HttpServletRequest request) {
        if ("user_agent".equals(setting.getName())) {
            if (StringUtils.isBlank(setting.getValue())) {
                setting.setValue(Utils.getUserAgent());
            } else if ("current".equals(setting.getValue())) {
                setting.setValue(request.getHeader(HttpHeaders.USER_AGENT));
            }
        }
        return service.update(setting);
    }

    @GetMapping("/export")
    public FileSystemResource exportDatabase(HttpServletResponse response) throws IOException {
        response.addHeader("Content-Disposition", "attachment; filename=\"database-" + LocalDate.now() + ".zip\"");
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        return service.exportDatabase();
    }

}
