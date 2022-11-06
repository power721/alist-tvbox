package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.model.FsInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class PlayListService {
    private final AListService aListService;

    public PlayListService(AListService aListService) {
        this.aListService = aListService;
    }

    @Async
    public void generate(String name, String path) throws IOException {
        if (name == null || name.isEmpty()) {
            name = path.substring(path.lastIndexOf('/') + 1);
        }
        Path file = Path.of(name + ".txt");
        Files.writeString(file, "");
        generate(file, name, path, "");
    }

    public void generate(Path file, String name, String basePath, String path) throws IOException {
        List<FsInfo> list = aListService.listFiles(basePath + path);
        List<String> folders = new ArrayList<>();
        List<String> files = new ArrayList<>();
        for (FsInfo fsInfo : list) {
            if (fsInfo.isDir()) {
                folders.add(fsInfo.getName());
                generate(file, name + " " + fsInfo.getName(), basePath, path + "/" + fsInfo.getName());
            } else if (fsInfo.getType() == 2) {
                files.add(fsInfo.getName());
            }
        }

        if (!files.isEmpty()) {
            log.info("generate: {}", name);
            Files.writeString(file, name + ",#genre#\n", StandardOpenOption.APPEND);
            for (String filename : files) {
                //String url = aListService.getApiUrl("/d" + path + "/" + filename);
                String url = trip(path + "/" + filename);
                log.info("add item: {} {}", filename, url);
                Files.writeString(file, getName(filename) + "," + url + "\n", StandardOpenOption.APPEND);
            }
        }
    }

    private String trip(String path) {
        if (path.startsWith("/")) {
            return path.substring(1);
        }
        return path;
    }

    private String getName(String name) {
        int index = name.lastIndexOf('.');
        if (index > 0) {
            return name.substring(0, index);
        } else {
            return name;
        }
    }
}
