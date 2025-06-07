package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.SearchSetting;
import cn.har01d.alist_tvbox.service.IndexFileService;
import cn.har01d.alist_tvbox.service.SettingService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/index-files")
public class IndexFileController {
    private final IndexFileService service;
    private final SettingService settingService;

    public IndexFileController(IndexFileService service, SettingService settingService) {
        this.service = service;
        this.settingService = settingService;
    }

    @GetMapping
    public Page<String> getIndexContent(Pageable pageable, String siteId, String indexName) throws IOException {
        return service.getIndexContent(pageable, siteId, indexName);
    }

    @GetMapping("/settings")
    public SearchSetting getSearchSetting() {
        return settingService.getSearchSetting();
    }

    @PostMapping("/settings")
    public SearchSetting setSearchSetting(@RequestBody SearchSetting searchSetting) {
        return settingService.setSearchSetting(searchSetting);
    }

    @DeleteMapping
    public void deleteIndexFile(String siteId, String indexName) throws IOException {
        service.deleteIndexFile(siteId, indexName);
    }

    @PostMapping("/exclude")
    public void toggleExcluded(String siteId, int index, String indexName) throws IOException {
        service.toggleExcluded(siteId, index, indexName);
    }

    @PostMapping("/upload")
    public void uploadIndexFile(String siteId, String indexName, @RequestParam("file") MultipartFile file) throws IOException {
        service.uploadIndexFile(siteId, indexName, file);
    }

    @GetMapping("/download")
    public FileSystemResource downloadIndexFile(String siteId, HttpServletResponse response) throws IOException {
        response.addHeader("Content-Disposition", "attachment; filename=\"index.zip\"");
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        return service.downloadIndexFile(siteId);
    }
}
