package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.TaskResult;
import cn.har01d.alist_tvbox.domain.TaskStatus;
import cn.har01d.alist_tvbox.dto.IndexRequest;
import cn.har01d.alist_tvbox.dto.IndexResponse;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.Task;
import cn.har01d.alist_tvbox.model.FsInfo;
import cn.har01d.alist_tvbox.model.FsResponse;
import cn.har01d.alist_tvbox.tvbox.IndexContext;
import cn.har01d.alist_tvbox.util.Constants;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
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
    private final AppProperties appProperties;
    private final SettingRepository settingRepository;
    private final RestTemplate restTemplate;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public IndexService(AListService aListService,
                        SiteService siteService,
                        TaskService taskService,
                        AppProperties appProperties,
                        SettingRepository settingRepository,
                        RestTemplateBuilder builder) {
        this.aListService = aListService;
        this.siteService = siteService;
        this.taskService = taskService;
        this.appProperties = appProperties;
        this.settingRepository = settingRepository;
        this.restTemplate = builder
                .defaultHeader(HttpHeaders.ACCEPT, Constants.ACCEPT)
                .defaultHeader(HttpHeaders.USER_AGENT, Constants.USER_AGENT1)
                .build();
        updateIndexFile();
    }

    @PostConstruct
    public void setup() {
        try {
            Path path = Paths.get("/version.txt");
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

    public String getRemoteVersion() {
        try {
            String remote = restTemplate.getForObject("http://docker.xiaoya.pro/update/version.txt", String.class).trim();
            String local = settingRepository.findById(INDEX_VERSION).map(Setting::getValue).orElse("").trim();
            if (!local.equals(remote)) {
                executor.execute(() -> updateXiaoyaIndexFile(remote));
            }
            return remote;
        } catch (Exception e) {
            log.warn("", e);
        }
        return "";
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

    public IndexResponse index(IndexRequest indexRequest) {
        cn.har01d.alist_tvbox.entity.Site site = siteService.getById(indexRequest.getSiteId());
        Task task = taskService.addIndexTask(site);

        executor.submit(() -> {
            try {
                index(indexRequest, site, task);
            } catch (Exception e) {
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

        if (indexRequest.isIncremental()) {
            removeLines(file, indexRequest.getPaths());
        }

        String summary;
        try (FileWriter writer = new FileWriter(file, indexRequest.isIncremental());
             FileWriter writer2 = new FileWriter(info)) {
            Instant time = Instant.now();
            taskService.startTask(task.getId());
            taskService.updateTaskData(task.getId(), file.getAbsolutePath());
            IndexContext context = new IndexContext(indexRequest, site, writer, task.getId());
            context.setSleep(indexRequest.getSleep());
            for (String path : indexRequest.getPaths()) {
                if (isCancelled(context)) {
                    break;
                }
                if (StringUtils.isBlank(path)) {
                    continue;
                }
                stopWatch.start("index " + path);
                index(context, path, 0);
                stopWatch.stop();
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

    private boolean isCancelled(IndexContext context) {
        Task task = taskService.getById(context.getTaskId());
        return task.getStatus() == TaskStatus.COMPLETED && task.getResult() == TaskResult.CANCELLED;
    }

    private void removeLines(File file, Set<String> prefix) {
        try {
            List<String> lines = Files.readAllLines(file.toPath())
                    .stream()
                    .filter(path -> prefix.stream().noneMatch(path::startsWith))
                    .collect(Collectors.toList());

            try (FileWriter writer = new FileWriter(file)) {
                IOUtils.writeLines(lines, null, writer);
            }
        } catch (Exception e) {
            log.warn("", e);
        }
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
            log.debug("exit");
            return;
        }

        if (!log.isDebugEnabled()) {
            log.info("index {} : {}", context.getSiteName(), path);
        }

        FsResponse fsResponse = aListService.listFiles(context.getSite(), path, 1, 1000);
        if (fsResponse == null) {
            log.debug("response null: {} {}", path, context.stats);
            context.stats.errors++;
            return;
        }
        if (context.isExcludeExternal() && fsResponse.getProvider().contains("AList")) {
            log.warn("exclude external {}", path);
            return;
        }

        log.debug("{} get {} files", fsResponse.getProvider(), fsResponse.getFiles().size());
        List<String> files = new ArrayList<>();
        for (FsInfo fsInfo : fsResponse.getFiles()) {
            try {
                if (fsInfo.getType() == 1) { // folder
                    String newPath = fixPath(path + "/" + fsInfo.getName());
                    log.debug("new path: {}", newPath);
                    if (exclude(context.getExcludes(), newPath)) {
                        log.warn("exclude folder {}", newPath);
                        context.stats.excluded++;
                        continue;
                    }

                    if (context.getSleep() > 0) {
                        log.debug("sleep {}", context.getSleep());
                        Thread.sleep(context.getSleep());
                    }

                    index(context, newPath, depth + 1);
                } else if (isMediaFormat(fsInfo.getName())) { // file
                    String newPath = fixPath(path + "/" + fsInfo.getName());
                    if (exclude(context.getExcludes(), newPath)) {
                        log.warn("exclude file {}", newPath);
                        context.stats.excluded++;
                        continue;
                    }

                    context.stats.files++;
                    log.debug("{}, add file: {}", path, fsInfo.getName());
                    files.add(fsInfo.getName());
                } else {
                    log.debug("ignore file: {}", fsInfo.getName());
                }
            } catch (Exception e) {
                log.warn("index error", e);
            }
        }

        if (!files.isEmpty() && !context.contains(path)) {
            context.write(path);
        }

        for (String name : files) {
            String newPath = fixPath(path + "/" + name);
            if (context.contains(newPath)) {
                continue;
            }
            context.write(newPath);
        }

        taskService.updateTaskSummary(context.getTaskId(), context.stats.toString());
    }

    private boolean exclude(Set<String> rules, String path) {
        for (String rule : rules) {
            if (StringUtils.isBlank(rule)) {
                continue;
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

}
