package cn.har01d.atmaintenance.service;

import org.flywaydb.core.Flyway;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;

/** Offline, copy-only upgrade of private RC databases with a shared Flyway history. */
public final class LegacyMaintenanceExport {
    private static final List<String> SCRIPTS = List.of("V59__PluginMaintenance.sql", "V60__MaintenanceAtDeployment.sql",
            "V61__ImportedRepairCandidate.sql", "V62__AutomaticRepair.sql", "V63__MaintenanceWorkflow.sql",
            "V64__AtPublication.sql", "V65__RuntimeActivation.sql", "V66__SourceCredentials.sql",
            "V67__EnhancedDiagnostics.sql", "V68__ReverseAnalysis.sql", "V69__ModelResponseDiagnostics.sql",
            "V70__RepairRuntimeContext.sql", "V71__UpstreamObservation.sql", "V72__EvidenceContinuation.sql",
            "V73__MaintenanceAutomation.sql", "V74__NewSourcePublication.sql", "V75__ConfigurableRepairRounds.sql");
    private static final String ARCHIVE = "legacy_maintenance_schema_history";

    private LegacyMaintenanceExport() {}

    public static void main(String[] args) {
        try {
            if (args.length != 3) throw new IllegalArgumentException("LEGACY_EXPORT_ARGUMENTS_INVALID");
            String signature = export(Path.of(args[0]), Path.of(args[1]), args[2],
                    System.getenv().getOrDefault("MAINTENANCE_EXPORT_DB_USER", "sa"),
                    System.getenv().getOrDefault("MAINTENANCE_EXPORT_DB_PASSWORD", ""));
            System.out.println("{\"exported\":true,\"sourcePreserved\":true,\"businessDataSha256\":\"" + signature + "\"}");
        } catch (Exception error) {
            System.err.println("LEGACY_EXPORT_FAILED");
            System.exit(1);
        }
    }

    static String export(Path original, Path destination, String expectedSha, String user, String password) throws Exception {
        Path source = original.toRealPath();
        Path target = destination.toAbsolutePath().normalize();
        if (!source.toString().endsWith(".mv.db") || !target.toString().endsWith(".mv.db")
                || source.equals(target) || Files.exists(target) || !expectedSha.matches("[a-f0-9]{64}")
                || !expectedSha.equals(fileSha(source))) throw new IllegalArgumentException("LEGACY_EXPORT_INPUT_CHANGED");
        try (var channel = FileChannel.open(source, StandardOpenOption.READ);
             var lock = channel.tryLock(0, Long.MAX_VALUE, true)) {
            if (lock == null) throw new IllegalStateException("LEGACY_EXPORT_SOURCE_ACTIVE");
        }
        String signature;
        try (var connection = DriverManager.getConnection(url(source) + ";ACCESS_MODE_DATA=r", user, password)) {
            requireLegacy(connection);
            signature = businessSignature(connection);
            Files.copy(source, target);
            if (!expectedSha.equals(fileSha(target))) throw new IllegalStateException("LEGACY_EXPORT_COPY_CHANGED");
        }
        try (var connection = DriverManager.getConnection(url(target), user, password);
             var statement = connection.createStatement()) {
            String historySignature = tableSignature(connection, "flyway_schema_history");
            statement.execute("ALTER TABLE \"flyway_schema_history\" RENAME TO \"" + ARCHIVE + "\"");
            String names = "'" + String.join("','", SCRIPTS) + "'";
            for (String name : List.of("flyway_schema_history", "maintenance_schema_history")) {
                statement.execute("CREATE TABLE \"" + name + "\" AS SELECT * FROM \"" + ARCHIVE
                        + "\" WHERE \"script\" " + (name.equals("flyway_schema_history") ? "NOT " : "") + "IN (" + names + ")");
                statement.execute("ALTER TABLE \"" + name + "\" ALTER COLUMN \"installed_rank\" SET NOT NULL");
                statement.execute("ALTER TABLE \"" + name + "\" ADD PRIMARY KEY(\"installed_rank\")");
            }
            if (!historySignature.equals(tableSignature(connection, ARCHIVE))
                    || !signature.equals(businessSignature(connection)))
                throw new IllegalStateException("LEGACY_EXPORT_DATA_CHANGED");
        }
        var validation = Flyway.configure().dataSource(url(target), user, password)
                .locations("classpath:db/maintenance/h2").table("maintenance_schema_history").load().validateWithResult();
        if (!validation.validationSuccessful) throw new IllegalStateException("LEGACY_EXPORT_MIGRATIONS_CHANGED");
        if (!expectedSha.equals(fileSha(source))) throw new IllegalStateException("LEGACY_EXPORT_SOURCE_CHANGED");
        return signature;
    }

    private static void requireLegacy(Connection connection) throws Exception {
        try (var tables = connection.getMetaData().getTables(null, "PUBLIC", "maintenance_schema_history", null)) {
            if (tables.next()) throw new IllegalStateException("LEGACY_EXPORT_NOT_REQUIRED");
        }
        var found = new ArrayList<String>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT \"script\", \"success\" FROM \"flyway_schema_history\"")) {
            while (rows.next()) {
                if (!rows.getBoolean(2)) throw new IllegalStateException("LEGACY_EXPORT_FAILED_MIGRATION");
                if (SCRIPTS.contains(rows.getString(1))) found.add(rows.getString(1));
            }
        }
        if (found.size() != SCRIPTS.size() || !found.containsAll(SCRIPTS))
            throw new IllegalStateException("LEGACY_EXPORT_HISTORY_INCOMPLETE");
    }

    private static String businessSignature(Connection connection) throws Exception {
        var tables = new TreeMap<String, String>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                     + "WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_TYPE='BASE TABLE'")) {
            while (rows.next()) {
                String name = rows.getString(1);
                if (!List.of("flyway_schema_history", "maintenance_schema_history", ARCHIVE).contains(name))
                    tables.put(name, tableSignature(connection, name));
            }
        }
        return sha(tables.toString());
    }

    private static String tableSignature(Connection connection, String table) throws Exception {
        var records = new ArrayList<String>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT * FROM \"" + table.replace("\"", "\"\"") + "\"")) {
            while (rows.next()) {
                var row = new StringBuilder();
                for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                    String value = rows.getString(column);
                    row.append(value == null ? "-1:" : value.length() + ":" + value);
                }
                records.add(sha(row.toString()));
            }
        }
        records.sort(String::compareTo);
        return sha(String.join("\n", records));
    }

    private static String url(Path file) {
        String path = file.toString().replace('\\', '/');
        if (path.contains(";")) throw new IllegalArgumentException("LEGACY_EXPORT_PATH_INVALID");
        return "jdbc:h2:file:" + path.substring(0, path.length() - ".mv.db".length()) + ";IFEXISTS=TRUE";
    }

    private static String fileSha(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int size;
            while ((size = stream.read(buffer)) != -1) digest.update(buffer, 0, size);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
