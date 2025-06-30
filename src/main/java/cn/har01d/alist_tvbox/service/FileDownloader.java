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
    // 版本文件路径
    private static final String PG_VERSION_FILE = "/data/pg_version.txt";
    private static final String ZX_BASE_VERSION_FILE = "/data/zx_base_version.txt";
    private static final String ZX_VERSION_FILE = "/data/zx_version.txt";

    // 压缩文件路径
    private static final String PG_ZIP = "/data/pg.zip";
    private static final String ZX_BASE_ZIP = "/data/zx.base.zip";
    private static final String ZX_ZIP = "/data/zx.zip";

    // 目标目录
    private static final String PG_DIR = "/www/pg/";
    private static final String ZX_DIR = "/www/zx/";

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
        } else if ("zx".equals(type)) {
            executor.submit(this::downloadZxWithRetry);
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

    private void downloadZxWithRetry() {
        int retry = 3;
        while (retry-- > 0) {
            try {
                downloadZx();
                return;
            } catch (Exception e) {
                log.error("Download ZX failed, retries left: {}", retry, e);
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
        log.error("Download ZX failed after 3 retries");
    }

    public void downloadPg() throws IOException {
        String localVersion = getLocalVersion(PG_VERSION_FILE, "0.0");
        String remoteVersion = getRemoteVersion(REMOTE_PG_VERSION_URL);

        log.info("local PG: {}, remote PG: {}", localVersion, remoteVersion);

        if (localVersion.equals(remoteVersion)) {
            log.info("sync files");
            syncFiles(PG_DIR, DATA_PG_DIR);
        } else {
            log.info("download {}", remoteVersion);
            downloadFile(REMOTE_PG_ZIP_URL, PG_ZIP);

            log.info("unzip file");
            unzipFile(PG_ZIP, PG_DIR);

            log.info("save version");
            saveVersion(PG_VERSION_FILE, remoteVersion);

            log.info("sync files");
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
        } finally {
            conn.disconnect();
        }
    }

    private void unzipFile(String zipFile, String destDir) throws IOException {
        deleteDirectory(Paths.get(destDir));
        Files.createDirectories(Paths.get(destDir));

        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();

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
}
