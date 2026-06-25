package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.domain.TaskType;
import cn.har01d.alist_tvbox.dto.Index115CheckResult;
import cn.har01d.alist_tvbox.dto.Index115ShareRef;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Task;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.util.Utils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
public class Index115Service {
    private static final String SHARE_CODE_KEY = "index115.share_code";

    private final TaskService taskService;
    private final ShareService shareService;
    private final SettingRepository settingRepository;
    private final DriverAccountRepository driverAccountRepository;
    private final Index115VersionClient versionClient;
    private final Index115Downloader downloader;
    private final Index115Extractor extractor;

    public Index115Service(TaskService taskService,
                           ShareService shareService,
                           SettingRepository settingRepository,
                           DriverAccountRepository driverAccountRepository,
                           Index115VersionClient versionClient,
                           Index115Downloader downloader,
                           Index115Extractor extractor) {
        this.taskService = taskService;
        this.shareService = shareService;
        this.settingRepository = settingRepository;
        this.driverAccountRepository = driverAccountRepository;
        this.versionClient = versionClient;
        this.downloader = downloader;
        this.extractor = extractor;
    }

    public boolean has115Account() {
        return driverAccountRepository.findByTypeAndMasterTrue(DriverType.PAN115).isPresent();
    }

    public Index115CheckResult check() {
        if (!has115Account()) {
            return new Index115CheckResult(false, false, "", "", null);
        }
        String local = settingRepository.findById(SHARE_CODE_KEY).map(Setting::getValue).orElse("");
        Index115ShareRef ref = versionClient.fetch();
        if (ref == null) {
            return new Index115CheckResult(true, false, local, "", "无法获取远端版本");
        }
        String remote = ref.shareCode();
        boolean hasUpdate = !local.equals(remote);
        return new Index115CheckResult(true, hasUpdate, local, remote, null);
    }

    public void update() {
        if (taskService.isTaskRunning(TaskType.DOWNLOAD)) {
            throw new BadRequestException("115索引更新任务进行中");
        }
        Task task = taskService.addIndex115Task();
        taskService.startTask(task.getId());
        try {
            Index115ShareRef ref = versionClient.fetch();
            if (ref == null) {
                taskService.failTask(task.getId(), "115.version.txt 解析失败");
                return;
            }
            String last = settingRepository.findById(SHARE_CODE_KEY).map(Setting::getValue).orElse("");
            if (last.equals(ref.shareCode())) {
                taskService.completeTask(task.getId(), "已是最新 " + ref.shareCode(), null);
                return;
            }
            Path zip = Files.createTempFile("index115-", ".zip");
            try {
                downloader.download(ref.shareCode(), ref.receiveCode(), zip);
                extractor.extractAndSwap(zip, Utils.getDataPath("index115"));
                saveShareCode(ref.shareCode());
                taskService.completeTask(task.getId(), "更新到 " + ref.shareCode(), null);
                shareService.reloadIndex115();
            } finally {
                Files.deleteIfExists(zip);
            }
        } catch (Exception e) {
            log.error("index115 update failed", e);
            taskService.failTask(task.getId(), e.getMessage());
        }
    }

    private void saveShareCode(String shareCode) {
        Setting s = settingRepository.findById(SHARE_CODE_KEY).orElseGet(Setting::new);
        if (s.getName() == null) {
            s.setName(SHARE_CODE_KEY);
        }
        s.setValue(shareCode);
        settingRepository.save(s);
    }
}
