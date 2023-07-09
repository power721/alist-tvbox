package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.service.LogsService;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@Profile("xiaoya")
public class LogController {
    private final LogsService logsService;

    public LogController(LogsService logsService) {
        this.logsService = logsService;
    }

    @GetMapping("/logs")
    public Page<String> logs(Pageable pageable) throws IOException {
        return logsService.getLogs(pageable);
    }

    @GetMapping("/logs/download")
    public FileSystemResource downloadLog() throws IOException {
        return logsService.downloadLog();
    }
}
