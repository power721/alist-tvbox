package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.TaskResult;
import cn.har01d.alist_tvbox.domain.TaskStatus;
import cn.har01d.alist_tvbox.dto.IndexRequest;
import cn.har01d.alist_tvbox.dto.IndexResponse;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.Task;
import cn.har01d.alist_tvbox.model.FsInfo;
import cn.har01d.alist_tvbox.model.FsResponse;
import cn.har01d.alist_tvbox.tvbox.IndexContext;
import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.seg.common.Term;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.similarity.CosineSimilarity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
public class IndexService {
    private final AListService aListService;
    private final SiteService siteService;
    private final TaskService taskService;
    private final AppProperties appProperties;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    public IndexService(AListService aListService, SiteService siteService, TaskService taskService, AppProperties appProperties) {
        this.aListService = aListService;
        this.siteService = siteService;
        this.taskService = taskService;
        this.appProperties = appProperties;
        updateIndexFile();
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

    public IndexResponse index(IndexRequest indexRequest) throws IOException {
        cn.har01d.alist_tvbox.entity.Site site = siteService.getById(indexRequest.getSiteId());
        Task task = taskService.addIndexTask(site);

        executorService.submit(() -> {
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
        File dir = new File("data/index/" + indexRequest.getSiteId());
        Files.createDirectories(dir.toPath());
        File file = new File(dir, indexRequest.getIndexName() + ".txt");
        File info = new File(dir, indexRequest.getIndexName() + ".info");

        String summary;
        try (FileWriter writer = new FileWriter(file);
             FileWriter writer2 = new FileWriter(info)) {
            Instant time = Instant.now();
            taskService.startTask(task.getId());
            taskService.updateTaskData(task.getId(), file.getAbsolutePath());
            IndexContext context = new IndexContext(indexRequest, site, writer, task.getId());
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
        if ((context.getMaxDepth() > 0 && depth == context.getMaxDepth()) || isCancelled(context)) {
            return;
        }

        if (!log.isDebugEnabled()) {
            log.info("index {} : {}", context.getSiteName(), path);
        }

        FsResponse fsResponse = aListService.listFiles(context.getSite(), path, 1, 0);
        if (fsResponse == null) {
            context.stats.errors++;
            return;
        }
        if (context.isExcludeExternal() && fsResponse.getProvider().contains("AList")) {
            return;
        }

        List<String> files = new ArrayList<>();
        for (FsInfo fsInfo : fsResponse.getFiles()) {
            try {
                if (fsInfo.getType() == 1) { // folder
                    String newPath = fixPath(path + "/" + fsInfo.getName());
                    if (exclude(context.getExcludes(), newPath)) {
                        log.warn("exclude folder {}", newPath);
                        context.stats.excluded++;
                        continue;
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
                    files.add(fsInfo.getName());
                }
            } catch (Exception e) {
                log.warn("index error", e);
            }
        }

        if (files.size() > 0 && !context.contains(path)) {
            context.write(path);
        }

        if (isSimilar(path, files, context.getStopWords())) {
            return;
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
        return path.replaceAll("/+", "/").replaceAll("\n", "%20");
    }

    private String getFolderName(String path) {
        int index = path.lastIndexOf('/');
        if (index > 0) {
            return path.substring(index + 1);
        }
        return path;
    }

    public boolean isSimilar(String path, List<String> sentences, Set<String> stopWords) {
        if (sentences.isEmpty()) {
            return true;
        }
        if (sentences.size() == 1) {
            String folderName = getFolderName(path);
            List<String> list = new ArrayList<>(sentences);
            list.add(folderName);
            return isSimilar(path, list, stopWords);
        }

        double sum = 0.0;
        CosineSimilarity cosineSimilarity = new CosineSimilarity();
        Map<CharSequence, Integer> leftVector = getVector(stopWords, sentences.get(0));
        for (int i = 1; i < sentences.size(); ++i) {
            Map<CharSequence, Integer> rightVector = getVector(stopWords, sentences.get(i));
            sum += cosineSimilarity.cosineSimilarity(leftVector, rightVector);
            leftVector = rightVector;
        }
        double result = sum / (sentences.size() - 1);

        log.debug("cosineSimilarity {} : {}", path, result);
        return result > 0.9;
    }

    private Map<CharSequence, Integer> getVector(Set<String> stopWords, String text) {
        Map<CharSequence, Integer> result = new HashMap<>();
        for (String stopWord : stopWords) {
            text = text.replaceAll(stopWord, "");
        }
        text = text.replaceAll("\\d+", " ").replaceAll("\\s+", " ");
        List<Term> termList = HanLP.segment(text);
        for (Term term : termList) {
            int frequency = term.getFrequency();
            if (frequency == 0) {
                frequency = 1;
            }
            if (result.containsKey(term.word)) {
                result.put(term.word, result.get(term.word) + frequency);
            } else {
                result.put(term.word, frequency);
            }
        }
        return result;
    }
}
