package cn.har01d.atmaintenance.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class LegacyMaintenanceExportTest {
    @TempDir Path directory;

    @Test void copiedDatabaseSeparatesHistoriesAndPreservesSourceAndBusinessRows() throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("original").toString().replace('\\', '/');
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/maintenance/h2").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE CANARY(ID INT PRIMARY KEY, BODY CLOB)");
            statement.execute("INSERT INTO CANARY VALUES(1, 'private fixture')");
        }
        Path source = directory.resolve("original.mv.db"), target = directory.resolve("exported.mv.db");
        byte[] original = Files.readAllBytes(source);
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
        assertEquals(64, LegacyMaintenanceExport.export(source, target, expected, "sa", "").length());
        assertArrayEquals(original, Files.readAllBytes(source));
        String exported = "jdbc:h2:file:" + directory.resolve("exported").toString().replace('\\', '/');
        try (var connection = DriverManager.getConnection(exported, "sa", ""); var statement = connection.createStatement()) {
            var rows = statement.executeQuery("SELECT BODY FROM CANARY");
            assertTrue(rows.next());
            assertEquals("private fixture", rows.getString(1));
            rows = statement.executeQuery("SELECT COUNT(*) FROM \"legacy_maintenance_schema_history\"");
            assertTrue(rows.next()); assertEquals(17, rows.getInt(1));
            rows = statement.executeQuery("SELECT COUNT(*) FROM \"maintenance_schema_history\"");
            assertTrue(rows.next()); assertEquals(16, rows.getInt(1));
            rows = statement.executeQuery("SELECT COUNT(*) FROM \"flyway_schema_history\"");
            assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
        }
        assertThrows(IllegalArgumentException.class, () -> LegacyMaintenanceExport.export(source, target, expected, "sa", ""));
        assertThrows(IllegalArgumentException.class, () -> LegacyMaintenanceExport.export(source,
                directory.resolve("wrong.mv.db"), "0".repeat(64), "sa", ""));
        assertFalse(Files.exists(directory.resolve("wrong.mv.db")));
    }
}
