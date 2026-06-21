package cn.har01d.alist_tvbox.service;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Slf4j
public class Index115Extractor {
    public void extractAndSwap(Path zip, Path dir) throws IOException {
        Path parent = dir.getParent();
        String name = dir.getFileName().toString();
        Path next = parent.resolve(name + ".new");
        Path old = parent.resolve(name + ".old");

        deleteRecursively(next);
        Files.createDirectories(next);
        unzipTo(zip, next);

        deleteRecursively(old);
        if (Files.exists(dir)) {
            Files.move(dir, old, StandardCopyOption.ATOMIC_MOVE);
        }
        Files.move(next, dir, StandardCopyOption.ATOMIC_MOVE);
        deleteRecursively(old);
    }

    private void unzipTo(Path zip, Path dest) throws IOException {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String n = entry.getName();
                if (n.contains("..") || n.startsWith("/") || n.contains("\\")) {
                    throw new IOException("invalid zip entry: " + n);
                }
                Path target = dest.resolve(n).normalize();
                if (!target.startsWith(dest.normalize())) {
                    throw new IOException("zip traversal: " + n);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = zf.getInputStream(entry);
                     OutputStream out = Files.newOutputStream(target)) {
                    in.transferTo(out);
                }
            }
        }
    }

    private void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(p)) {
            walk.sorted(java.util.Collections.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
