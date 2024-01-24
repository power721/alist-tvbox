package cn.har01d.alist_tvbox.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
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
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service

public class LogsService {

    private String fixLine(String text) {
        return text
                .replace(" ERROR ", " <span class=\"error\">ERROR</span> ")
                .replace(" WARN ", " <span class=\"error\">WARN</span> ")
                .replace(" INFO ", " <span class=\"info\">INFO</span> ")
                .replace("\u001b[31m", "<span class=\"error\">")
                .replace("\u001b[36m", "<span class=\"success\">")
                .replace("\u001b[0m", " </span>");
    }

    private Path getLogFile(String type) {
        if ("alist".equals(type)) {
            return Paths.get("/opt/alist/log/alist.log");
        } else if ("init".equals(type)) {
            return Paths.get("/data/log/init.log");
        } else {
            return Paths.get("/data/log/app.log");
        }
    }

    public Page<String> getLogs(Pageable pageable, String type) throws IOException {
        Path file = getLogFile(type);
        List<String> lines = Files.readAllLines(file);
        int size = pageable.getPageSize();
        int start = pageable.getPageNumber() * size;
        int end = start + size;
        if (end > lines.size()) {
            end = lines.size();
        }

        List<String> list = new ArrayList<>();
        if (start < end) {
            list = lines.subList(start, end).stream().map(this::fixLine).collect(Collectors.toList());
        }

        return new PageImpl<>(list, pageable, lines.size());
    }

    public FileSystemResource downloadLog() throws IOException {
        File file = new File("/opt/alist/log/alist.log");
        if (file.exists()) {
            FileUtils.copyFileToDirectory(file, new File("/opt/atv/log/"));
        }

        File out = new File("/tmp/log.zip");
        out.createNewFile();
        try (FileOutputStream fos = new FileOutputStream(out);
             ZipOutputStream zipOut = new ZipOutputStream(fos)) {
            File fileToZip = new File("/opt/atv/log/");
            zipFile(fileToZip, "", zipOut);
        }
        return new FileSystemResource(out);
    }

    private static void zipFile(File fileToZip, String fileName, ZipOutputStream zipOut) throws IOException {
        if (fileToZip.isHidden()) {
            return;
        }

        if (fileToZip.isDirectory()) {
            File[] children = fileToZip.listFiles();
            if (children == null) {
                return;
            }

            for (File childFile : children) {
                if (childFile.isFile()) {
                    zipFile(childFile, fileName + "/" + childFile.getName(), zipOut);
                }
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
