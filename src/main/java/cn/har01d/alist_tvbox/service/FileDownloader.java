package cn.har01d.alist_tvbox.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Slf4j
@Service
public class FileDownloader {
    private static final String VERSION_FILE = "/data/pg_version.txt";
    private static final String PG_ZIP = "/data/pg.zip";
    private static final String PG_DIR = "/www/pg/";
    private static final String DATA_PG_DIR = "/data/pg/";
    private static final String REMOTE_VERSION_URL = "http://har01d.org/pg.version";
    private static final String REMOTE_ZIP_URL = "http://har01d.org/pg.zip";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public void runTask(String type) {
        if ("pg".equals(type)) {
            executor.submit(() -> {
                try {
                    downloadPg();
                } catch (Exception e) {
                    log.error("Download PG failed.", e);
                }
            });
        }
    }

    public void downloadPg() throws IOException {
        String localVersion = getLocalVersion();

        String remoteVersion = getRemoteVersion();

        log.info("local PG: {}, remote PG: {}", localVersion, remoteVersion);

        if (localVersion.equals(remoteVersion)) {
            log.info("sync files");
            syncFiles();
        } else {
            log.info("download {}", remoteVersion);
            downloadNewVersion();

            log.info("unzip file");
            unzipFile();

            log.info("save version");
            saveVersion(remoteVersion);

            log.info("sync files");
            syncFiles();
        }
    }

    private static String getLocalVersion() throws IOException {
        if (Files.exists(Paths.get(VERSION_FILE))) {
            return Files.readAllLines(Paths.get(VERSION_FILE)).get(0);
        } else {
            if (Files.exists(Paths.get("/pg.zip"))) {
                Files.copy(Paths.get("/pg.zip"), Paths.get(PG_ZIP), StandardCopyOption.REPLACE_EXISTING);
            }
            return "0.0";
        }
    }

    private static String getRemoteVersion() throws IOException {
        URL url = new URL(REMOTE_VERSION_URL);
        try (BufferedReader in = new BufferedReader(new InputStreamReader(url.openStream()))) {
            return in.readLine();
        }
    }

    private static void downloadNewVersion() throws IOException {
        URL url = new URL(REMOTE_ZIP_URL);
        try (InputStream in = url.openStream();
             FileOutputStream out = new FileOutputStream(PG_ZIP)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
    }

    private static void unzipFile() throws IOException {
        deleteDirectory(Paths.get(PG_DIR));
        Files.createDirectories(Paths.get(PG_DIR));

        try (ZipFile zipFile = new ZipFile(PG_ZIP)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                File entryDestination = new File(PG_DIR, entry.getName());
                if (entry.isDirectory()) {
                    entryDestination.mkdirs();
                } else {
                    entryDestination.getParentFile().mkdirs();
                    try (InputStream in = zipFile.getInputStream(entry);
                         OutputStream out = new FileOutputStream(entryDestination)) {
                        byte[] buffer = new byte[1024];
                        int len;
                        while ((len = in.read(buffer)) > 0) {
                            out.write(buffer, 0, len);
                        }
                    }
                }
            }
        }
    }

    private static void saveVersion(String version) throws IOException {
        Files.write(Paths.get(VERSION_FILE), version.getBytes());
    }

    private static void syncFiles() throws IOException {
        if (Files.exists(Paths.get(DATA_PG_DIR))) {
            copyDirectory(Paths.get(DATA_PG_DIR), Paths.get(PG_DIR));
        }
    }

    private static void deleteDirectory(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            log.warn("delete file error: {}", p, e);
                        }
                    });
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.walk(source)
                .forEach(sourcePath -> {
                    try {
                        Path targetPath = target.resolve(source.relativize(sourcePath));
                        if (Files.isDirectory(sourcePath)) {
                            Files.createDirectories(targetPath);
                        } else {
                            Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        log.warn("copy file error: {}", source, e);
                    }
                });
    }
}
