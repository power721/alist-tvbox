package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
public class LogsService {
    private final ObjectMapper objectMapper;

    private String aListLogPath = "/opt/alist/log/alist.log";

    public LogsService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        readAListLogPath();
    }

    public void readAListLogPath() {
        Path path = Path.of(Utils.getAListPath("data/config.json"));
        if (Files.exists(path)) {
            try {
                String text = Files.readString(path);
                JsonNode json = objectMapper.readTree(text);
                aListLogPath = json.get("log").get("name").asText();
                log.info("AList log path: {}", aListLogPath);
            } catch (IOException e) {
                log.warn("read AList config failed", e);
            }
        }
    }

    private String fixLine(String text) {
        return text
                .replace(" ERROR ", " <span class=\"error\">ERROR</span> ")
                .replace(" WARN ", " <span class=\"error\">WARN</span> ")
                .replace(" INFO ", " <span class=\"info\">INFO</span> ")
                .replace("\u001b[31m", "<span class=\"error\">")
                .replace("\u001b[33m", "<span class=\"error\">")
                .replace("\u001b[36m", "<span class=\"success\">")
                .replace("\u001b[37m", "<span class=\"debug\">")
                .replace("\u001b[0m", "</span> ")
                ;
    }

    private Path getLogFile(String type) {
        if ("alist".equals(type)) {
            return Path.of(aListLogPath);
        } else if ("init".equals(type)) {
            return Utils.getLogPath("init.log");
        } else {
            return Utils.getLogPath("app.log");
        }
    }

    public Page<String> getLogs(Pageable pageable, String type, String level) throws IOException {
        Path file = getLogFile(type);
        int size = pageable.getPageSize();
        int start = pageable.getPageNumber() * size;
        int end = start + size;

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<String> filtered = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!type.equals("app")) {
                filtered.add(fixLine(line));
            } else if (matchLogLevel(line, level)) {
                StringBuilder sb = new StringBuilder(fixLine(line));
                sb.append("<pre>");
                while (i + 1 < lines.size()) {
                    String next = lines.get(i + 1);
                    if (isLogLine(next)) {
                        break;
                    }
                    sb.append(next).append("\n");
                    i++;
                }
                sb.append("</pre>");
                filtered.add(sb.toString().replace("<pre></pre>", ""));
            }
        }

        if (end > filtered.size()) {
            end = filtered.size();
        }

        List<String> result = filtered.subList(start, end);
        return new PageImpl<>(result, pageable, filtered.size());
    }

    private boolean matchLogLevel(String line, String level) {
        if (StringUtils.isBlank(level) || "ALL".equals(level)) {
            return true;
        }
        if (line.startsWith("\u001b[")) {
            line = line.replace("\u001b[31m", "")
                    .replace("\u001b[33m", "")
                    .replace("\u001b[36m", "")
                    .replace("\u001b[37m", "")
                    .replace("\u001b[0m", " ");
            return line.split("\\s+")[0].equals(level);
        }
        String[] parts = line.split("\\s+");
        if (parts.length > 7 && parts[3].equals("---") && parts[1].equals(level)) {
            return true;
        }
        return parts.length > 3 && parts[2].equals(level);
    }

    private boolean isLogLine(String line) {
        String[] parts = line.split("\\s+");
        if (parts.length > 7 && parts[3].equals("---") && (parts[1].equals("ERROR") || parts[1].equals("WARN") || parts[1].equals("INFO") || parts[1].equals("DEBUG"))) {
            return true;
        }
        if (parts.length > 3 && (parts[2].equals("ERROR") || parts[2].equals("WARN") || parts[2].equals("INFO") || parts[2].equals("DEBUG"))) {
            return true;
        }
        return line.startsWith("\u001b[");
    }

    public FileSystemResource downloadLog() throws IOException {
        File file = new File(aListLogPath);
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
