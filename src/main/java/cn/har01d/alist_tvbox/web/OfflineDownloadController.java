package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.OfflineDownloadConfigDto;
import cn.har01d.alist_tvbox.dto.OfflineDownloadConfigRequest;
import cn.har01d.alist_tvbox.dto.OfflineDownloadQuotaResponse;
import cn.har01d.alist_tvbox.dto.OfflineDownloadRequest;
import cn.har01d.alist_tvbox.service.OfflineDownloadService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/offline_download")
public class OfflineDownloadController {
    private final OfflineDownloadService offlineDownloadService;

    public OfflineDownloadController(OfflineDownloadService offlineDownloadService) {
        this.offlineDownloadService = offlineDownloadService;
    }

    @GetMapping("/config")
    public OfflineDownloadConfigDto getConfig() {
        return offlineDownloadService.getConfig();
    }

    @GetMapping("/quota")
    public OfflineDownloadQuotaResponse getQuota() {
        return offlineDownloadService.getQuota();
    }

    @PostMapping("/config")
    public OfflineDownloadConfigDto saveConfig(@RequestBody OfflineDownloadConfigRequest request) {
        return offlineDownloadService.saveConfig(request);
    }
}
