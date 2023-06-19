package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.AListLogin;
import cn.har01d.alist_tvbox.dto.CheckinResult;
import cn.har01d.alist_tvbox.dto.ShareInfo;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Share;
import cn.har01d.alist_tvbox.model.StorageInfo;
import cn.har01d.alist_tvbox.service.ShareService;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalTime;

@Profile("xiaoya")
@RestController
public class ShareController {
    private final ShareService shareService;
    private final SettingRepository settingRepository;

    public ShareController(ShareService shareService, SettingRepository settingRepository) {
        this.shareService = shareService;
        this.settingRepository = settingRepository;
    }

    @GetMapping("/shares")
    public Page<Share> list(Pageable pageable) {
        return shareService.list(pageable);
    }

    @PostMapping("/shares")
    public Share create(@RequestBody Share share) {
        return shareService.create(share);
    }

    @PostMapping("/shares/{id}")
    public Share update(@PathVariable Integer id, @RequestBody Share share) {
        return shareService.update(id, share);
    }

    @DeleteMapping("/shares/{id}")
    public void delete(@PathVariable Integer id) {
        shareService.delete(id);
    }

    @GetMapping("/resources")
    public Page<ShareInfo> listResources(Pageable pageable) {
        return shareService.listResources(pageable);
    }

    @PostMapping("/login")
    public void updateLogin(@RequestBody AListLogin login) {
        shareService.updateLogin(login);
    }

    @GetMapping("/login")
    public AListLogin getLogin() {
        return shareService.getLoginInfo();
    }

    @GetMapping("/storage")
    public StorageInfo getStorageInfo() {
        return shareService.getStorageInfo();
    }

    @PostMapping("/storage")
    public StorageInfo updateStorageInfo(@RequestBody StorageInfo dto) {
        return shareService.updateStorageInfo(dto);
    }

    @GetMapping("/storages")
    public Object listStorages(Pageable pageable) {
        return shareService.listStorages(pageable);
    }

    @PostMapping("/checkin")
    public CheckinResult checkin(@RequestParam(required = false) boolean force) {
        return shareService.checkin(force);
    }

    @GetMapping("/checkin")
    public Instant getCheckinTime() {
        return shareService.getCheckinTime();
    }

    @PostMapping("/show-my-ali")
    public void showMyAli(@RequestParam(required = false) boolean enabled) {
        shareService.showMyAli(enabled);
    }

    @GetMapping("/show-my-ali")
    public boolean showMyAli() {
        return settingRepository.findById("show_my_ali").map(Setting::getValue).map(Boolean::parseBoolean).orElse(false);
    }

    @PostMapping("/schedule")
    public LocalTime updateScheduleTime(@RequestBody Instant time) {
        return shareService.updateScheduleTime(time);
    }
}
