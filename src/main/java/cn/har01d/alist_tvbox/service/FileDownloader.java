package cn.har01d.alist_tvbox.service;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.springframework.stereotype.Service;

import cn.har01d.alist_tvbox.util.Utils;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class FileDownloader {
    private static final String BASE_URL = "http://har01d.org/";
    private static final String REMOTE_PG_VERSION_URL = BASE_URL + "pg.version";
    private static final String REMOTE_PG_ZIP_URL = BASE_URL + "pg.zip";
    private static final String REMOTE_ZX_BASE_VERSION_URL = BASE_URL + "zx.base.version";
    private static final String REMOTE_ZX_BASE_ZIP_URL = BASE_URL + "zx.base.zip";
    private static final String REMOTE_ZX_VERSION_URL = BASE_URL + "zx.version";
    private static final String REMOTE_ZX_ZIP_URL = BASE_URL + "zx.zip";
    private static final String REMOTE_DIFF_ZIP_URL = BASE_URL + "diff.zip";

    private final Path pgVersionFile;
    private final Path zxBaseVersionFile;
    private final Path zxVersionFile;
    private final Path movieVersionFile;

    private final Path pgZip;
    private final Path zxBaseZip;
    private final Path zxZip;
    private final Path diffZip;

    private final Path pgWebDir;
    private final Path zxWebDir;

    private final Path atvDataDir;
    private final Path pgDataDir;
    private final Path zxDataDir;

    public FileDownloader() {
        pgVersionFile = Utils.getDataPath("pg_version.txt");
        zxBaseVersionFile = Utils.getDataPath("zx_base_version.txt");
        zxVersionFile = Utils.getDataPath("zx_version.txt");
        movieVersionFile = Utils.getDataPath("atv", "movie_version");
        pgZip = Utils.getDataPath("pg.zip");
        zxBaseZip = Utils.getDataPath("zx.base.zip");
        zxZip = Utils.getDataPath("zx.zip");
        diffZip = Utils.getDataPath("diff.zip");
        pgWebDir = Utils.getWebPath("pg");
        zxWebDir = Utils.getWebPath("zx");
        atvDataDir = Utils.getDataPath("atv");
        pgDataDir = Utils.getDataPath("pg");
        zxDataDir = Utils.getDataPath("zx");
    }

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
        String localVersion = getLocalVersion(pgVersionFile, "0.0");
        String remoteVersion = getRemoteVersion(REMOTE_PG_VERSION_URL);

        log.info("local PG: {}, remote PG: {}", localVersion, remoteVersion);

        if (localVersion.equals(remoteVersion)) {
            log.info("sync PG files");
            syncFiles(pgWebDir, pgDataDir);
        } else {
            log.info("download PG {}", remoteVersion);
            downloadFile(REMOTE_PG_ZIP_URL, pgZip);

            log.info("unzip PG file");
            unzipFile(pgZip, pgWebDir);

            log.info("save PG version");
            saveVersion(pgVersionFile, remoteVersion);

            log.info("sync PG files");
            syncFiles(pgWebDir, pgDataDir);

            log.info("PG update completed successfully");
        }
    }

    public void downloadZx() throws IOException {
        String localBaseVersion = getLocalVersion(zxBaseVersionFile, "0.0");
        String remoteBaseVersion = getRemoteVersion(REMOTE_ZX_BASE_VERSION_URL);

        log.info("local zx base: {}, remote zx base: {}", localBaseVersion, remoteBaseVersion);

        if (!localBaseVersion.equals(remoteBaseVersion)) {
            log.info("download zx base {}", remoteBaseVersion);
            downloadFile(REMOTE_ZX_BASE_ZIP_URL, zxBaseZip);

            log.info("save zx base version");
            saveVersion(zxBaseVersionFile, remoteBaseVersion);
        }

        String localVersion = getLocalVersion(zxVersionFile, "0.0");
        if ("0.0".equals(localVersion) && Files.exists(Paths.get("/zx.zip"))) {
            Files.copy(Paths.get("/zx.zip"), zxZip, StandardCopyOption.REPLACE_EXISTING);
        }

        String remoteVersion = getRemoteVersion(REMOTE_ZX_VERSION_URL);

        log.info("local zx diff: {}, remote zx diff: {}", localVersion, remoteVersion);

        if (!localVersion.equals(remoteVersion)) {
            log.info("download zx diff {}", remoteVersion);
            downloadFile(REMOTE_ZX_ZIP_URL, zxZip);

            log.info("save zx diff version");
            saveVersion(zxVersionFile, remoteVersion);
        }

        logFileInfo(zxBaseZip);
        logFileInfo(zxZip);

        log.info("sync zx files");
        deleteDirectory(zxWebDir);

        log.info("unzip zx.base.zip");
        unzipFile(zxBaseZip, zxWebDir);

        log.info("unzip zx.zip");
        unzipFile(zxZip, zxWebDir);

        log.info("sync custom files");
        syncFiles(zxWebDir, zxDataDir);

        log.info("update zx completed");
    }

    public void downloadMovie(String remoteVersion) throws IOException {
        if (remoteVersion == null || remoteVersion.isEmpty()) {
            throw new IllegalArgumentException("Remote version is required for movie data update");
        }

        String localVersion = getLocalVersion(movieVersionFile, "0.0");
        log.info("local movie data version: {}, remote version: {}", localVersion, remoteVersion);

        if (localVersion.equals(remoteVersion)) {
            log.info("Movie data is up to date");
            return;
        }

        log.info("download diff.zip");
        downloadFile(REMOTE_DIFF_ZIP_URL, diffZip);

        log.info("unzip diff.zip");
        unzipFile(diffZip, atvDataDir);

        String newVersion = getLocalVersion(movieVersionFile, remoteVersion);
        log.info("Current movie version: {}", newVersion);

        Files.deleteIfExists(diffZip);
        log.info("Movie data update completed");
    }

    private String getLocalVersion(Path path, String defaultValue) throws IOException {
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

    private void downloadFile(String fileUrl, Path destination) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(fileUrl).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);

        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             FileOutputStream out = new FileOutputStream(destination.toFile())) {

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

            if (fileSize > 0 && Files.size(destination) != fileSize) {
                throw new IOException("Downloaded file size does not match expected size");
            }
        } finally {
            conn.disconnect();
        }
    }

    public void unzipFile(Path zipFile, Path destDir) throws IOException {
        try {
            unzipWithApacheCommons(zipFile, destDir);
            return;
        } catch (Exception e) {
            log.warn("Apache Commons Compress failed: {}", e.getMessage());
        }

        try {
            unzipWithJava(zipFile, destDir);
            return;
        } catch (Exception e) {
            log.warn("Java zip failed: {}", e.getMessage());
        }

        try {
            unzipWithSystemCommand(zipFile, destDir);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void unzipWithApacheCommons(Path zipFile, Path destDir) throws IOException {
        log.info("unzip apache commons zip file: {}", zipFile);
        try (var zip = new org.apache.commons.compress.archivers.zip.ZipFile(zipFile)) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                Path entryPath = Paths.get(destDir.toString(), entry.getName()).normalize();

                if (!entryPath.startsWith(destDir.normalize())) {
                    throw new IOException("Bad ZIP entry: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (InputStream in = zip.getInputStream(entry);
                         OutputStream out = Files.newOutputStream(entryPath)) {
                        in.transferTo(out);
                    }
                }
            }
        }
    }

    private void unzipWithJava(Path zipFile, Path destDir) throws IOException {
        log.info("unzip by Java: {}", zipFile);
        try (var zip = new java.util.zip.ZipFile(zipFile.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path entryPath = Paths.get(destDir.toString(), entry.getName()).normalize();

                if (!entryPath.startsWith(destDir.normalize())) {
                    throw new IOException("Bad ZIP entry: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (InputStream in = zip.getInputStream(entry);
                         OutputStream out = Files.newOutputStream(entryPath)) {
                        in.transferTo(out);
                    }
                }
            }
        }
    }

    private void unzipWithSystemCommand(Path zipFile, Path destDir) throws IOException, InterruptedException {
        log.info("Unzip by linux command: {}", zipFile);
        ProcessBuilder pb = new ProcessBuilder("unzip", "-o", zipFile.toString(), "-d", destDir.toString());
        Process process = pb.start();

        String errorOutput = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("System unzip failed with code " + exitCode + ": " + errorOutput);
        }
    }

    private void saveVersion(Path versionFile, String version) throws IOException {
        Files.write(versionFile, version.getBytes(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void syncFiles(Path destDir, Path sourceDir) throws IOException {
        if (Files.exists(sourceDir)) {
            copyDirectory(sourceDir, destDir);
        }
    }

    private void logFileInfo(Path filePath) throws IOException {
        Path path = filePath;
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
