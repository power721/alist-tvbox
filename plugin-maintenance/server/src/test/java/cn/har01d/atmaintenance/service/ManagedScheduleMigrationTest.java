package cn.har01d.atmaintenance.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class ManagedScheduleMigrationTest {
    @TempDir Path directory;

    @Test
    void v21UpgradeDoesNotInventApprovalForHistoricalRunsAndPreservesRevisionsOnColdReopen() throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("managed-schedule").toString().replace('\\', '/')
                + ";WRITE_DELAY=0";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration/h2").target("21").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            sql.execute("SET REFERENTIAL_INTEGRITY FALSE");
            sql.execute("""
                    INSERT INTO maintenance_probe_run(id, source_id, artifact_id, artifact_sha256,
                        inspector_sha256, knowledge_sha256, status, created_at, execution_mode)
                    VALUES ('historical', 'source', 'artifact', REPEAT('a',64), REPEAT('b',64), REPEAT('c',64),
                        'COMPLETED', CURRENT_TIMESTAMP, 'managed_source')
                    """);
        }
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration/h2").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("SELECT source_revision FROM maintenance_probe_run WHERE id='historical'")) {
                assertTrue(rows.next());
                assertNull(rows.getObject(1), "Migration must not certify historical source revision");
            }
            sql.execute("UPDATE maintenance_probe_run SET source_revision=7 WHERE id='historical'");
        }
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement();
             var rows = sql.executeQuery("SELECT source_revision, execution_mode FROM maintenance_probe_run WHERE id='historical'")) {
            assertTrue(rows.next());
            assertEquals(7, rows.getLong(1));
            assertEquals("managed_source", rows.getString(2));
        }
    }
}
