package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.exception.BadRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
public class IndexFileService {
    public Page<String> getIndexContent(Pageable pageable, String siteId) throws IOException {
        Path file = Paths.get("/data/index", siteId, "custom_index.txt");
        if (!Files.exists(file)) {
            throw new BadRequestException("索引文件不存在");
        }

        List<String> lines = Files.readAllLines(file);
        int size = pageable.getPageSize();
        int start = pageable.getPageNumber() * size;
        int end = start + size;
        if (end > lines.size()) {
            end = lines.size();
        }

        List<String> list = new ArrayList<>();
        if (start < end) {
            list = lines.subList(start, end);
        }

        return new PageImpl<>(list, pageable, lines.size());
    }

    public void toggleExcluded(String siteId, int index) throws IOException {
        if (index < 0) {
            throw new BadRequestException("行数不正确");
        }
        Path file = Paths.get("/data/index", siteId, "custom_index.txt");
        if (!Files.exists(file)) {
            throw new BadRequestException("索引文件不存在");
        }

        List<String> lines = Files.readAllLines(file);
        if (index >= lines.size()) {
            throw new BadRequestException("行数不正确");
        }

        String line = lines.get(index);
        if (line.startsWith("-")) {
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
            zipFile(fileToZip, fileToZip.getName(), zipOut);
        }
        return new FileSystemResource(out);
    }

    private static void zipFile(File fileToZip, String fileName, ZipOutputStream zipOut) throws IOException {
        if (fileToZip.isHidden()) {
            return;
        }

        if (fileToZip.isDirectory()) {
            if (fileName.endsWith("/")) {
                zipOut.putNextEntry(new ZipEntry(fileName));
                zipOut.closeEntry();
            } else {
                zipOut.putNextEntry(new ZipEntry(fileName + "/"));
                zipOut.closeEntry();
            }

            File[] children = fileToZip.listFiles();
            if (children == null) {
                return;
            }

            for (File childFile : children) {
                zipFile(childFile, fileName + "/" + childFile.getName(), zipOut);
            }
            return;
        }

        try (FileInputStream fis = new FileInputStream(fileToZip)) {
            ZipEntry zipEntry = new ZipEntry(fileName);
            zipOut.putNextEntry(zipEntry);
            byte[] bytes = new byte[4096];
            int length;
            while ((length = fis.read(bytes)) >= 0) {
                zipOut.write(bytes, 0, length);
            }
        }
    }

}
