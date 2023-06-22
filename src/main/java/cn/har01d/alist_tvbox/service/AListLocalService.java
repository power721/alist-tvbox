package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.model.SettingResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@Profile("xiaoya")
public class AListLocalService {
    private final SettingRepository settingRepository;
    private final RestTemplate restTemplate;

    private volatile int aListStatus;

    public AListLocalService(SettingRepository settingRepository, RestTemplateBuilder builder) {
        this.settingRepository = settingRepository;
        this.restTemplate = builder.build();
    }

    public void startAListServer(boolean wait) {
        try {
            log.info("start AList server");
            ProcessBuilder builder = new ProcessBuilder();
            builder.inheritIO();
            builder.command("/opt/alist/alist", "server", "--no-prefix");
            builder.directory(new File("/opt/alist"));
            Process process = builder.start();
            settingRepository.save(new Setting("alist_start_time", Instant.now().toString()));
            if (wait) {
                process.waitFor(30, TimeUnit.SECONDS);
                waitAListStart();
            }
            log.info("AList server started");
            aListStatus = 1;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void waitAListStart() throws InterruptedException {
        int count = 0;
        for (int i = 0; i < 60; ++i) {
            ResponseEntity<SettingResponse> response = restTemplate.getForEntity("http://localhost:5244/api/public/settings", SettingResponse.class);
            if (response.getBody() != null && response.getBody().getCode() == 200) {
                count++;
            } else {
                count = 0;
            }
            if (count > 1) {
                return;
            }
            Thread.sleep(500);
        }
    }

    public void stopAListServer() {
        log.info("stop AList server");
        try {
            ProcessBuilder builder = new ProcessBuilder();
            builder.inheritIO();
            builder.command("pkill", "-f", "/opt/alist/alist");
            builder.directory(new File("/opt/alist"));
            Process process = builder.start();
            process.waitFor(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void restartAListServer() {
        stopAListServer();
        startAListServer(false);
    }

    public void validateAListStatus() {
        getAListStatus();
        if (aListStatus == 1) {
            throw new BadRequestException("AList服务启动中");
        }
        if (aListStatus == 0) {
            throw new BadRequestException("AList服务未启动");
        }
    }

    public int getAListStatus() {
        try {
            ResponseEntity<SettingResponse> response = restTemplate.getForEntity("http://localhost:5244/api/public/settings", SettingResponse.class);
            if (response.getBody() != null) {
                if (response.getBody().getCode() == 200) {
                    aListStatus = 2;
                    return 2;
                } else if (response.getBody().getCode() == 500) {
                    aListStatus = 1;
                    return 1;
                }
            }
        } catch (Exception e) {
            log.warn("{}", e.getMessage());
        }
        aListStatus = 0;
        return 0;
    }

}
