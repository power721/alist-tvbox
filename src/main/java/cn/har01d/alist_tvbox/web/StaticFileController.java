package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.StaticFileInfo;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.util.Utils;
import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Slf4j
@RestController
@RequestMapping("/api/static-files")
public class StaticFileController {

    private static final String URL_PREFIX = "/static/";

    @GetMapping
    public List<StaticFileInfo> list(@RequestParam(defaultValue = "") String dir) throws IOException {
        Path basePath = Utils.getWebPath("static");
        Path targetPath = basePath;
        if (!dir.isEmpty()) {
            Path resolved = basePath.resolve(dir).normalize();
            if (!resolved.startsWith(basePath)) {
                throw new BadRequestException("非法路径");
            }
            targetPath = resolved;
        }

        if (!Files.exists(targetPath)) {
            Files.createDirectories(targetPath);
            return List.of();
        }

        if (!Files.isDirectory(targetPath)) {
            throw new BadRequestException("不是目录");
        }

        List<StaticFileInfo> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(targetPath)) {
            stream.forEach(path -> {
                StaticFileInfo info = buildFileInfo(basePath, path);
                result.add(info);
            });
        }

        result.sort(Comparator.comparing(StaticFileInfo::isDirectory).reversed()
                .thenComparing(StaticFileInfo::getName));
        return result;
    }

    @PostMapping("/mkdir")
    public void mkdir(@RequestParam String path) throws IOException {
        Path basePath = Utils.getWebPath("static");
        Path targetPath = basePath.resolve(path).normalize();
        if (!targetPath.startsWith(basePath)) {
            throw new BadRequestException("非法路径");
        }
        if (Files.exists(targetPath)) {
            throw new BadRequestException("文件或目录已存在");
        }
        Files.createDirectories(targetPath);
        log.info("created directory: {}", targetPath);
    }

    @PostMapping("/upload")
    public void upload(@RequestParam String dir, @RequestParam("file") MultipartFile file,
                       @RequestParam(defaultValue = "false") boolean extract) throws IOException {
        Path basePath = Utils.getWebPath("static");
        Path targetDir = basePath.resolve(dir).normalize();
        if (!targetDir.startsWith(basePath)) {
            throw new BadRequestException("非法路径");
        }

        String filename = file.getOriginalFilename();
        if (filename == null || filename.isEmpty()) {
            throw new BadRequestException("文件名不能为空");
        }

        Files.createDirectories(targetDir);

        if (extract && filename.toLowerCase().endsWith(".zip")) {
            try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        Path dirPath = targetDir.resolve(entry.getName()).normalize();
                        if (dirPath.startsWith(targetDir)) {
                            Files.createDirectories(dirPath);
                        }
                    } else {
                        Path filePath = targetDir.resolve(entry.getName()).normalize();
                        if (filePath.startsWith(targetDir)) {
                            Files.createDirectories(filePath.getParent());
                            Files.copy(zis, filePath, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                    zis.closeEntry();
                }
            }
            log.info("extracted zip to: {}", targetDir);
        } else {
            Path filePath = targetDir.resolve(filename).normalize();
            if (!filePath.startsWith(basePath)) {
                throw new BadRequestException("非法文件名");
            }
            file.transferTo(filePath.toFile());
            log.info("uploaded file: {}", filePath);
        }
    }

    @DeleteMapping
    public void delete(@RequestParam String path) throws IOException {
        Path basePath = Utils.getWebPath("static");
        Path targetPath = basePath.resolve(path).normalize();
        if (!targetPath.startsWith(basePath) || targetPath.equals(basePath)) {
            throw new BadRequestException("非法路径");
        }
        if (!Files.exists(targetPath)) {
            throw new BadRequestException("文件或目录不存在");
        }

        if (Files.isDirectory(targetPath)) {
            try (Stream<Path> stream = Files.walk(targetPath)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        log.warn("failed to delete: {}", p, e);
                    }
                });
            }
        } else {
            Files.delete(targetPath);
        }
        log.info("deleted: {}", targetPath);
    }

    @PostMapping("/rename")
    public void rename(@RequestParam String path, @RequestParam String newName) throws IOException {
        Path basePath = Utils.getWebPath("static");
        Path targetPath = basePath.resolve(path).normalize();
        if (!targetPath.startsWith(basePath) || targetPath.equals(basePath)) {
            throw new BadRequestException("非法路径");
        }
        if (!Files.exists(targetPath)) {
            throw new BadRequestException("文件或目录不存在");
        }
        if (newName == null || newName.isEmpty() || newName.contains("/") || newName.contains("..")) {
            throw new BadRequestException("文件名不合法");
        }

        Path newPath = targetPath.getParent().resolve(newName).normalize();
        if (!newPath.startsWith(basePath)) {
            throw new BadRequestException("非法文件名");
        }
        if (Files.exists(newPath)) {
            throw new BadRequestException("文件或目录已存在");
        }

        Files.move(targetPath, newPath);
        log.info("renamed {} -> {}", targetPath, newPath);
    }

    @DeleteMapping("/batch")
    public int batchDelete(@RequestBody Map<String, List<String>> body) throws IOException {
        List<String> paths = body.get("paths");
        if (paths == null || paths.isEmpty()) {
            throw new BadRequestException("路径列表不能为空");
        }
        Path basePath = Utils.getWebPath("static");
        int count = 0;
        for (String p : paths) {
            Path targetPath = basePath.resolve(p).normalize();
            if (!targetPath.startsWith(basePath) || targetPath.equals(basePath)) {
                continue;
            }
            if (!Files.exists(targetPath)) {
                continue;
            }
            if (Files.isDirectory(targetPath)) {
                try (Stream<Path> stream = Files.walk(targetPath)) {
                    stream.sorted(Comparator.reverseOrder()).forEach(file -> {
                        try {
                            Files.delete(file);
                        } catch (IOException e) {
                            log.warn("failed to delete: {}", file, e);
                        }
                    });
                }
            } else {
                Files.delete(targetPath);
            }
            log.info("deleted: {}", targetPath);
            count++;
        }
        return count;
    }

    @GetMapping("/dirs")
    public List<Map<String, Object>> listDirs() throws IOException {
        Path basePath = Utils.getWebPath("static");
        if (!Files.exists(basePath)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        scanDirs(basePath, basePath, result);
        return result;
    }

    private void scanDirs(Path basePath, Path current, List<Map<String, Object>> result) throws IOException {
        try (Stream<Path> stream = Files.list(current)) {
            stream.filter(Files::isDirectory)
                    .sorted()
                    .forEach(dir -> {
                        try {
                            scanDirs(basePath, dir, result);
                        } catch (IOException e) {
                            log.warn("failed to scan dir: {}", dir, e);
                        }
                    });
        }

        Map<String, Object> node = new LinkedHashMap<>();
        String relativePath = basePath.relativize(current).toString();
        node.put("name", relativePath.isEmpty() ? "/" : current.getFileName().toString());
        node.put("path", relativePath);

        List<Map<String, Object>> children = new ArrayList<>();
        try (Stream<Path> stream = Files.list(current)) {
            stream.filter(Files::isDirectory)
                    .sorted()
                    .forEach(subDir -> {
                        String subRelative = basePath.relativize(subDir).toString();
                        Map<String, Object> child = new LinkedHashMap<>();
                        child.put("name", subDir.getFileName().toString());
                        child.put("path", subRelative);
                        child.put("children", collectSubDirs(basePath, subDir));
                        children.add(child);
                    });
        }
        node.put("children", children);
        result.add(node);
    }

    private List<Map<String, Object>> collectSubDirs(Path basePath, Path dir) {
        List<Map<String, Object>> children = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isDirectory)
                    .sorted()
                    .forEach(subDir -> {
                        String subRelative = basePath.relativize(subDir).toString();
                        Map<String, Object> child = new LinkedHashMap<>();
                        child.put("name", subDir.getFileName().toString());
                        child.put("path", subRelative);
                        child.put("children", collectSubDirs(basePath, subDir));
                        children.add(child);
                    });
        } catch (IOException e) {
            log.warn("failed to list dir: {}", dir, e);
        }
        return children;
    }

    @PostMapping("/move")
    public int move(@RequestBody Map<String, Object> body) throws IOException {
        @SuppressWarnings("unchecked")
        List<String> paths = (List<String>) body.get("paths");
        String targetDir = (String) body.get("targetDir");
        if (paths == null || paths.isEmpty()) {
            throw new BadRequestException("路径列表不能为空");
        }
        Path basePath = Utils.getWebPath("static");
        Path destDir = basePath;
        if (targetDir != null && !targetDir.isEmpty()) {
            destDir = basePath.resolve(targetDir).normalize();
            if (!destDir.startsWith(basePath)) {
                throw new BadRequestException("非法目标路径");
            }
        }
        Files.createDirectories(destDir);

        int count = 0;
        for (String p : paths) {
            Path srcPath = basePath.resolve(p).normalize();
            if (!srcPath.startsWith(basePath) || srcPath.equals(basePath)) {
                continue;
            }
            if (!Files.exists(srcPath)) {
                continue;
            }
            Path newPath = destDir.resolve(srcPath.getFileName()).normalize();
            if (!newPath.startsWith(basePath)) {
                continue;
            }
            if (Files.exists(newPath)) {
                log.warn("target already exists, skip: {}", newPath);
                continue;
            }
            Files.move(srcPath, newPath);
            log.info("moved {} -> {}", srcPath, newPath);
            count++;
        }
        return count;
    }

    @GetMapping("/download")
    public void download(@RequestParam String path, HttpServletResponse response) throws IOException {
        Path basePath = Utils.getWebPath("static");
        Path targetPath = basePath.resolve(path).normalize();
        if (!targetPath.startsWith(basePath) || targetPath.equals(basePath)) {
            throw new BadRequestException("非法路径");
        }
        if (!Files.exists(targetPath)) {
            throw new BadRequestException("文件或目录不存在");
        }

        String fileName = targetPath.getFileName().toString();
        if (Files.isDirectory(targetPath)) {
            String zipName = fileName + ".zip";
            response.setContentType("application/zip");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + zipName + "\"");
            try (ZipOutputStream zos = new ZipOutputStream(response.getOutputStream())) {
                try (Stream<Path> stream = Files.walk(targetPath)) {
                    stream.forEach(file -> {
                        try {
                            String entryName = fileName + "/" + basePath.relativize(file).toString().substring(path.length() + 1);
                            if (Files.isDirectory(file)) {
                                zos.putNextEntry(new ZipEntry(entryName + "/"));
                                zos.closeEntry();
                            } else {
                                zos.putNextEntry(new ZipEntry(entryName));
                                Files.copy(file, zos);
                                zos.closeEntry();
                            }
                        } catch (IOException e) {
                            log.warn("failed to add file to zip: {}", file, e);
                        }
                    });
                }
            }
            log.info("downloaded dir as zip: {}", targetPath);
        } else {
            String contentType = Files.probeContentType(targetPath);
            response.setContentType(contentType != null ? contentType : MediaType.APPLICATION_OCTET_STREAM_VALUE);
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"");
            response.setContentLengthLong(Files.size(targetPath));
            Files.copy(targetPath, response.getOutputStream());
            log.info("downloaded file: {}", targetPath);
        }
    }

    @PostMapping("/batch-download")
    public void batchDownload(@RequestBody Map<String, List<String>> body, HttpServletResponse response) throws IOException {
        List<String> paths = body.get("paths");
        if (paths == null || paths.isEmpty()) {
            throw new BadRequestException("路径列表不能为空");
        }
        Path basePath = Utils.getWebPath("static");

        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"download.zip\"");
        try (ZipOutputStream zos = new ZipOutputStream(response.getOutputStream())) {
            for (String p : paths) {
                Path targetPath = basePath.resolve(p).normalize();
                if (!targetPath.startsWith(basePath) || targetPath.equals(basePath) || !Files.exists(targetPath)) {
                    continue;
                }
                String entryPrefix = targetPath.getFileName().toString();
                if (Files.isDirectory(targetPath)) {
                    try (Stream<Path> stream = Files.walk(targetPath)) {
                        stream.forEach(file -> {
                            try {
                                String relative = targetPath.getParent().relativize(file).toString();
                                if (Files.isDirectory(file)) {
                                    zos.putNextEntry(new ZipEntry(relative + "/"));
                                    zos.closeEntry();
                                } else {
                                    zos.putNextEntry(new ZipEntry(relative));
                                    Files.copy(file, zos);
                                    zos.closeEntry();
                                }
                            } catch (IOException e) {
                                log.warn("failed to add to zip: {}", file, e);
                            }
                        });
                    }
                } else {
                    zos.putNextEntry(new ZipEntry(entryPrefix));
                    Files.copy(targetPath, zos);
                    zos.closeEntry();
                }
            }
        }
        log.info("batch downloaded {} items", paths.size());
    }

    private StaticFileInfo buildFileInfo(Path basePath, Path path) {
        String relativePath = basePath.relativize(path).toString();
        String url = URL_PREFIX + relativePath;
        boolean isDir = Files.isDirectory(path);

        long size = 0;
        long lastModified = 0;
        try {
            if (!isDir) {
                size = Files.size(path);
            }
            lastModified = Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            log.warn("failed to read file info: {}", path, e);
        }

        return new StaticFileInfo(path.getFileName().toString(), relativePath, size, lastModified, isDir, url);
    }
}
