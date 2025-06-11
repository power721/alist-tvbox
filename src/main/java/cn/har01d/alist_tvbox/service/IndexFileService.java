package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.TaskType;
import cn.har01d.alist_tvbox.dto.ValidateRequest;
import cn.har01d.alist_tvbox.entity.Task;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
public class IndexFileService {
    private final int maxFolderSize = 30;
    private final AListService aListService;
    private final SettingService settingService;
    private final TaskService taskService;
    private final AppProperties appProperties;

    public IndexFileService(AListService aListService, SettingService settingService, TaskService taskService, AppProperties appProperties) {
        this.aListService = aListService;
        this.settingService = settingService;
        this.taskService = taskService;
        this.appProperties = appProperties;
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

    public void validateIndexFiles(boolean updateExcludePath, int depth) throws InterruptedException {
        if (depth < 1 || depth > 4) {
            throw new BadRequestException("校验层级无效");
        }
        if (taskService.isTaskRunning(TaskType.VALIDATE_INDEX)) {
            throw new BadRequestException("校验任务进行中");
        }

        Task task = taskService.addValidateIndexTask();
        taskService.startTask(task.getId());
        Set<String> paths = new HashSet<>();
        List<ValidateRequest> requests = new ArrayList<>();
        Map<String, ValidateRequest> map = new HashMap<>();
        List<String> files = new ArrayList<>(settingService.getSearchSources());
        String base = Utils.getIndexPath() + "/";
        for (File file : Utils.listFiles(Utils.getIndexPath("1"), "txt")) {
            files.add(file.getAbsolutePath().replace(base, ""));
        }

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
                    ValidateRequest depth1 = null;
                    ValidateRequest depth2 = null;
                    ValidateRequest depth3 = null;
                    if (split.length > 1) {
                        path += "/" + split[1];
                        if (paths.add(path)) {
                            depth1 = new ValidateRequest(path);
                            requests.add(depth1);
                            map.put(path, depth1);
                        } else {
                            depth1 = map.get(path);
                        }
                    }
                    if (depth > 1 && split.length > 2) {
                        path += "/" + split[2];
                        if (paths.add(path)) {
                            depth2 = new ValidateRequest(path);
                            map.put(path, depth2);
                            depth1.getChildren().add(depth2);
                        } else {
                            depth2 = map.get(path);
                        }
                    }
                    if (depth > 2 && split.length > 3) {
                        path += "/" + split[3];
                        if (depth2.getChildren().size() < maxFolderSize) {
                            if (!isMediaFile(path) && paths.add(path)) {
                                depth3 = new ValidateRequest(path);
                                map.put(path, depth3);
                                depth2.getChildren().add(depth3);
                            } else {
                                depth3 = map.get(path);
                            }
                        } else {
                            for (var child : depth2.getChildren()) {
                                paths.remove(child.getPath());
                            }
                        }
                    }
                    if (depth > 3 && split.length > 4) {
                        path += "/" + split[4];
                        if (depth3 != null && depth3.getChildren().size() < maxFolderSize) {
                            if (!isMediaFile(path) && paths.add(path)) {
                                depth3.getChildren().add(new ValidateRequest(path));
                            }
                        } else if (depth3 != null) {
                            for (var child : depth3.getChildren()) {
                                paths.remove(child.getPath());
                            }
                        }
                    }
                });
            } catch (IOException e) {
                log.error("read index file {} failed", file, e);
            }
        }
        taskService.updateTaskSummary(task.getId(), "校验" + paths.size() + "个路径");
        log.info("validate {} paths: {}", paths.size(), paths);

        List<String> results = new ArrayList<>();
        for (var request : requests) {
            if (taskService.isCancelled(task.getId())) {
                log.warn("task is cancelled");
                return;
            }
            results.addAll(validate(task.getId(), request));
        }
        log.info("invalid paths: {} {}", results.size(), results);
        taskService.completeTask(task.getId(), results.size() + "个失效路径", String.join("\n", results));

        if (updateExcludePath) {
            settingService.setExcludedPaths(results);
        }
    }

    public List<String> validate(int taskId, ValidateRequest request) throws InterruptedException {
        List<String> results = new ArrayList<>();
        if (taskService.isCancelled(taskId)) {
            log.warn("task is cancelled");
            return results;
        }

        String path = request.getPath();
        var result = aListService.validate(path);
        if (!result.success()) {
            if (result.message().contains("object not found")) {
                results.add(path);
            }
            log.warn("validate path {} failed: {}", path, result.message());
            return results;
        }

        if (request.getChildren().size() >= maxFolderSize) {
            return results;
        }

        for (var child : request.getChildren()) {
            Thread.sleep(500);
            results.addAll(validate(taskId, child));
        }

        return results;
    }

    private boolean isMediaFile(String path) {
        String name = path;
        int index = path.lastIndexOf('/');
        if (index > -1) {
            name = path.substring(index + 1);
        }
        return isMediaFormat(name);
    }

    private boolean isMediaFormat(String name) {
        int index = name.lastIndexOf('.');
        if (index > 0) {
            String suffix = name.substring(index + 1).toLowerCase();
            return appProperties.getFormats().contains(suffix);
        }
        return false;
    }
}
