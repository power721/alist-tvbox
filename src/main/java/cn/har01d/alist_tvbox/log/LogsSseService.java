package cn.har01d.alist_tvbox.log;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

@Slf4j
@Service
@Profile("xiaoya")
public class LogsSseService {

    private static final String TOPIC = "logs";
    private final SseTemplate template;
    private final MonitoringFileService monitoringFileService;
    private static final AtomicLong COUNTER = new AtomicLong(0);

    public LogsSseService(SseTemplate template, MonitoringFileService monitoringFileService) {
        this.template = template;
        this.monitoringFileService = monitoringFileService;
        monitoringFileService.listen(file -> {
            try (Stream<String> stream = Files.lines(file)) {
                stream.skip(COUNTER.get())
                        .forEach(line ->
                                template.broadcast(TOPIC, SseEmitter.event()
                                        .id(String.valueOf(COUNTER.incrementAndGet()))
                                        .data(String.format("%04d: %s", COUNTER.get(), fixLine(line)))));
            } catch (Exception e) {
                log.warn("", e);
            }
        });
    }

    public SseEmitter newSseEmitter() throws IOException {
        SseEmitter emitter = template.newSseEmitter(TOPIC);
        List<String> lines = Files.readAllLines(monitoringFileService.getFile());
        int end = lines.size();
        int start = end - 1000;
        if (start < 0) {
            start = 0;
        }
        COUNTER.set(start);
        emitter.send("");
        for (String line : lines.subList(start, end)) {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(COUNTER.incrementAndGet()))
                    .data(String.format("%04d: %s", COUNTER.get(), fixLine(line))));
        }
        return emitter;
    }

    private String fixLine(String text) {
        return text.replaceAll("\u001B\\[\\d{2}m", "AList: ").replaceAll("\u001B\\[\\d{1}m", " ");
    }
}
