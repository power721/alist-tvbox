package cn.har01d.atmaintenance.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseArchiveToolTest {
    @TempDir Path directory;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BODY = "\uFEFF# \u4e2d\u6587\r\nraise RuntimeError('DATA_ONLY')\r\n";

    @Test
    void allTablesAndOriginalBytesRoundTripWithoutStartingTheApplication() throws Exception {
        Path database = database("");
        Path archive = directory.resolve("owned.zip");
        var backup = DatabaseArchiveTool.backup(database, archive, "");
        String sha = backup.path("archiveSha256").asText();
        var inspected = DatabaseArchiveTool.inspect(archive, sha);
        assertFalse(inspected.path("databaseOpened").booleanValue());
        assertTrue(inspected.path("privateData").booleanValue());
        assertEquals(backup.path("manifest"), inspected.path("manifest"));
        assertTrue(inspected.path("manifest").path("schema").path("tables").size() >= 20);
        assertEquals(1, inspected.path("manifest").path("schema").path("tables")
                .path("MAINTENANCE_SOURCE").intValue());

        Path restored = directory.resolve("restored");
        var receipt = DatabaseArchiveTool.restore(archive, restored, sha, "");
        assertFalse(receipt.path("applicationStarted").booleanValue());
        assertTrue(receipt.path("requiresLocalOnlyStartup").booleanValue());
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            assertArrayEquals(zip.getInputStream(zip.getEntry("maintenance.mv.db")).readAllBytes(),
                    Files.readAllBytes(restored.resolve("maintenance.mv.db")));
        }
        try (var connection = DriverManager.getConnection(url(restored.resolve("maintenance.mv.db")), "sa", "");
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT body FROM maintenance_artifact")) {
                assertTrue(rows.next());
                assertEquals(BODY, rows.getString(1));
            }
            try (var rows = statement.executeQuery("SELECT used_calls,reserved_input_bytes,reserved_output_tokens,"
                    + "reserved_cost_usd FROM maintenance_model_budget")) {
                assertTrue(rows.next());
                assertEquals(3, rows.getInt(1));
                assertEquals(321, rows.getLong(2));
                assertEquals(456, rows.getLong(3));
                assertEquals(0, rows.getBigDecimal(4).compareTo(new java.math.BigDecimal("0.12")));
            }
        }
    }

    @Test
    void wrongIdentityOrExistingDestinationNeverPublishesOrOverwrites() throws Exception {
        Path database = database("");
        Path archive = directory.resolve("owned.zip");
        var backup = DatabaseArchiveTool.backup(database, archive, "");
        byte[] original = Files.readAllBytes(archive);
        failure("ARCHIVE_TARGET_MUST_NOT_EXIST", () -> DatabaseArchiveTool.backup(database, archive, ""));
        assertArrayEquals(original, Files.readAllBytes(archive));
        Path target = directory.resolve("restored");
        failure("ARCHIVE_IDENTITY_MISMATCH", () -> DatabaseArchiveTool.restore(archive, target, "0".repeat(64), ""));
        assertFalse(Files.exists(target));
        Files.createDirectory(target);
        Files.writeString(target.resolve("keep.txt"), "untouched");
        failure("ARCHIVE_TARGET_MUST_NOT_EXIST", () -> DatabaseArchiveTool.restore(archive, target,
                backup.path("archiveSha256").asText(), ""));
        assertEquals("untouched", Files.readString(target.resolve("keep.txt")));
        assertNoTemporaryWork();
    }

    @Test
    void foreignDatabasesAndMissingOrJdbcInjectedPathsAreNotAccepted() throws Exception {
        Path foreign = directory.resolve("foreign");
        Files.createDirectory(foreign);
        Path database = foreign.resolve("maintenance.mv.db");
        try (var connection = DriverManager.getConnection(url(database), "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE private_business(id INT)");
        }
        failure("OWNED_MAINTENANCE_SCHEMA_REQUIRED", () -> DatabaseArchiveTool.backup(database,
                directory.resolve("rejected.zip"), ""));
        failure("OWNED_MAINTENANCE_DATABASE_REQUIRED", () -> DatabaseArchiveTool.backup(
                directory.resolve("missing.mv.db"), directory.resolve("missing.zip"), ""));
        assertFalse(Files.exists(directory.resolve("rejected.zip")));
        assertNoTemporaryWork();
    }

    @Test
    void authenticationFailureDoesNotLeakThePasswordOrPublishPartialFiles() throws Exception {
        Path database = database("PRIVATE_PASSWORD_DO_NOT_PRINT");
        Path archive = directory.resolve("owned.zip");
        failure("DATABASE_AUTHENTICATION_FAILED", () -> DatabaseArchiveTool.backup(database, archive, "wrong"));
        assertFalse(Files.exists(archive));
        var backup = DatabaseArchiveTool.backup(database, archive, "PRIVATE_PASSWORD_DO_NOT_PRINT");
        assertFalse(backup.toString().contains("PRIVATE_PASSWORD"));
        Path target = directory.resolve("wrong-password");
        failure("DATABASE_AUTHENTICATION_FAILED", () -> DatabaseArchiveTool.restore(archive, target,
                backup.path("archiveSha256").asText(), "wrong"));
        assertFalse(Files.exists(target));
        assertNoTemporaryWork();
    }

    @Test
    void lockedDatabaseCannotBeBackedUpAsIfItWereCold() throws Exception {
        Path database = database("");
        try (var channel = FileChannel.open(database, StandardOpenOption.READ, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            failure("DATABASE_IN_USE", () -> DatabaseArchiveTool.backup(database,
                    directory.resolve("in-use.zip"), ""));
        }
        assertFalse(Files.exists(directory.resolve("in-use.zip")));
        assertNoTemporaryWork();
    }

    @Test
    void manifestHashAndSchemaTamperingFailWithoutPublishingRestoredData() throws Exception {
        Path database = database("");
        Path archive = directory.resolve("owned.zip");
        DatabaseArchiveTool.backup(database, archive, "");
        var files = entries(archive);
        var manifest = JSON.readTree(files.get("manifest.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) manifest.path("database")).put("sha256", "0".repeat(64));
        files.put("manifest.json", JSON.writeValueAsBytes(manifest));
        Path changed = writeArchive("bad-hash.zip", files);
        failure("ARCHIVE_DATABASE_HASH_MISMATCH", () -> DatabaseArchiveTool.inspect(changed, sha(changed)));

        files = entries(archive);
        manifest = JSON.readTree(files.get("manifest.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) manifest.path("schema").path("tables"))
                .put("MAINTENANCE_SOURCE", 500);
        files.put("manifest.json", JSON.writeValueAsBytes(manifest));
        Path wrongSchema = writeArchive("bad-schema.zip", files);
        Path target = directory.resolve("not-published");
        failure("ARCHIVE_SCHEMA_MISMATCH", () -> DatabaseArchiveTool.restore(wrongSchema, target, sha(wrongSchema), ""));
        assertFalse(Files.exists(target));
        assertNoTemporaryWork();
    }

    @Test
    void invalidEntryNamesAndTrailingOrDuplicateManifestJsonAreRejectedBeforeOpeningTheDatabase() throws Exception {
        Path database = database("");
        Path archive = directory.resolve("owned.zip");
        DatabaseArchiveTool.backup(database, archive, "");
        for (String name : List.of("../escape.mv.db", "/maintenance.mv.db", "nested/maintenance.mv.db", "unknown")) {
            var files = entries(archive);
            files.put(name, new byte[]{1});
            Path changed = writeArchive(UUID.randomUUID() + ".zip", files);
            failure("ARCHIVE_ENTRIES_INVALID", () -> DatabaseArchiveTool.inspect(changed, sha(changed)));
        }
        for (String suffix : List.of(" {}", "\nnull")) {
            var files = entries(archive);
            files.put("manifest.json", (new String(files.get("manifest.json"), StandardCharsets.UTF_8) + suffix)
                    .getBytes(StandardCharsets.UTF_8));
            Path changed = writeArchive(UUID.randomUUID() + ".zip", files);
            failure("ARCHIVE_MANIFEST_INVALID", () -> DatabaseArchiveTool.inspect(changed, sha(changed)));
        }
        var files = entries(archive);
        files.put("manifest.json", "{\"schemaVersion\":1,\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8));
        Path changed = writeArchive("duplicate-json.zip", files);
        failure("ARCHIVE_MANIFEST_INVALID", () -> DatabaseArchiveTool.inspect(changed, sha(changed)));
    }

    @Test
    void duplicateZipEntriesAndCrcCorruptionAreRejected() throws Exception {
        Path database = database("");
        Path archive = directory.resolve("owned.zip");
        DatabaseArchiveTool.backup(database, archive, "");
        var files = entries(archive);
        var duplicate = new LinkedHashMap<>(files);
        duplicate.put("manifesx.json", files.get("manifest.json"));
        Path changed = writeArchive("duplicate-zip.zip", duplicate);
        byte[] bytes = Files.readAllBytes(changed);
        byte[] from = "manifesx.json".getBytes(StandardCharsets.US_ASCII);
        byte[] to = "manifest.json".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i <= bytes.length - from.length; i++) {
            if (Arrays.equals(bytes, i, i + from.length, from, 0, from.length)) System.arraycopy(to, 0, bytes, i, to.length);
        }
        Files.write(changed, bytes);
        failure("ARCHIVE_ENTRIES_INVALID", () -> DatabaseArchiveTool.inspect(changed, sha(changed)));

        Path corrupt = directory.resolve("corrupt.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(corrupt))) {
            for (var entry : files.entrySet()) {
                ZipEntry record = new ZipEntry(entry.getKey());
                var crc = new java.util.zip.CRC32();
                crc.update(entry.getValue());
                record.setMethod(ZipEntry.STORED);
                record.setSize(entry.getValue().length);
                record.setCrc(crc.getValue());
                zip.putNextEntry(record);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        bytes = Files.readAllBytes(corrupt);
        // A stored entry's central-directory CRC is not checked by ZipFile's raw stream.
        for (int i = 0; i < bytes.length - 46; i++) {
            if (ByteBuffer.wrap(bytes, i, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt() == 0x02014b50) {
                bytes[i + 16] ^= 1;
                break;
            }
        }
        Files.write(corrupt, bytes);
        failure("ARCHIVE_ENTRY_CORRUPT", () -> DatabaseArchiveTool.inspect(corrupt, sha(corrupt)));
    }

    @Test
    void schemaVersionMustBeAnExactBoundedIntegerAndTheToolDoesNotAcceptArbitraryArguments() throws Exception {
        Path database = database("");
        Path archive = directory.resolve("owned.zip");
        DatabaseArchiveTool.backup(database, archive, "");
        for (String value : List.of("1.0", "1e0", "\"1\"", "18446744073709551617", "true", "null")) {
            var files = entries(archive);
            String text = new String(files.get("manifest.json"), StandardCharsets.UTF_8)
                    .replace("\"schemaVersion\":1", "\"schemaVersion\":" + value);
            files.put("manifest.json", text.getBytes(StandardCharsets.UTF_8));
            Path changed = writeArchive(UUID.randomUUID() + ".zip", files);
            failure("ARCHIVE_MANIFEST_INVALID", () -> DatabaseArchiveTool.inspect(changed, sha(changed)));
        }
        failure("ARCHIVE_ARGUMENTS_INVALID", () -> DatabaseArchiveTool.execute(
                new String[]{"backup", "--database", "first", "--database", "second"}, ""));
        failure("TRUSTED_ARCHIVE_SHA256_REQUIRED", () -> DatabaseArchiveTool.inspect(archive, null));
    }

    private Path database(String password) throws Exception {
        Path source = directory.resolve(UUID.randomUUID().toString());
        Files.createDirectory(source);
        Path file = source.resolve("maintenance.mv.db");
        Flyway.configure().dataSource(url(file), "sa", password).locations("classpath:db/migration/h2").load().migrate();
        try (var connection = DriverManager.getConnection(url(file), "sa", password)) {
            try (var statement = connection.createStatement()) {
                statement.execute("INSERT INTO maintenance_source(id,name,filename,created_at) "
                        + "VALUES('source-1','fixture','source.py',CURRENT_TIMESTAMP)");
                statement.execute("UPDATE maintenance_model_budget SET used_calls=3,reserved_input_bytes=321,"
                        + "reserved_output_tokens=456,reserved_cost_usd=0.12");
            }
            try (var insert = connection.prepareStatement("INSERT INTO maintenance_artifact"
                    + "(id,source_id,sha256,body,created_at) VALUES('artifact-1','source-1',?,?,CURRENT_TIMESTAMP)")) {
                insert.setString(1, sha(BODY.getBytes(StandardCharsets.UTF_8)));
                insert.setString(2, BODY);
                insert.executeUpdate();
            }
        }
        return file;
    }

    private static String url(Path file) {
        return "jdbc:h2:file:" + file.toAbsolutePath().toString().replace('\\', '/').replace(".mv.db", "")
                + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
    }

    private Map<String, byte[]> entries(Path archive) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (var zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                files.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
            }
        }
        return files;
    }

    private Path writeArchive(String filename, Map<String, byte[]> files) throws Exception {
        Path archive = directory.resolve(filename);
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (var entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return archive;
    }

    private static String sha(Path file) throws Exception { return sha(Files.readAllBytes(file)); }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void assertNoTemporaryWork() throws Exception {
        try (var entries = Files.list(directory)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().startsWith(".maintenance-")));
        }
    }

    private void failure(String code, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(code, assertThrows(DatabaseArchiveTool.ArchiveFailure.class, operation).getMessage());
    }
}
