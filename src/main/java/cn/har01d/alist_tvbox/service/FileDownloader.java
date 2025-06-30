package cn.har01d.alist_tvbox.service;

import jakarta.annotation.PreDestroy;
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
    // 版本文件路径
    private static final String PG_VERSION_FILE = "/data/pg_version.txt";
    private static final String ZX_BASE_VERSION_FILE = "/data/zx_base_version.txt";
    private static final String ZX_VERSION_FILE = "/data/zx_version.txt";
    private static final String MOVIE_VERSION_FILE = "/data/atv/movie_version";

    // 压缩文件路径
    private static final String PG_ZIP = "/data/pg.zip";
    private static final String ZX_BASE_ZIP = "/data/zx.base.zip";
    private static final String ZX_ZIP = "/data/zx.zip";
    private static final String DIFF_ZIP = "/tmp/diff.zip";

    // 目标目录
    private static final String PG_DIR = "/www/pg/";
    private static final String ZX_DIR = "/www/zx/";
    private static final String ATV_DIR = "/data/atv/";

    // 数据目录
    private static final String DATA_PG_DIR = "/data/pg/";
    private static final String DATA_ZX_DIR = "/data/zx/";

    // 远程URL
    private static final String REMOTE_PG_VERSION_URL = "http://har01d.org/pg.version";
    private static final String REMOTE_PG_ZIP_URL = "http://har01d.org/pg.zip";
    private static final String REMOTE_ZX_BASE_VERSION_URL = "http://har01d.org/zx.base.version";
    private static final String REMOTE_ZX_BASE_ZIP_URL = "http://har01d.org/zx.base.zip";
    private static final String REMOTE_ZX_VERSION_URL = "http://har01d.org/zx.version";
    private static final String REMOTE_ZX_ZIP_URL = "http://har01d.org/zx.zip";
    private static final String REMOTE_DIFF_ZIP_URL = "http://har01d.org/diff.zip";

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

    public void runTask(String type, String... args) {
        if ("pg".equals(type)) {
            executor.submit(this::downloadPgWithRetry);
        } else if ("zx".equals(type)) {
            executor.submit(this::downloadZxWithRetry);
        } else if ("movie".equals(type)) {
            String remoteVersion = args.length > 0 ? args[0] : null;
            executor.submit(() -> downloadMovieWithRetry(remoteVersion));
        }
    }

    private void downloadPgWithRetry() {
        executeWithRetry(() -> {
            try {
                downloadPg();
            } catch (IOException e) {
                log.error("PG task failed", e);
            }
        }, "PG");
    }

    private void downloadZxWithRetry() {
        executeWithRetry(() -> {
            try {
                downloadZx();
            } catch (IOException e) {
                log.error("ZX task failed", e);
            }
        }, "ZX");
    }

    private void downloadMovieWithRetry(String remoteVersion) {
        executeWithRetry(() -> {
            try {
                downloadMovie(remoteVersion);
            } catch (IOException e) {
                log.error("Movie task failed", e);
            }
        }, "Movie");
    }

    private void executeWithRetry(Runnable task, String taskName) {
        int retry = 3;
        while (retry-- > 0) {
            try {
                task.run();
                return;
            } catch (Exception e) {
                log.error("Download {} failed, retries left: {}", taskName, retry, e);
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
        log.error("Download {} failed after 3 retries", taskName);
    }

    public void downloadPg() throws IOException {
        String localVersion = getLocalVersion(PG_VERSION_FILE, "0.0");
        String remoteVersion = getRemoteVersion(REMOTE_PG_VERSION_URL);

        log.info("local PG: {}, remote PG: {}", localVersion, remoteVersion);

        if (localVersion.equals(remoteVersion)) {
            log.info("sync PG files");
            syncFiles(PG_DIR, DATA_PG_DIR);
        } else {
            log.info("download PG {}", remoteVersion);
            downloadFile(REMOTE_PG_ZIP_URL, PG_ZIP);

            log.info("unzip PG file");
            unzipFile(PG_ZIP, PG_DIR);

            log.info("save PG version");
            saveVersion(PG_VERSION_FILE, remoteVersion);

            log.info("sync PG files");
            syncFiles(PG_DIR, DATA_PG_DIR);

            log.info("PG update completed successfully");
        }
    }

    public void downloadZx() throws IOException {
        // 处理zx.base部分
        String localBaseVersion = getLocalVersion(ZX_BASE_VERSION_FILE, "0.0");
        String remoteBaseVersion = getRemoteVersion(REMOTE_ZX_BASE_VERSION_URL);

        log.info("local zx base: {}, remote zx base: {}", localBaseVersion, remoteBaseVersion);

        if (!localBaseVersion.equals(remoteBaseVersion)) {
            log.info("download zx base {}", remoteBaseVersion);
            downloadFile(REMOTE_ZX_BASE_ZIP_URL, ZX_BASE_ZIP);

            log.info("save zx base version");
            saveVersion(ZX_BASE_VERSION_FILE, remoteBaseVersion);
        }

        // 处理zx部分
        String localVersion = getLocalVersion(ZX_VERSION_FILE, "0.0");
        if ("0.0".equals(localVersion) && Files.exists(Paths.get("/zx.zip"))) {
            Files.copy(Paths.get("/zx.zip"), Paths.get(ZX_ZIP), StandardCopyOption.REPLACE_EXISTING);
        }

        String remoteVersion = getRemoteVersion(REMOTE_ZX_VERSION_URL);

        log.info("local zx diff: {}, remote zx diff: {}", localVersion, remoteVersion);

        if (!localVersion.equals(remoteVersion)) {
            log.info("download zx diff {}", remoteVersion);
            downloadFile(REMOTE_ZX_ZIP_URL, ZX_ZIP);

            log.info("save zx diff version");
            saveVersion(ZX_VERSION_FILE, remoteVersion);
        }

        // 列出文件信息
        logFileInfo(ZX_BASE_ZIP);
        logFileInfo(ZX_ZIP);

        // 同步文件
        log.info("sync zx files");
        deleteDirectory(Paths.get(ZX_DIR));

        log.info("unzip zx.base.zip");
        unzipFile(ZX_BASE_ZIP, ZX_DIR);

        log.info("unzip zx.zip");
        unzipFile(ZX_ZIP, ZX_DIR);

        log.info("sync custom files");
        syncFiles(ZX_DIR, DATA_ZX_DIR);

        log.info("update zx completed");
    }

    public void downloadMovie(String remoteVersion) throws IOException {
        if (remoteVersion == null || remoteVersion.isEmpty()) {
            throw new IllegalArgumentException("Remote version is required for movie data update");
        }

        String localVersion = getLocalVersion(MOVIE_VERSION_FILE, "0.0");
        log.info("local movie data version: {}, remote version: {}", localVersion, remoteVersion);

        if (localVersion.equals(remoteVersion)) {
            log.info("Movie data is up to date");
            return;
        }

        log.info("download diff.zip");
        downloadFile(REMOTE_DIFF_ZIP_URL, DIFF_ZIP);

        log.info("unzip diff.zip");
        unzipFile(DIFF_ZIP, ATV_DIR);

        // 读取并记录新版本
        String newVersion = getLocalVersion(MOVIE_VERSION_FILE, remoteVersion);
        log.info("Current movie version: {}", newVersion);

        // 清理临时文件
        Files.deleteIfExists(Paths.get(DIFF_ZIP));
        log.info("Movie data update completed");
    }

    private String getLocalVersion(String versionFile, String defaultValue) throws IOException {
        Path path = Paths.get(versionFile);
        if (Files.exists(path)) {
            return Files.readAllLines(path).get(0).trim();
        }
        return defaultValue;
    }

    private String getRemoteVersion(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(conn.getInputStream()))) {
            return in.readLine().trim();
        } finally {
            conn.disconnect();
        }
    }

    private void downloadFile(String fileUrl, String destination) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(fileUrl).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);

        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             FileOutputStream out = new FileOutputStream(destination)) {

            long fileSize = conn.getContentLengthLong();
            byte[] buffer = new byte[8192];
            long downloaded = 0;
            int bytesRead;

            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                downloaded += bytesRead;

                if (fileSize > 0 && (downloaded % (1024 * 1024) == 0 || downloaded == fileSize)) {
                    int progress = (int) (downloaded * 100 / fileSize);
                    log.info("Download progress: {}% ({} bytes/{} bytes)",
                            progress, downloaded, fileSize);
                }
            }

            // 下载完成后校验文件大小
            if (fileSize > 0 && Files.size(Paths.get(destination)) != fileSize) {
                throw new IOException("Downloaded file size does not match expected size");
            }
        } finally {
            conn.disconnect();
        }
    }

    private void unzipFile(String zipFile, String destDir) throws IOException {
        Path zipPath = Paths.get(zipFile);

        // 验证ZIP文件是否存在且可读
        if (!Files.exists(zipPath) || !Files.isReadable(zipPath)) {
            throw new IOException("ZIP file does not exist or is not readable: " + zipFile);
        }

        // 验证ZIP文件大小
        long fileSize = Files.size(zipPath);
        if (fileSize < 22) { // ZIP文件最小长度
            throw new IOException("ZIP file is too small to be valid: " + zipFile);
        }

        Files.createDirectories(Paths.get(destDir));

        try (ZipFile zip = new ZipFile(zipFile)) {
            // 验证ZIP文件是否可以读取条目
            Enumeration<? extends ZipEntry> entries = zip.entries();
            if (!entries.hasMoreElements()) {
                throw new IOException("ZIP file contains no entries: " + zipFile);
            }

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                File entryDestination = new File(destDir, entry.getName());

                if (entry.isDirectory()) {
                    entryDestination.mkdirs();
                } else {
                    File parent = entryDestination.getParentFile();
                    if (parent != null) {
                        parent.mkdirs();
                    }

                    try (InputStream in = zip.getInputStream(entry);
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
        } catch (IOException e) {
            // 删除可能已解压的部分文件
            deleteDirectory(Paths.get(destDir));
            throw new IOException("Failed to unzip file: " + zipFile, e);
        }
    }

    private void saveVersion(String versionFile, String version) throws IOException {
        Files.write(Paths.get(versionFile), version.getBytes(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void syncFiles(String destDir, String sourceDir) throws IOException {
        Path sourcePath = Paths.get(sourceDir);
        if (Files.exists(sourcePath)) {
            copyDirectory(sourcePath, Paths.get(destDir));
        }
    }

    private void logFileInfo(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        if (Files.exists(path)) {
            log.info("File info: {} - Size: {} bytes",
                    path, Files.size(path));
        } else {
            log.warn("File not found: {}", path);
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

    @PreDestroy
    public void cleanup() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
