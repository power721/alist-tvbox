package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.CatFilesResult;
import cn.har01d.alist_tvbox.dto.CatUploadResult;
import cn.har01d.alist_tvbox.service.CatPackageService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 猫源自定义爬虫上传与管理。爬虫落 /www/cat/custom 并登记清单,bundle 启动时自动装载,
 * 详见 docs/cat-package-upload-design.md。
 */
@RestController
@RequestMapping("/api/cat")
@PreAuthorize("hasAnyAuthority('ADMIN', 'CLIENT')")
public class CatPackageController {
    private final CatPackageService catPackageService;

    public CatPackageController(CatPackageService catPackageService) {
        this.catPackageService = catPackageService;
    }

    @PostMapping("/upload")
    public CatUploadResult upload(@RequestParam("file") MultipartFile file,
                                  @RequestParam(defaultValue = "false") boolean autoExtract,
                                  @RequestParam(required = false) String name) throws IOException {
        return catPackageService.upload(file, autoExtract, name);
    }

    @GetMapping("/files")
    public CatFilesResult files() throws IOException {
        return catPackageService.list();
    }

    @DeleteMapping("/file")
    public void delete(@RequestParam String path) throws IOException {
        catPackageService.delete(path);
    }

    // 浏览器直开下载无法带 Authorization 头,经 TokenFilter 的 query-token 白名单放行(同静态文件下载)
    @GetMapping("/download")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> download(
            @RequestParam String path) throws IOException {
        java.nio.file.Path file = catPackageService.requireFile(path);
        String filename = path.substring(path.lastIndexOf('/') + 1);
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .body(new org.springframework.core.io.FileSystemResource(file));
    }
}
