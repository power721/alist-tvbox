package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.service.LogsService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController

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
    public FileSystemResource downloadLog(HttpServletResponse response) throws IOException {
        response.addHeader("Content-Disposition", "attachment; filename=\"log.zip\"");
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        return logsService.downloadLog();
    }
}
