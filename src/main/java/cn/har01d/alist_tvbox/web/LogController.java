package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.log.LogsSseService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@Controller
@Profile("xiaoya")
public class LogController {
    private final LogsSseService logsSseService;

    public LogController(LogsSseService logsSseService) {
        this.logsSseService = logsSseService;
    }

    @GetMapping(value = "/logs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter logs() throws IOException {
        return logsSseService.newSseEmitter();
    }
}
