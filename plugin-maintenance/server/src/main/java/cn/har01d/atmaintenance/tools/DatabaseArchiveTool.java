package cn.har01d.atmaintenance.tools;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Cold, owned-database archives. This entry point never starts Spring or a worker. */
public final class DatabaseArchiveTool {
    static final long MAX_DATABASE_BYTES = 1_073_741_824L;
    static final int MAX_MANIFEST_BYTES = 65_536;
    static final String DATABASE = "maintenance.mv.db";
    private static final String KIND = "alist-tvbox-maintenance-h2";
    enum Deployment {
        STANDALONE(DATABASE, KIND, MAX_DATABASE_BYTES),
        AT_EMBEDDED("atv.mv.db", "alist-tvbox-at-embedded-h2", 64L * 1024 * 1024 * 1024);

        final String database;
        final String kind;
        final long maximumBytes;

        Deployment(String database, String kind, long maximumBytes) {
            this.database = database;
            this.kind = kind;
            this.maximumBytes = maximumBytes;
        }
    }
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                    .maxStringLength(MAX_MANIFEST_BYTES).maxNumberLength(32).build()).build());

    private DatabaseArchiveTool() {}

    public static void main(String[] args) {
        run(args, System.getenv().getOrDefault("MAINTENANCE_DB_PASSWORD", ""), Deployment.STANDALONE);
    }

    static void run(String[] args, String password, Deployment deployment) {
        try {
            ObjectNode result = execute(args, password, deployment);
            System.out.println(JSON.writeValueAsString(result));
        } catch (Exception error) {
            String code = error instanceof ArchiveFailure failure ? failure.getMessage() : "DATABASE_ARCHIVE_FAILED";
            System.err.println("{\"ok\":false,\"code\":\"" + code + "\"}");
            System.exit(1);
        }
    }

    static ObjectNode execute(String[] args, String password) throws Exception {
        return execute(args, password, Deployment.STANDALONE);
    }

    static ObjectNode execute(String[] args, String password, Deployment deployment) throws Exception {
        require(args.length >= 1 && (args.length - 1) % 2 == 0, "ARCHIVE_ARGUMENTS_INVALID");
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            require(options.putIfAbsent(args[i], args[i + 1]) == null, "ARCHIVE_ARGUMENTS_INVALID");
        }
        return switch (args[0]) {
            case "backup" -> {
                require(options.keySet().equals(Set.of("--database", "--output")), "ARCHIVE_ARGUMENTS_INVALID");
                yield backup(Path.of(options.get("--database")), Path.of(options.get("--output")), password, deployment);
            }
            case "inspect" -> {
                require(options.keySet().equals(Set.of("--archive", "--trusted-sha256")), "ARCHIVE_ARGUMENTS_INVALID");
                yield inspect(Path.of(options.get("--archive")), options.get("--trusted-sha256"), deployment);
            }
            case "restore" -> {
                require(options.keySet().equals(Set.of("--archive", "--destination", "--trusted-sha256")),
                        "ARCHIVE_ARGUMENTS_INVALID");
                yield restore(Path.of(options.get("--archive")), Path.of(options.get("--destination")),
                        options.get("--trusted-sha256"), password, deployment);
            }
            default -> throw new ArchiveFailure("ARCHIVE_ARGUMENTS_INVALID");
        };
    }

    static ObjectNode backup(Path database, Path output, String password) throws Exception {
        return backup(database, output, password, Deployment.STANDALONE);
    }

    static ObjectNode backup(Path database, Path output, String password, Deployment deployment) throws Exception {
        Path source = database.toAbsolutePath().normalize();
        require(source.getFileName() != null
                && (deployment == Deployment.AT_EMBEDDED
                    ? source.getFileName().toString().endsWith(".mv.db")
                    : source.getFileName().toString().equals(deployment.database))
                && Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(source), deployment == Deployment.STANDALONE
                ? "OWNED_MAINTENANCE_DATABASE_REQUIRED" : "OWNED_AT_EMBEDDED_DATABASE_REQUIRED");
        source = source.toRealPath();
        require(Files.size(source) <= deployment.maximumBytes, "ARCHIVE_DATABASE_TOO_LARGE");
        Path destination = newTarget(output);
        Path work = Files.createTempDirectory(destination.getParent(), ".maintenance-backup-");
        try {
            Path h2Backup = work.resolve("h2.zip");
            ObjectNode schema;
            String h2Version;
            // Holding the H2 connection also excludes a concurrent application or backup.
            try (Connection connection = open(source, password, false)) {
                schema = schema(connection, deployment);
                h2Version = version(connection);
                try (var statement = connection.prepareStatement("BACKUP TO ?")) {
                    statement.setString(1, h2Backup.toString());
                    statement.execute();
                }
            } catch (SQLException error) {
                throw sqlFailure(error);
            }
            Path snapshot = work.resolve(deployment.database);
            try (ZipFile zip = new ZipFile(h2Backup.toFile())) {
                String originalName = source.getFileName().toString();
                require(zip.size() == 1 && zip.getEntry(originalName) != null, "H2_BACKUP_CONTENT_INVALID");
                copyEntry(zip, zip.getEntry(originalName), snapshot, deployment.maximumBytes);
            }
            ObjectNode manifest = JSON.createObjectNode().put("schemaVersion", 1).put("kind", deployment.kind)
                    .put("createdAt", Instant.now().toString()).put("h2Version", h2Version)
                    .put("privateData", true).put("toolClassSha256", toolHash());
            manifest.set("database", JSON.createObjectNode().put("file", deployment.database)
                    .put("bytes", Files.size(snapshot)).put("sha256", hash(snapshot)));
            manifest.set("schema", schema);
            Path prepared = work.resolve("prepared.zip");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(prepared))) {
                zip.putNextEntry(new ZipEntry("manifest.json"));
                zip.write(JSON.writeValueAsBytes(manifest));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry(deployment.database));
                Files.copy(snapshot, zip);
                zip.closeEntry();
            }
            require(Files.size(prepared) <= deployment.maximumBytes + MAX_MANIFEST_BYTES, "ARCHIVE_TOO_LARGE");
            String archiveSha = hash(prepared);
            inspect(prepared, archiveSha, deployment);
            Files.move(prepared, destination);
            return receipt(archiveSha, manifest).put("operation", "backup")
                    .put("archiveBytes", Files.size(destination));
        } finally {
            deleteWork(work);
        }
    }

    static ObjectNode inspect(Path archive, String expectedSha) throws Exception {
        return inspect(archive, expectedSha, Deployment.STANDALONE);
    }

    static ObjectNode inspect(Path archive, String expectedSha, Deployment deployment) throws Exception {
        try (ZipFile zip = verifiedArchive(archive, expectedSha, deployment)) {
            ObjectNode manifest = manifest(zip, deployment);
            ZipEntry entry = zip.getEntry(deployment.database);
            var observed = readEntry(zip, entry, null, deployment.maximumBytes);
            require(observed.bytes() == manifest.path("database").path("bytes").longValue()
                    && observed.sha256().equals(manifest.path("database").path("sha256").textValue()),
                    "ARCHIVE_DATABASE_HASH_MISMATCH");
            return receipt(expectedSha, manifest).put("operation", "inspect").put("databaseOpened", false);
        }
    }

    static ObjectNode restore(Path archive, Path destination, String expectedSha, String password) throws Exception {
        return restore(archive, destination, expectedSha, password, Deployment.STANDALONE);
    }

    static ObjectNode restore(Path archive, Path destination, String expectedSha, String password,
                              Deployment deployment) throws Exception {
        // The expected identity must come from the owner's trusted backup receipt, not from this ZIP.
        Path target = newTarget(destination);
        Path work = Files.createTempDirectory(target.getParent(), ".maintenance-restore-");
        try {
            ObjectNode manifest;
            Path database = work.resolve(deployment.database);
            try (ZipFile zip = verifiedArchive(archive, expectedSha, deployment)) {
                manifest = manifest(zip, deployment);
                var observed = copyEntry(zip, zip.getEntry(deployment.database), database, deployment.maximumBytes);
                require(observed.bytes() == manifest.path("database").path("bytes").longValue()
                        && observed.sha256().equals(manifest.path("database").path("sha256").textValue()),
                        "ARCHIVE_DATABASE_HASH_MISMATCH");
            }
            try (Connection connection = open(database, password, true)) {
                require(version(connection).equals(manifest.path("h2Version").textValue()),
                        "ARCHIVE_H2_VERSION_MISMATCH");
                require(schema(connection, deployment).equals(manifest.get("schema")), "ARCHIVE_SCHEMA_MISMATCH");
            } catch (SQLException error) {
                throw sqlFailure(error);
            }
            require(hash(database).equals(manifest.path("database").path("sha256").textValue()),
                    "ARCHIVE_VALIDATION_CHANGED_DATABASE");
            // Persist the trusted archive's metadata next to the restored file, never inside the DB.
            Files.write(work.resolve("recovery-manifest.json"), JSON.writeValueAsBytes(manifest),
                    StandardOpenOption.CREATE_NEW);
            Files.move(work, target);
            return receipt(expectedSha, manifest).put("operation", "restore")
                    .put("applicationStarted", false).put("requiresLocalOnlyStartup", true);
        } finally {
            deleteWork(work);
        }
    }

    private static ZipFile verifiedArchive(Path archive, String expectedSha, Deployment deployment) throws Exception {
        require(expectedSha != null && expectedSha.matches("[a-f0-9]{64}"), "TRUSTED_ARCHIVE_SHA256_REQUIRED");
        require(Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)
                && Files.size(archive) <= deployment.maximumBytes + MAX_MANIFEST_BYTES, "ARCHIVE_FILE_INVALID");
        require(hash(archive).equals(expectedSha), "ARCHIVE_IDENTITY_MISMATCH");
        ZipFile zip = new ZipFile(archive.toFile());
        try {
            Set<String> names = new HashSet<>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                require(!entry.isDirectory() && names.add(entry.getName())
                        && Set.of("manifest.json", deployment.database).contains(entry.getName())
                        && Set.of(ZipEntry.STORED, ZipEntry.DEFLATED).contains(entry.getMethod()),
                        "ARCHIVE_ENTRIES_INVALID");
            }
            require(names.equals(Set.of("manifest.json", deployment.database)), "ARCHIVE_ENTRIES_INVALID");
            return zip;
        } catch (Exception error) {
            zip.close();
            throw error;
        }
    }

    private static ObjectNode manifest(ZipFile zip, Deployment deployment) throws Exception {
        var output = new java.io.ByteArrayOutputStream();
        readEntry(zip, zip.getEntry("manifest.json"), output, MAX_MANIFEST_BYTES);
        JsonNode parsed;
        try (var parser = JSON.createParser(output.toByteArray())) {
            parsed = JSON.readTree(parser);
            require(parser.nextToken() == null, "ARCHIVE_MANIFEST_INVALID");
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new ArchiveFailure("ARCHIVE_MANIFEST_INVALID");
        }
        require(parsed != null && parsed.isObject()
                && parsed.path("schemaVersion").isIntegralNumber() && parsed.path("schemaVersion").canConvertToInt()
                && parsed.path("schemaVersion").intValue() == 1
                && parsed.path("kind").asText().equals(deployment.kind) && parsed.path("privateData").isBoolean()
                && parsed.path("privateData").booleanValue()
                && parsed.path("h2Version").asText().matches("[0-9]+\\.[0-9]+\\.[0-9]+")
                && parsed.path("database").path("file").asText().equals(deployment.database)
                && parsed.path("database").path("bytes").isIntegralNumber()
                && parsed.path("database").path("bytes").canConvertToLong()
                && parsed.path("database").path("bytes").longValue() > 0
                && parsed.path("database").path("bytes").longValue() <= deployment.maximumBytes
                && parsed.path("database").path("sha256").asText().matches("[a-f0-9]{64}")
                && parsed.path("schema").isObject(), "ARCHIVE_MANIFEST_INVALID");
        return (ObjectNode) parsed;
    }

    private static Connection open(Path database, String password, boolean readOnly) throws SQLException, ArchiveFailure {
        String file = database.toAbsolutePath().normalize().toString().replace('\\', '/');
        require(file.endsWith(".mv.db") && !file.contains(";") && !file.contains("\r") && !file.contains("\n"),
                "DATABASE_PATH_INVALID");
        String url = "jdbc:h2:file:" + file.substring(0, file.length() - ".mv.db".length())
                + ";IFEXISTS=TRUE;AUTO_SERVER=FALSE;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0"
                + (readOnly ? ";ACCESS_MODE_DATA=r" : "");
        return DriverManager.getConnection(url, "sa", password);
    }

    private static ObjectNode schema(Connection connection, Deployment deployment) throws Exception {
        ObjectNode result = JSON.createObjectNode();
        ObjectNode tables = result.putObject("tables");
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                     + "WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")) {
            while (rows.next()) {
                String table = rows.getString(1);
                require(table.equals("flyway_schema_history")
                        || deployment == Deployment.AT_EMBEDDED && table.equals("maintenance_schema_history")
                        || table.matches(deployment == Deployment.STANDALONE
                        ? "MAINTENANCE_[A-Z_]+" : "[A-Z_][A-Z0-9_]*"), schemaError(deployment));
                try (var countStatement = connection.createStatement();
                     var count = countStatement.executeQuery("SELECT COUNT(*) FROM \"" + table + "\"")) {
                    count.next();
                    tables.put(table, count.getLong(1));
                }
            }
        }
        require(tables.has("MAINTENANCE_SOURCE") && tables.has("MAINTENANCE_ARTIFACT")
                && tables.has("MAINTENANCE_MODEL_BUDGET") && tables.has("MAINTENANCE_UPSTREAM")
                && tables.has("flyway_schema_history"), schemaError(deployment));
        if (deployment == Deployment.AT_EMBEDDED) {
            require(tables.has("PLUGIN") && tables.has("X_USER") && tables.has("SETTING")
                    && tables.has("ID_GENERATOR"), schemaError(deployment));
        }
        boolean embeddedMigration = migrations(connection, "flyway_schema_history", result.putArray("migrations"));
        if (deployment == Deployment.AT_EMBEDDED && tables.has("maintenance_schema_history")) {
            embeddedMigration |= migrations(connection, "maintenance_schema_history",
                    result.putArray("maintenanceMigrations"));
        }
        require(!result.path("migrations").isEmpty()
                && (deployment == Deployment.STANDALONE || embeddedMigration), schemaError(deployment));
        // Normalize Jackson's integer widths to the same representation read from an archive.
        return (ObjectNode) JSON.readTree(JSON.writeValueAsBytes(result));
    }

    private static boolean migrations(Connection connection, String table,
                                      com.fasterxml.jackson.databind.node.ArrayNode migrations) throws SQLException, ArchiveFailure {
        boolean embeddedMigration = false;
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT \"installed_rank\",\"version\",\"script\",\"checksum\",\"success\" "
                     + "FROM \"" + table + "\" ORDER BY \"installed_rank\"")) {
            while (rows.next()) {
                require(rows.getBoolean(5), "DATABASE_MIGRATION_INCOMPLETE");
                var migration = migrations.addObject().put("rank", rows.getInt(1))
                        .put("version", rows.getString(2)).put("script", rows.getString(3));
                if ("59".equals(rows.getString(2)) && "V59__PluginMaintenance.sql".equals(rows.getString(3))) {
                    embeddedMigration = true;
                }
                int checksum = rows.getInt(4);
                if (rows.wasNull()) migration.putNull("checksum");
                else migration.put("checksum", checksum);
            }
        }
        return embeddedMigration;
    }

    private static String schemaError(Deployment deployment) {
        return deployment == Deployment.STANDALONE
                ? "OWNED_MAINTENANCE_SCHEMA_REQUIRED" : "OWNED_AT_EMBEDDED_SCHEMA_REQUIRED";
    }

    private static String version(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT H2VERSION()")) {
            result.next();
            return result.getString(1);
        }
    }

    private static ObjectNode receipt(String sha, JsonNode manifest) throws IOException {
        ObjectNode result = JSON.createObjectNode().put("ok", true).put("archiveSha256", sha)
                .put("privateData", true);
        result.set("manifest", manifest);
        return (ObjectNode) JSON.readTree(JSON.writeValueAsBytes(result));
    }

    private static Path newTarget(Path path) throws IOException {
        Path target = path.toAbsolutePath().normalize();
        require(target.getFileName() != null && Files.isDirectory(target.getParent())
                && !Files.exists(target, LinkOption.NOFOLLOW_LINKS), "ARCHIVE_TARGET_MUST_NOT_EXIST");
        return target.getParent().toRealPath().resolve(target.getFileName());
    }

    private static EntryIdentity copyEntry(ZipFile zip, ZipEntry entry, Path path, long limit) throws Exception {
        try (OutputStream output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
            return readEntry(zip, entry, output, limit);
        }
    }

    private static EntryIdentity readEntry(ZipFile zip, ZipEntry entry, OutputStream output, long limit) throws Exception {
        require(entry != null && entry.getSize() > 0 && entry.getSize() <= limit, "ARCHIVE_ENTRY_TOO_LARGE");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        CRC32 crc = new CRC32();
        long size = 0;
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] buffer = new byte[65_536];
            int count;
            while ((count = input.read(buffer)) != -1) {
                size += count;
                require(size <= limit, "ARCHIVE_ENTRY_TOO_LARGE");
                digest.update(buffer, 0, count);
                crc.update(buffer, 0, count);
                if (output != null) output.write(buffer, 0, count);
            }
        }
        require(size == entry.getSize() && crc.getValue() == entry.getCrc(), "ARCHIVE_ENTRY_CORRUPT");
        return new EntryIdentity(size, HexFormat.of().formatHex(digest.digest()));
    }

    private static String hash(Path file) throws Exception {
        try (InputStream input = Files.newInputStream(file)) {
            return hash(input);
        }
    }

    private static String hash(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var stream = new DigestInputStream(input, digest)) {
            stream.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String toolHash() throws Exception {
        return hash(Objects.requireNonNull(DatabaseArchiveTool.class.getResourceAsStream("DatabaseArchiveTool.class")));
    }

    private static ArchiveFailure sqlFailure(SQLException error) {
        return new ArchiveFailure(error.getErrorCode() == 90020 ? "DATABASE_IN_USE"
                : error.getErrorCode() == 28000 ? "DATABASE_AUTHENTICATION_FAILED" : "DATABASE_VERIFICATION_FAILED");
    }

    private static void deleteWork(Path work) throws IOException {
        if (!Files.exists(work)) return;
        try (var entries = Files.walk(work)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.delete(entry);
        }
    }

    private static void require(boolean condition, String code) throws ArchiveFailure {
        if (!condition) throw new ArchiveFailure(code);
    }

    private record EntryIdentity(long bytes, String sha256) {}

    static final class ArchiveFailure extends IOException {
        ArchiveFailure(String code) { super(code); }
    }
}
