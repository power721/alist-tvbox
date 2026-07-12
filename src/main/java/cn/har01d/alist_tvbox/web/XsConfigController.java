package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.service.FileDownloader;
import cn.har01d.alist_tvbox.util.Utils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/xs")
public class XsConfigController {
    private final FileDownloader fileDownloader;

    public XsConfigController(FileDownloader fileDownloader) {
        this.fileDownloader = fileDownloader;
    }

    @GetMapping("/version")
    public Object version() throws IOException {
        String remote = fileDownloader.getXsVersion();
        String local = "";
        Path path = Utils.getDataPath("xs_version.txt");
        if (Files.exists(path)) {
            local = Files.readString(path);
        }

        return Map.of("local", local, "remote", remote);
    }
}
