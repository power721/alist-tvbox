package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class TelegramSearchService {
    private final SettingRepository settingRepository;
    private volatile boolean running;
    private volatile long pid;

    public TelegramSearchService(SettingRepository settingRepository) {
        this.settingRepository = settingRepository;
    }

    @PostConstruct
    public void init() {
        String session = settingRepository.findById("tg_session").map(Setting::getValue).orElse("");
        if (StringUtils.isNotBlank(session)) {
            start();
        }
    }

    public void start() {
        String session = settingRepository.findById("tg_session").map(Setting::getValue).orElseThrow(() -> new IllegalStateException("电报session缺失"));
        start(session);
    }

    public void start(String session) {
        if (running) {
            stop();
        }

        if (StringUtils.isBlank(session)) {
            return;
        }

        try {
            log.info("Start telegram search server.");
            ProcessBuilder builder = new ProcessBuilder();
            File outFile = new File("/opt/atv/log/tgs.log");
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(outFile));
            builder.redirectError(ProcessBuilder.Redirect.appendTo(outFile));
            String arch = System.getProperties().getProperty("os.arch");
            String command = "/tgsearch.arm64v8";
            if (arch.equals("amd64")) {
                command = "/tgsearch.x86_64";
            }
            builder.command(command, "-s", session);
            builder.directory(new File("/data"));
            Process process = builder.start();
            pid = process.pid();
            log.info("Telegram search server started, PID: {}", pid);
            running = true;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void stop() {
        log.info("Stop telegram search server.");
        try {
            ProcessBuilder builder = new ProcessBuilder();
            File outFile = new File("/opt/atv/log/tgs.log");
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(outFile));
            builder.redirectError(ProcessBuilder.Redirect.appendTo(outFile));
            builder.command("kill", String.valueOf(pid));
            builder.directory(new File("/data"));
            Process process = builder.start();
            process.waitFor(1, TimeUnit.SECONDS);
            running = false;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
