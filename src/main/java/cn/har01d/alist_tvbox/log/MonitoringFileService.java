package cn.har01d.alist_tvbox.log;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Slf4j
@Service
@Profile("xiaoya")
public class MonitoringFileService {
    private final AtomicBoolean listen = new AtomicBoolean(false);

    private final Path monitoringDirectory;
    private final Path file;
    private final List<Consumer<Path>> callbacks = new CopyOnWriteArrayList<>();
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private long lastModifiedTime;

    public MonitoringFileService(@Value("${logging.file.name:log/app.log}") String fileName) {
        this.monitoringDirectory = new FileSystemResource("/opt/atv/").getFile().toPath();
        this.file = monitoringDirectory.resolve(fileName);

        log.info("monitoring log file: {}", file);

        executorService.submit(this::monitor);
    }

    public void listen(Consumer<Path> consumer) {
        callbacks.add(consumer);
    }

    public long lastModifiedTime() throws IOException {
        BasicFileAttributes attr = Files.readAttributes(file, BasicFileAttributes.class);
        return attr.lastModifiedTime().toMillis();
    }

    void monitor() {
        listen.set(true);
        while (listen.get()) {
            try {
                Thread.sleep(1000);
                long time = lastModifiedTime();
                if (lastModifiedTime < time) {
                    if (callbacks.isEmpty()) {
                        continue;
                    }

                    lastModifiedTime = time;
                    callbacks.forEach(c -> c.accept(file));
                }
            } catch (Exception ex) {
                log.warn("", ex);
                listen.set(false);
            }
        }
    }

    public Path getFile() {
        return file;
    }
}
