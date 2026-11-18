package cn.har01d.atmaintenance.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class LocalReleaseMigrationTest {
    @TempDir Path directory;

    @Test
    void v20UpgradePreservesPendingAndCancelledPlansAndLocalReceiptsSurviveColdReopen() throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("local-release").toAbsolutePath().toString().replace('\\', '/')
                + ";WRITE_DELAY=0";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration/h2").target("20").load().migrate();
        // Synthetic records with referential integrity disabled exercise the real migration, not source execution.
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            sql.execute("SET REFERENTIAL_INTEGRITY FALSE");
            sql.execute("""
                    INSERT INTO maintenance_release_plan(id, source_id, candidate_id, request_key, request_sha256,
                        baseline_artifact_sha256, candidate_sha256, frozen_sha256, frozen_json, status,
                        active_source_id, created_at, cancelled_at)
                    VALUES ('pending', 'source', 'candidate', 'pending-key', REPEAT('a',64), REPEAT('b',64),
                        REPEAT('c',64), REPEAT('d',64), '{}', 'AWAITING_RUNTIME_VALIDATION', 'source', CURRENT_TIMESTAMP, NULL),
                        ('cancelled', 'source', 'candidate', 'cancelled-key', REPEAT('a',64), REPEAT('b',64),
                        REPEAT('c',64), REPEAT('d',64), '{}', 'CANCELLED', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
        }
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration/h2").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM maintenance_release_plan WHERE local_receipt_json IS NULL")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
            }
            sql.execute("""
                    UPDATE maintenance_release_plan SET status = 'LOCAL_APPLIED', active_source_id = NULL,
                        local_receipt_json = '{"apply":{"beforeRevision":4,"afterRevision":5},"productionModified":false}'
                    WHERE id = 'pending'
                    """);
        }
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement();
             var rows = sql.executeQuery("SELECT status, local_receipt_json, active_source_id FROM maintenance_release_plan WHERE id='pending'")) {
            assertTrue(rows.next());
            assertEquals("LOCAL_APPLIED", rows.getString(1));
            assertTrue(rows.getString(2).contains("\"afterRevision\":5"));
            assertNull(rows.getString(3));
        }
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            sql.execute("""
                    UPDATE maintenance_release_plan SET status = 'LOCAL_ROLLED_BACK',
                        local_receipt_json = '{"apply":{"afterRevision":5},"rollback":{"afterRevision":6}}'
                    WHERE id = 'pending'
                    """);
            assertThrows(java.sql.SQLException.class,
                    () -> sql.execute("UPDATE maintenance_release_plan SET status = 'DEPLOYED' WHERE id='pending'"));
        }
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement();
             var rows = sql.executeQuery("SELECT status, local_receipt_json FROM maintenance_release_plan ORDER BY id")) {
            assertTrue(rows.next());
            assertEquals("CANCELLED", rows.getString(1));
            assertNull(rows.getString(2));
            assertTrue(rows.next());
            assertEquals("LOCAL_ROLLED_BACK", rows.getString(1));
            assertTrue(rows.getString(2).contains("\"rollback\""));
        }
    }
}
