package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.TaskResult;
import cn.har01d.alist_tvbox.domain.TaskStatus;
import cn.har01d.alist_tvbox.dto.FileItem;
import cn.har01d.alist_tvbox.dto.IndexRequest;
import cn.har01d.alist_tvbox.dto.IndexResponse;
import cn.har01d.alist_tvbox.entity.IndexTemplate;
import cn.har01d.alist_tvbox.entity.IndexTemplateRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.Task;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.model.FsInfo;
import cn.har01d.alist_tvbox.model.FsResponse;
import cn.har01d.alist_tvbox.tvbox.IndexContext;
import cn.har01d.alist_tvbox.util.Constants;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static cn.har01d.alist_tvbox.util.Constants.APP_VERSION;
import static cn.har01d.alist_tvbox.util.Constants.DOCKER_VERSION;
import static cn.har01d.alist_tvbox.util.Constants.INDEX_VERSION;

@Slf4j
@Service
public class IndexService {
    private final AListService aListService;
    private final SiteService siteService;
    private final TaskService taskService;
    private final AListLocalService aListLocalService;
    private final AppProperties appProperties;
    private final SettingRepository settingRepository;
    private final IndexTemplateRepository indexTemplateRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final Environment environment;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public IndexService(AListService aListService,
                        SiteService siteService,
                        TaskService taskService,
                        AListLocalService aListLocalService,
                        AppProperties appProperties,
                        SettingRepository settingRepository,
                        IndexTemplateRepository indexTemplateRepository,
                        RestTemplateBuilder builder,
                        ObjectMapper objectMapper,
                        Environment environment) {
        this.aListService = aListService;
        this.siteService = siteService;
        this.taskService = taskService;
        this.aListLocalService = aListLocalService;
        this.appProperties = appProperties;
        this.settingRepository = settingRepository;
        this.indexTemplateRepository = indexTemplateRepository;
        this.restTemplate = builder
                .defaultHeader(HttpHeaders.ACCEPT, Constants.ACCEPT)
                .defaultHeader(HttpHeaders.USER_AGENT, Constants.USER_AGENT1)
                .build();
        this.objectMapper = objectMapper;
        this.environment = environment;
        updateIndexFile();
    }

    @PostConstruct
    public void setup() {
        try {
            Path path = Paths.get("/data/index/version.txt");
            if (Files.exists(path)) {
                List<String> lines = Files.readAllLines(path);
                if (!lines.isEmpty()) {
                    settingRepository.save(new Setting(INDEX_VERSION, lines.get(0).trim()));
                }
            }
        } catch (Exception e) {
            log.warn("", e);
        }

        try {
            Path path = Paths.get("/docker.version");
            if (Files.exists(path)) {
                List<String> lines = Files.readAllLines(path);
                if (!lines.isEmpty()) {
                    settingRepository.save(new Setting(DOCKER_VERSION, lines.get(0).trim()));
                }
            }
        } catch (Exception e) {
            log.warn("", e);
        }

        try {
            Path path = Paths.get("data/app_version");
            if (Files.exists(path)) {
                List<String> lines = Files.readAllLines(path);
                if (!lines.isEmpty()) {
                    settingRepository.save(new Setting(APP_VERSION, lines.get(0).trim()));
                }
            }
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    @Scheduled(cron = "0 0 22 * * ?")
    public void update() {
        getRemoteVersion();
    }

    public String getRemoteVersion() {
        if (!environment.matchesProfiles("xiaoya")) {
            return "";
        }

        try {
            String remote = getVersion();
            String local = settingRepository.findById(INDEX_VERSION).map(Setting::getValue).orElse("").trim();
            log.debug("xiaoya index file local: {} remote: {}", local, remote);
            if (remote != null && !local.equals(remote)) {
                executor.execute(() -> updateXiaoyaIndexFile(remote));
            }
            return remote;
        } catch (Exception e) {
            log.warn("", e);
        }
        return "";
    }

    private String getVersion() {
        String remote;
        try {
            remote = restTemplate.getForObject("http://docker.xiaoya.pro/version.txt", String.class);
        } catch (ResourceAccessException e) {
            remote = restTemplate.getForObject("http://data.har01d.cn/version.txt", String.class);
        }
        return Utils.trim(remote);
    }

    public void updateXiaoyaIndexFile(String remote) {
        try {
            log.info("download xiaoya index file");
            ProcessBuilder builder = new ProcessBuilder();
            builder.command("sh", "-c", "/index.sh", remote);
            builder.inheritIO();
            builder.directory(new File("/tmp"));
            Process process = builder.start();
            int code = process.waitFor();
            if (code == 0) {
                log.info("xiaoya index file updated");
                settingRepository.save(new Setting(INDEX_VERSION, remote));
            } else {
                log.warn("download xiaoya index file failed: {}", code);
            }
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    public void updateIndexFile() {
        for (Site site : siteService.list()) {
            if (site.isSearchable() && StringUtils.isNotBlank(site.getIndexFile())) {
                try {
                    downloadIndexFile(site, true);
                } catch (Exception e) {
                    log.warn("", e);
                }
            }
        }
    }

    public void updateIndexFile(Integer siteId) throws IOException {
        Site site = siteService.getById(siteId);
        downloadIndexFile(site, true);
    }

    public String downloadIndexFile(Site site) throws IOException {
        return downloadIndexFile(site, false);
    }

    public String downloadIndexFile(Site site, boolean update) throws IOException {
        String url = site.getIndexFile();
        if (!url.startsWith("http")) {
            return url;
        }

        String name = getIndexFileName(url);
        String filename = name;
        if (name.endsWith(".zip")) {
            filename = name.substring(0, name.length() - 4) + ".txt";
        }

        File file = new File(".cache/" + site.getId() + "/" + filename);
        if (!update && file.exists()) {
            return file.getAbsolutePath();
        }

        if (name.endsWith(".zip")) {
            if (unchanged(site, url, name)) {
                return file.getAbsolutePath();
            }

            log.info("download index file from {}", url);
            downloadZipFile(site, url, name);
        } else {
            log.info("download index file from {}", url);
            FileUtils.copyURLToFile(new URL(url), file);
        }

        return file.getAbsolutePath();
    }

    private static boolean unchanged(Site site, String url, String name) {
        String localTime = getLocalTime(site, name.substring(0, name.length() - 4) + ".info");
        String infoUrl = url.substring(0, url.length() - 4) + ".info";
        String remoteTime = getRemoteTime(site, infoUrl);
        return localTime.equals(remoteTime);
    }

    private static String getLocalTime(Site site, String filename) {
        File info = new File(".cache/" + site.getId() + "/" + filename);
        if (info.exists()) {
            try {
                return FileUtils.readFileToString(info, StandardCharsets.UTF_8);
            } catch (Exception e) {
                // ignore
            }
        }
        return Instant.now().toString();
    }

    private static String getRemoteTime(Site site, String url) {
        try {
            File file = Files.createTempFile(String.valueOf(site.getId()), ".info").toFile();
            FileUtils.copyURLToFile(new URL(url), file);
            return FileUtils.readFileToString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // ignore
        }
        return "";
    }

    private static void downloadZipFile(Site site, String url, String name) throws IOException {
        File zipFile = new File(".cache/" + site.getId() + "/" + name);
        FileUtils.copyURLToFile(new URL(url), zipFile);
        unzip(zipFile);
        Files.delete(zipFile.toPath());
    }

    public static void unzip(File file) throws IOException {
        Path destFolderPath = Paths.get(file.getParent());

        try (ZipFile zipFile = new ZipFile(file, ZipFile.OPEN_READ, StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path entryPath = destFolderPath.resolve(entry.getName());
                if (entryPath.normalize().startsWith(destFolderPath.normalize())) {
                    if (entry.isDirectory()) {
                        Files.createDirectories(entryPath);
                    } else {
                        Files.createDirectories(entryPath.getParent());
                        try (InputStream in = zipFile.getInputStream(entry);
                             OutputStream out = Files.newOutputStream(entryPath.toFile().toPath())) {
                            IOUtils.copy(in, out);
                        }
                    }
                }
            }
        }
    }

    private String getIndexFileName(String url) {
        int index = url.lastIndexOf('/');
        String name = "index.txt";
        if (index > -1) {
            name = url.substring(index + 1);
        }
        if (name.isEmpty()) {
            return "index.txt";
        }
        return name;
    }

    @Scheduled(cron = "0 0 10,12,14,16,18-23 * * ?")
    public void autoIndex() {
        if (aListLocalService.getAListStatus() != 2) {
            return;
        }
        String hour = String.valueOf(LocalTime.now().getHour());
        List<IndexTemplate> list = indexTemplateRepository.findByScheduledTrue();
        log.debug("auto index: {}", list.size());
        for (IndexTemplate template : list) {
            if (template.getScheduleTime() != null && template.getScheduleTime().contains(hour)) {
                try {
                    log.info("auto index for template: {}", template.getId());
                    IndexRequest indexRequest = objectMapper.readValue(template.getData(), IndexRequest.class);
                    index(indexRequest);
                } catch (Exception e) {
                    log.error("start index failed: {}", template.getId(), e);
                }
            }
        }
    }

    public IndexResponse index(IndexRequest indexRequest) {
        indexRequest.setPaths(indexRequest.getPaths().stream().filter(StringUtils::isNotBlank).toList());
        if (indexRequest.getPaths().isEmpty()) {
            throw new BadRequestException("路径不能为空");
        }
        cn.har01d.alist_tvbox.entity.Site site = siteService.getById(indexRequest.getSiteId());
        Task task = taskService.addIndexTask(site);

        executor.submit(() -> {
            try {
                index(indexRequest, site, task);
            } catch (Exception e) {
                log.warn("index failed", e);
                taskService.failTask(task.getId(), e.getMessage());
            }
        });

        return new IndexResponse(task.getId());
    }

    @Async
    public void index(IndexRequest indexRequest, cn.har01d.alist_tvbox.entity.Site site, Task task) throws IOException {
        StopWatch stopWatch = new StopWatch("index");
        File dir = new File("/data/index/" + indexRequest.getSiteId());
        Files.createDirectories(dir.toPath());
        File file = new File(dir, indexRequest.getIndexName() + ".txt");
        File info = new File(dir, indexRequest.getIndexName() + ".info");

        indexRequest.setPaths(indexRequest.getPaths().stream().filter(e -> !e.isBlank()).toList());
        List<String> paths = indexRequest.getPaths().stream().map(e -> e.split(":")[0]).collect(Collectors.toList());
        List<String> excludes = paths.stream().filter(e -> e.startsWith("-")).map(e -> e.substring(1)).toList();
        paths.removeAll(excludes);
        if (indexRequest.isIncremental()) {
            removeLines(file, paths);
        }

        String summary;
        try (FileWriter writer = new FileWriter(file, indexRequest.isIncremental());
             FileWriter writer2 = new FileWriter(info)) {
            Instant time = Instant.now();
            taskService.startTask(task.getId());
            String detail = getTaskDetails(paths) + "\n\n索引文件:\n" + file.getAbsolutePath();
            taskService.updateTaskData(task.getId(), detail);
            IndexContext context = new IndexContext(indexRequest, site, writer, task.getId());
            context.getExcludes().addAll(excludes);
            context.getExcludes().addAll(loadExcluded(file));
            int total = 0;
            for (String path : indexRequest.getPaths()) {
                if (isCancelled(context)) {
                    break;
                }
                path = customize(context, indexRequest, path);
                stopWatch.start("index " + path);
                index(context, path, 0);
                stopWatch.stop();
                log.info("{} {}", path, context.stats.indexed - total);
                total = context.stats.indexed;
            }
            writer2.write(time.toString());
            log.info("index stats: {}", context.stats);
            summary = context.stats.toString();
        }

        if (indexRequest.isCompress()) {
            File zipFIle = new File(dir, indexRequest.getIndexName() + ".zip");
            zipFile(file, info, zipFIle);
        }
        taskService.completeTask(task.getId());
        taskService.updateTaskSummary(task.getId(), summary);

        log.info("index done, total time : {} {}", Duration.ofNanos(stopWatch.getTotalTimeNanos()), stopWatch.prettyPrint());
        log.info("index file: {}", file.getAbsolutePath());
    }

    private static String customize(IndexContext context, IndexRequest indexRequest, String path) {
        String[] parts = path.split(":");
        path = parts[0];
        boolean includeFiles = parts.length > 1 && "file".equals(parts[1]);
        context.setIncludeFiles(indexRequest.isIncludeFiles() || includeFiles);
        int maxDepth = parts.length > 2 ? Integer.parseInt(parts[2]) : indexRequest.getMaxDepth();
        context.setMaxDepth(maxDepth);
        return path;
    }

    private String getTaskDetails(List<String> paths) {
        int n = paths.size();
        if (n < 7) {
            return "索引路径:\n" + String.join("\n", paths);
        }
        return "索引路径:\n" + String.join("\n", paths.subList(0, 3)) +
                "\n...\n" + String.join("\n", paths.subList(n - 3, n));
    }

    private boolean isCancelled(IndexContext context) {
        Task task = taskService.getById(context.getTaskId());
        return task.getStatus() == TaskStatus.COMPLETED && task.getResult() == TaskResult.CANCELLED;
    }

    private void removeLines(File file, List<String> prefix) {
        if (!file.exists()) {
            return;
        }

        try {
            List<String> lines = Files.readAllLines(file.toPath())
                    .stream()
                    .filter(path -> prefix.stream().noneMatch(path::startsWith))
                    .toList();

            try (FileWriter writer = new FileWriter(file)) {
                IOUtils.writeLines(lines, null, writer);
            }
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    private List<String> loadExcluded(File file) throws IOException {
        return Files.readAllLines(file.toPath())
                .stream()
                .filter(path -> path.startsWith("-"))
                .map(path -> "^" + path.substring(1) + "$")
                .toList();
    }

    private void zipFile(File file, File info, File output) throws IOException {
        try (ZipOutputStream zipOut = new ZipOutputStream(Files.newOutputStream(output.toPath()))) {
            addZipEntry(zipOut, file);
            addZipEntry(zipOut, info);
        }
    }

    private void addZipEntry(ZipOutputStream zipOut, File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            ZipEntry zipEntry = new ZipEntry(file.getName());
            zipOut.putNextEntry(zipEntry);

            byte[] bytes = new byte[4096];
            int length;
            while ((length = fis.read(bytes)) >= 0) {
                zipOut.write(bytes, 0, length);
            }
        }
    }

    private void index(IndexContext context, String path, int depth) throws IOException {
        log.debug("path: {}  depth: {}  context: {}", path, depth, context);
        if ((context.getMaxDepth() > 0 && depth == context.getMaxDepth()) || isCancelled(context)) {
            log.debug("exit {}", depth);
            return;
        }

        if (!log.isDebugEnabled()) {
            log.info("index {} : {}", context.getSiteName(), path);
        }

        FsResponse fsResponse = aListService.listFiles(context.getSite(), path, 1, 1000);
        if (fsResponse == null) {
            log.warn("response null: {} {}", path, context.stats);
            context.stats.errors++;
            if (depth == 0) {
                Task task = taskService.getById(context.getTaskId());
                String data = task.getData();
                if (!data.contains("失效路径：")) {
                    data += "\n\n失效路径：\n";
                }
                data += path + "\n";
                taskService.updateTaskData(task.getId(), data);
            }
            return;
        }
        if (context.isExcludeExternal() && fsResponse.getProvider().contains("AList")) {
            log.warn("exclude external {}", path);
            return;
        }

        log.debug("{} get {} files", fsResponse.getProvider(), fsResponse.getFiles().size());
        List<String> files = new ArrayList<>();
        boolean hasFile = false;
        for (FsInfo fsInfo : fsResponse.getFiles()) {
            try {
                if (fsInfo.getType() == 1) { // folder
                    if (fsInfo.getName().equals("字幕")) {
                        continue;
                    }
                    String newPath = fixPath(path + "/" + fsInfo.getName());
                    log.debug("new path: {}", newPath);
                    if (exclude(context.getExcludes(), newPath)) {
                        log.warn("exclude folder {}", newPath);
                        context.stats.excluded++;
                        continue;
                    }

                    if (context.getMaxDepth() == depth + 1 && !context.isIncludeFiles()) {
                        files.add(fsInfo.getName());
                    } else {
                        if (context.getIndexRequest().getSleep() > 0) {
                            log.debug("sleep {}", context.getIndexRequest().getSleep());
                            Thread.sleep(context.getIndexRequest().getSleep());
                        }

                        if (isCancelled(context)) {
                            break;
                        }

                        try {
                            index(context, newPath, depth + 1);
                        } catch (Exception e) {
                            context.stats.errors++;
                            log.warn("index failed: {}", newPath, e);
                        }
                    }
                } else if (isMediaFormat(fsInfo.getName())) { // file
                    hasFile = true;
                    if (context.isIncludeFiles()) {
                        String newPath = fixPath(path + "/" + fsInfo.getName());
                        if (exclude(context.getExcludes(), newPath)) {
                            log.warn("exclude file {}", newPath);
                            context.stats.excluded++;
                            continue;
                        }

                        context.stats.files++;
                        log.debug("{}, add file: {}", path, fsInfo.getName());
                        files.add(fsInfo.getName());
                    }
                } else {
                    log.debug("ignore file: {}", fsInfo.getName());
                }
            } catch (Exception e) {
                log.warn("index error", e);
            }
        }

        if (hasFile) {
            context.write(path);
        }

        for (String name : files) {
            String newPath = fixPath(path + "/" + name);
            context.write(newPath);
        }

        taskService.updateTaskSummary(context.getTaskId(), context.stats.toString());
    }

    private boolean exclude(Set<String> rules, String path) {
        for (String rule : rules) {
            if (StringUtils.isBlank(rule)) {
                continue;
            }
            if (rule.startsWith("/") && path.startsWith(rule)) {
                return true;
            }
            if (rule.startsWith("^") && rule.endsWith("$") && path.equals(rule.substring(1, rule.length() - 1))) {
                return true;
            }
            if (rule.startsWith("^") && path.startsWith(rule.substring(1))) {
                return true;
            }
            if (rule.endsWith("$") && path.endsWith(rule.substring(0, rule.length() - 1))) {
                return true;
            }
            if (path.contains(rule)) {
                return true;
            }
        }
        return false;
    }

    private boolean isMediaFormat(String name) {
        int index = name.lastIndexOf('.');
        if (index > 0) {
            String suffix = name.substring(index + 1);
            return appProperties.getFormats().contains(suffix);
        }
        return false;
    }

    private String fixPath(String path) {
        return path.replaceAll("/+", "/").replace("\n", "%20");
    }

    public List<FileItem> listIndexFiles(int id) {
        try {
            Path path = Paths.get("/data/index/" + id);
            return Files.list(path)
                    .filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .sorted()
                    .map(p -> new FileItem(p.getFileName().toString().replace(".txt", ""), p.toString(), 0))
                    .toList();
        } catch (Exception e) {
            log.warn("list index files " + id, e);
        }
        return List.of();
    }

    private String decodeUrl(String url) {
        try {
            return URLDecoder.decode(url, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return url;
        }
    }
}
