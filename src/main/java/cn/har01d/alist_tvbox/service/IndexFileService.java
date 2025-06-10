package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.ValidateResult;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.util.Utils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
public class IndexFileService {
    private final AListService aListService;
    private final SettingService settingService;

    public IndexFileService(AListService aListService, SettingService settingService) {
        this.aListService = aListService;
        this.settingService = settingService;
    }

    public Page<String> getIndexContent(Pageable pageable, String siteId, String index) throws IOException {
        List<String> list = new ArrayList<>();
        Path file = Utils.getIndexPath(siteId, index + ".txt");
        if (!Files.exists(file)) {
            return new PageImpl<>(list);
        }

        List<String> lines = Files.readAllLines(file);
        int size = pageable.getPageSize();
        int start = pageable.getPageNumber() * size;
        int end = start + size;
        if (end > lines.size()) {
            end = lines.size();
        }

        if (start < end) {
            list = lines.subList(start, end);
        }

        return new PageImpl<>(list, pageable, lines.size());
    }

    public void toggleExcluded(String siteId, int index, String indexName) throws IOException {
        if (index < 0) {
            throw new BadRequestException("行数不正确");
        }
        Path file = Utils.getIndexPath(siteId, indexName + ".txt");
        if (!Files.exists(file)) {
            throw new BadRequestException("索引文件不存在");
        }

        List<String> lines = Files.readAllLines(file);
        if (index >= lines.size()) {
            throw new BadRequestException("行数不正确");
        }

        String line = lines.get(index);
        if (line.startsWith("-")) {
            line = "+" + line.substring(1);
        } else if (line.startsWith("+")) {
            line = line.substring(1);
        } else {
            line = "-" + line;
        }
        lines.set(index, line);
        Files.writeString(file, String.join("\n", lines));
    }

    public FileSystemResource downloadIndexFile(String siteId) throws IOException {
        File out = new File("/tmp/index.zip");
        out.createNewFile();
        try (FileOutputStream fos = new FileOutputStream(out);
             ZipOutputStream zipOut = new ZipOutputStream(fos)) {
            File fileToZip = Utils.getIndexPath(siteId).toFile();
            Utils.zipFile(fileToZip, fileToZip.getName(), zipOut);
        }
        return new FileSystemResource(out);
    }

    public void uploadIndexFile(String siteId, String indexName, MultipartFile file) throws IOException {
        Path temp = Path.of("/tmp/index.txt");
        try {
            FileUtils.copyToFile(file.getInputStream(), temp.toFile());
            List<String> lines = Files.readAllLines(temp);
            if (lines.stream().anyMatch(e -> !isValid(e))) {
                throw new BadRequestException("索引格式不正确");
            }

            lines = lines.stream().map(e -> e.startsWith("./") ? e.substring(1) : e).toList();
            Path path = Utils.getIndexPath(siteId, indexName + ".txt");
            if (!Files.exists(path)) {
                Files.createDirectories(path.getParent());
                Files.createFile(path);
            }
            Files.writeString(path, String.join("\n", lines));
            log.info("上传索引文件成功： {}", path);
        } finally {
            Files.delete(temp);
        }
    }

    private boolean isValid(String line) {
        return line.startsWith("-/") || line.startsWith("+/") || line.startsWith("/") || line.startsWith("./") || line.isBlank();
    }

    public void deleteIndexFile(String siteId, String indexName) throws IOException {
        Path path = Utils.getIndexPath(siteId, indexName + ".txt");
        Files.delete(path);
    }

    public void validate() {
        Set<String> paths = new HashSet<>();
        List<String> files = settingService.getSearchSources();
        log.info("validate index files: {}", files);
        for (String file : files) {
            try (Stream<String> stream = Files.lines(Utils.getIndexPath(file))) {
                stream.forEach(line -> {
                    if (line.startsWith(".")) {
                        line = line.substring(1);
                    }
                    int index = line.indexOf("#");
                    if (index > 0) {
                        line = line.substring(0, index);
                    }
                    line = line
                            .replace("\uD83C\uDFF7\uFE0F我的115分享", "我的115分享")
                            .replace("\uD83C\uDFF7\uFE0F 我的115分享", "我的115分享")
                            .replace("\uD83C\uDF00我的夸克分享", "我的夸克分享");
                    String[] split = line.split("/");
                    String path = "";
                    if (split.length > 1) {
                        path += "/" + split[1];
                        paths.add(path);
                    }
                    if (split.length > 2) {
                        path += "/" + split[2];
                        paths.add(path);
                    }
                });
            } catch (IOException e) {
                log.error("read index file {} failed", file, e);
            }
        }
        log.info("validate {} paths: {}", paths.size(), paths);

        List<String> results = new ArrayList<>();
        for (String path : paths) {
            ValidateResult result = aListService.validate(path);
            if (!result.success()) {
                if (result.message().contains("object not found")) {
                    results.add(path);
                }
                log.warn("validate path {} failed: {}", path, result.message());
            }
        }
        log.info("invalid paths: {} {}", results.size(), results);
    }
}
