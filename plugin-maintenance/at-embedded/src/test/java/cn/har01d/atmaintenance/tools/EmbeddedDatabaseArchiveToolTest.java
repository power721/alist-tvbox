package cn.har01d.atmaintenance.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import cn.har01d.atmaintenance.service.EmbeddedMaintenanceMigrations;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddedDatabaseArchiveToolTest {
    @TempDir Path directory;
    private static final DatabaseArchiveTool.Deployment DEPLOYMENT = DatabaseArchiveTool.Deployment.AT_EMBEDDED;
    private static final String BODY = "\uFEFF# \u4e2d\u6587\r\nraise RuntimeError('DATA_ONLY')\r\n";
    private static final String CANDIDATE = BODY + "# candidate data only\r\n";
    private static final String LOCAL_RECEIPT = "{\"fixture\":true,\"productionModified\":false}";

    @Test
    void actualAtAndMaintenanceTablesRecoverTogetherWithoutStartingEitherApplication() throws Exception {
        Path database = database("custom-at-database.mv.db");
        seed(database);
        Path archive = directory.resolve("embedded.zip");
        var backup = DatabaseArchiveTool.backup(database, archive, "", DEPLOYMENT);
        String trusted = backup.path("archiveSha256").asText();
        assertEquals("alist-tvbox-at-embedded-h2", backup.path("manifest").path("kind").asText());
        var tables = backup.path("manifest").path("schema").path("tables");
        assertTrue(tables.size() > 50);
        assertEquals(1, tables.path("PLUGIN").asInt());
        assertEquals(1, tables.path("MAINTENANCE_RELEASE_PLAN").asInt());
        assertEquals(1, tables.path("MAINTENANCE_MODEL_CALL").asInt());
        var inspected = DatabaseArchiveTool.inspect(archive, trusted, DEPLOYMENT);
        assertFalse(inspected.path("databaseOpened").asBoolean());

        Path recovered = directory.resolve("recovered");
        var result = DatabaseArchiveTool.restore(archive, recovered, trusted, "", DEPLOYMENT);
        assertFalse(result.path("applicationStarted").asBoolean());
        assertTrue(result.path("requiresLocalOnlyStartup").asBoolean());
        Path restored = recovered.resolve("atv.mv.db");
        try (var zip = new ZipFile(archive.toFile())) {
            assertArrayEquals(zip.getInputStream(zip.getEntry("atv.mv.db")).readAllBytes(),
                    Files.readAllBytes(restored));
        }
        try (var connection = DriverManager.getConnection(url(restored) + ";ACCESS_MODE_DATA=r", "sa", "");
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT content FROM plugin WHERE id=501")) {
                assertTrue(rows.next());
                assertEquals(BODY, rows.getString(1));
            }
            try (var rows = statement.executeQuery("SELECT body FROM maintenance_repair_candidate WHERE id='candidate-1'")) {
                assertTrue(rows.next());
                assertEquals(CANDIDATE, rows.getString(1));
            }
            try (var rows = statement.executeQuery("SELECT local_receipt_json FROM maintenance_release_plan")) {
                assertTrue(rows.next());
                assertEquals(LOCAL_RECEIPT, rows.getString(1));
            }
            try (var rows = statement.executeQuery("SELECT used_calls,active_call_id FROM maintenance_model_budget")) {
                assertTrue(rows.next());
                assertEquals(3, rows.getInt(1));
                assertEquals("call-1", rows.getString(2));
            }
            try (var rows = statement.executeQuery("SELECT used_attempts,enabled FROM maintenance_probe_schedule")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
                assertTrue(rows.getBoolean(2));
            }
        }
        assertEquals(backup.path("manifest"),
                new ObjectMapper().readTree(Files.readAllBytes(recovered.resolve("recovery-manifest.json"))));
    }

    @Test
    void deploymentKindsCannotBeConfusedOrExistingTargetsOverwritten() throws Exception {
        Path database = database("atv.mv.db");
        Path archive = directory.resolve("embedded.zip");
        var backup = DatabaseArchiveTool.backup(database, archive, "", DEPLOYMENT);
        String trusted = backup.path("archiveSha256").asText();
        assertEquals("ARCHIVE_ENTRIES_INVALID", assertThrows(DatabaseArchiveTool.ArchiveFailure.class,
                () -> DatabaseArchiveTool.inspect(archive, trusted)).getMessage());
        assertEquals("OWNED_MAINTENANCE_DATABASE_REQUIRED", assertThrows(DatabaseArchiveTool.ArchiveFailure.class,
                () -> DatabaseArchiveTool.backup(database, directory.resolve("standalone.zip"), "")).getMessage());
        Path target = directory.resolve("existing");
        Files.createDirectory(target);
        Path keep = target.resolve("keep");
        Files.writeString(keep, "unchanged");
        assertEquals("ARCHIVE_TARGET_MUST_NOT_EXIST", assertThrows(DatabaseArchiveTool.ArchiveFailure.class,
                () -> DatabaseArchiveTool.restore(archive, target, trusted, "", DEPLOYMENT)).getMessage());
        assertEquals("unchanged", Files.readString(keep));
        assertEquals("ARCHIVE_IDENTITY_MISMATCH", assertThrows(DatabaseArchiveTool.ArchiveFailure.class,
                () -> DatabaseArchiveTool.restore(archive, directory.resolve("not-created"),
                        "0".repeat(64), "", DEPLOYMENT)).getMessage());
        assertFalse(Files.exists(directory.resolve("not-created")));
    }

    @Test
    void nonEmbeddedHostSchemaDoesNotCreateAnArchive() throws Exception {
        Path foreign = directory.resolve("foreign.mv.db");
        try (var connection = DriverManager.getConnection(url(foreign), "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE plugin(id INT)");
        }
        Path archive = directory.resolve("rejected.zip");
        assertEquals("OWNED_AT_EMBEDDED_SCHEMA_REQUIRED",
                assertThrows(DatabaseArchiveTool.ArchiveFailure.class,
                        () -> DatabaseArchiveTool.backup(foreign, archive, "", DEPLOYMENT)).getMessage());
        assertFalse(Files.exists(archive));
    }

    @Test
    void runningDatabaseIsNotAcceptedAsAColdSnapshot() throws Exception {
        Path database = database("atv.mv.db");
        try (var connection = DriverManager.getConnection(url(database), "sa", "")) {
            Path archive = directory.resolve("locked.zip");
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), EmbeddedDatabaseArchiveTool.class.getName(),
                    "backup", "--database", database.toString(), "--output", archive.toString())
                    .start();
            try {
                assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(1, process.exitValue());
                String errorOutput = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                String json = errorOutput.lines().filter(line -> !line.startsWith("Picked up JAVA_TOOL_OPTIONS:"))
                        .collect(java.util.stream.Collectors.joining("\n"));
                var output = new ObjectMapper().readTree(json);
                assertEquals("DATABASE_IN_USE", output.path("code").asText());
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
                }
            }
            assertFalse(Files.exists(archive));
        }
    }

    private Path database(String filename) {
        Path database = directory.resolve(filename);
        new EmbeddedMaintenanceMigrations().migrate(Flyway.configure().dataSource(url(database), "sa", "")
                .locations("classpath:db/migration/h2", "classpath:db/migration/common",
                        "classpath:db/migration/current").load());
        return database;
    }

    private static String url(Path file) {
        return "jdbc:h2:file:" + file.toAbsolutePath().toString().replace('\\', '/')
                .replace(".mv.db", "") + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
    }

    private static void seed(Path file) throws Exception {
        String digest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(BODY.getBytes(StandardCharsets.UTF_8)));
        try (var connection = DriverManager.getConnection(url(file), "sa", "");
             var statement = connection.createStatement()) {
            try (var insert = connection.prepareStatement(
                    "INSERT INTO plugin(id,enabled,sort_order,content,url) VALUES(501,true,1,?,'https://example.invalid/source.py')")) {
                insert.setString(1, BODY);
                insert.executeUpdate();
            }
            statement.execute("INSERT INTO maintenance_source(id,name,filename,created_at) "
                    + "VALUES('source-1','fixture','source.py',CURRENT_TIMESTAMP)");
            try (var insert = connection.prepareStatement("INSERT INTO maintenance_artifact"
                    + "(id,source_id,sha256,body,created_at) VALUES('artifact-1','source-1',?,?,CURRENT_TIMESTAMP)")) {
                insert.setString(1, digest);
                insert.setString(2, BODY);
                insert.executeUpdate();
            }
            statement.execute("INSERT INTO maintenance_probe_run(id,source_id,artifact_id,artifact_sha256,"
                    + "inspector_sha256,knowledge_sha256,status,created_at) VALUES('run-1','source-1','artifact-1',"
                    + "'" + digest + "','" + digest + "','" + digest + "','COMPLETED',CURRENT_TIMESTAMP)");
            statement.execute("INSERT INTO maintenance_repair(id,source_id,baseline_run_id,baseline_artifact_sha256,"
                    + "fault_sha256,frozen_json,status,created_at) VALUES('repair-1','source-1','run-1',"
                    + "'" + digest + "','" + digest + "','{}','RUNTIME_OBSERVED',CURRENT_TIMESTAMP)");
            statement.execute("INSERT INTO maintenance_model_call(id,repair_id,request_key,attempt_number,"
                    + "configuration_sha256,model_name,request_sha256,request_json,request_bytes,"
                    + "reserved_output_tokens,status,created_at) VALUES('call-1','repair-1','fixture-call',1,"
                    + "'" + digest + "','fixture','" + digest + "','{}',2,10,'RUNNING',CURRENT_TIMESTAMP)");
            try (var insert = connection.prepareStatement("INSERT INTO maintenance_repair_candidate"
                    + "(id,repair_id,round_number,sha256,body,proposal_sha256,proposal_json,status,created_at)"
                    + " VALUES('candidate-1','repair-1',1,?,?,?,'{}','RUNTIME_OBSERVED',CURRENT_TIMESTAMP)")) {
                insert.setString(1, digest);
                insert.setString(2, CANDIDATE);
                insert.setString(3, digest);
                insert.executeUpdate();
            }
            try (var insert = connection.prepareStatement("INSERT INTO maintenance_release_plan"
                    + "(id,source_id,candidate_id,request_key,request_sha256,baseline_artifact_sha256,"
                    + "candidate_sha256,frozen_sha256,frozen_json,status,created_at,local_receipt_json)"
                    + " VALUES('plan-1','source-1','candidate-1','fixture-plan',?,?,?,?,'{}','LOCAL_APPLIED',CURRENT_TIMESTAMP,?)")) {
                for (int index = 1; index <= 4; index++) insert.setString(index, digest);
                insert.setString(5, LOCAL_RECEIPT);
                insert.executeUpdate();
            }
            statement.execute("UPDATE maintenance_model_budget SET used_calls=3,active_call_id='call-1'");
            statement.execute("INSERT INTO maintenance_probe_schedule(source_id,sha256,policy_json,enabled,next_due_at,"
                    + "budget_day,used_attempts) VALUES('source-1','" + digest + "','{}',true,CURRENT_TIMESTAMP,CURRENT_DATE,2)");
        }
    }
}
