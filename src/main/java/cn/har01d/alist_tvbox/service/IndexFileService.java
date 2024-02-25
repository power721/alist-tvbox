package cn.har01d.alist_tvbox.service;

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
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
public class IndexFileService {
    public Page<String> getIndexContent(Pageable pageable, String siteId, String index) throws IOException {
        List<String> list = new ArrayList<>();
        Path file = Paths.get("/data/index", siteId, index + ".txt");
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
        Path file = Paths.get("/data/index", siteId, indexName + ".txt");
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
            File fileToZip = new File("/data/index/" + siteId);
            Utils.zipFile(fileToZip, fileToZip.getName(), zipOut);
        }
        return new FileSystemResource(out);
    }

    public void uploadIndexFile(String siteId, String indexName, MultipartFile file) throws IOException {
        Path temp = Paths.get("/tmp/index.txt");
        try {
            FileUtils.copyToFile(file.getInputStream(), temp.toFile());
            List<String> lines = Files.readAllLines(temp);
            if (lines.stream().anyMatch(e -> !isValid(e))) {
                throw new BadRequestException("索引格式不正确");
            }

            lines = lines.stream().map(e -> e.startsWith("./") ? e.substring(1) : e).toList();
            Path path = Paths.get("/data/index", siteId, indexName + ".txt");
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
}
