package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.util.Constants;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static cn.har01d.alist_tvbox.util.Constants.APP_VERSION;
import static cn.har01d.alist_tvbox.util.Constants.DOCKER_VERSION;
import static cn.har01d.alist_tvbox.util.Constants.INDEX_VERSION;

@Slf4j
@Service
public class IndexService {
    private final SiteService siteService;
    private final SettingRepository settingRepository;
    private final RestTemplate restTemplate;

    public IndexService(SiteService siteService, SettingRepository settingRepository, RestTemplateBuilder builder) {
        this.siteService = siteService;
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
            return restTemplate.getForObject("http://docker.xiaoya.pro/update/version.txt", String.class);
        } catch (Exception e) {
            log.warn("", e);
        }
        return "";
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

}
