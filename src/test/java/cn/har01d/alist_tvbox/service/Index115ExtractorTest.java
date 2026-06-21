package cn.har01d.alist_tvbox.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class Index115ExtractorTest {
    @TempDir
    Path temp;

    private Path makeZip(String... entries) throws IOException {
        Path zip = temp.resolve("pkg.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (String e : entries) {
                z.putNextEntry(new ZipEntry(e));
                z.write(new byte[]{1});
                z.closeEntry();
            }
        }
        return zip;
    }

    @Test
    void extractsAndSwaps() throws IOException {
        Path zip = makeZip("index.db", "bleve/x");
        Path dir = temp.resolve("index115");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("stale"), "old");

        new Index115Extractor().extractAndSwap(zip, dir);

        assertTrue(Files.exists(dir.resolve("index.db")));
        assertTrue(Files.exists(dir.resolve("bleve").resolve("x")));
        assertFalse(Files.exists(dir.resolve("stale")), "old contents replaced");
    }

    @Test
    void swapsWhenTargetMissing() throws IOException {
        Path zip = makeZip("index.db");
        Path dir = temp.resolve("index115");

        new Index115Extractor().extractAndSwap(zip, dir);

        assertTrue(Files.exists(dir.resolve("index.db")));
    }
}
