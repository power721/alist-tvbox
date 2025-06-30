package cn.har01d.alist_tvbox.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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

    private final ExecutorService executor = new ThreadPoolExecutor(
            1, 1,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            r -> {
                Thread t = new Thread(r, "file-downloader");
                t.setDaemon(true);
                return t;
            }
    );

    public void runTask(String type) {
        if ("pg".equals(type)) {
            executor.submit(this::downloadPgWithRetry);
        }
    }

    private void downloadPgWithRetry() {
        int retry = 3;
        while (retry-- > 0) {
            try {
                downloadPg();
                return;
            } catch (Exception e) {
                log.error("Download PG failed, retries left: {}", retry, e);
                if (retry > 0) {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
        log.error("Download PG failed after 3 retries");
    }

    public void downloadPg() throws IOException {
        String localVersion = getLocalVersion();
        String remoteVersion = getRemoteVersion();

        log.info("Local PG version: {}, remote PG version: {}", localVersion, remoteVersion);

        if (localVersion.equals(remoteVersion)) {
            log.info("Versions match, syncing files only");
            syncFiles();
        } else {
            log.info("New version available, downloading {}", remoteVersion);
            downloadNewVersionWithProgress();

            log.info("Unzipping file");
            unzipFile();

            log.info("Saving new version info");
            saveVersion(remoteVersion);

            log.info("Syncing additional files");
            syncFiles();

            log.info("PG update completed successfully");
        }
    }

    private String getLocalVersion() throws IOException {
        Path versionFile = Paths.get(VERSION_FILE);
        if (Files.exists(versionFile)) {
            return Files.readAllLines(versionFile).get(0).trim();
        } else {
            Path sourceZip = Paths.get("/pg.zip");
            if (Files.exists(sourceZip)) {
                Files.copy(sourceZip, Paths.get(PG_ZIP), StandardCopyOption.REPLACE_EXISTING);
            }
            return "0.0";
        }
    }

    private String getRemoteVersion() throws IOException {
        URL url = new URL(REMOTE_VERSION_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(conn.getInputStream()))) {
            return in.readLine().trim();
        } finally {
            conn.disconnect();
        }
    }

    private void downloadNewVersionWithProgress() throws IOException {
        URL url = new URL(REMOTE_ZIP_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);

        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             FileOutputStream out = new FileOutputStream(PG_ZIP)) {

            long fileSize = conn.getContentLengthLong();
            byte[] buffer = new byte[8192];
            long downloaded = 0;
            int bytesRead;

            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                downloaded += bytesRead;

                // 每下载1MB或完成时打印进度
                if (fileSize > 0 && (downloaded % (1024 * 1024) == 0 || downloaded == fileSize)) {
                    int progress = (int) (downloaded * 100 / fileSize);
                    log.info("Download progress: {}% ({} bytes/{} bytes)",
                            progress, downloaded, fileSize);
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private void unzipFile() throws IOException {
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
                    // 确保父目录存在
                    File parent = entryDestination.getParentFile();
                    if (parent != null) {
                        parent.mkdirs();
                    }

                    try (InputStream in = zipFile.getInputStream(entry);
                         OutputStream out = new BufferedOutputStream(
                                 new FileOutputStream(entryDestination))) {

                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = in.read(buffer)) > 0) {
                            out.write(buffer, 0, len);
                        }
                    }
                }
            }
        }
    }

    private void saveVersion(String version) throws IOException {
        Files.write(Paths.get(VERSION_FILE), version.getBytes(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void syncFiles() throws IOException {
        Path sourceDir = Paths.get(DATA_PG_DIR);
        if (Files.exists(sourceDir)) {
            copyDirectory(sourceDir, Paths.get(PG_DIR));
        }
    }

    private void deleteDirectory(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            log.warn("Failed to delete file: {}", p, e);
                        }
                    });
        }
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        Files.walk(source)
                .forEach(sourcePath -> {
                    try {
                        Path targetPath = target.resolve(source.relativize(sourcePath));
                        if (Files.isDirectory(sourcePath)) {
                            Files.createDirectories(targetPath);
                        } else {
                            Files.copy(sourcePath, targetPath,
                                    StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        log.warn("Failed to copy file: {}", sourcePath, e);
                    }
                });
    }
}
