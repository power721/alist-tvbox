package cn.har01d.atmaintenance.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ModelSourceBudgetMigrationTest {
    @Test
    void exactMigrationSelectsUtcDayInBothUtcAndPositiveOffsetSessions() throws Exception {
        for (String zone : List.of("UTC", "Asia/Macau")) {
            try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID(), "sa", "");
                 var statement = connection.createStatement()) {
                statement.execute("SET TIME ZONE '" + zone + "'");
                // Minimal V9 table fixture; the JAR file smoke covers the full V9 schema.
                statement.execute("CREATE TABLE maintenance_source(id VARCHAR(36) PRIMARY KEY)");
                statement.execute("CREATE TABLE maintenance_repair(id VARCHAR(36) PRIMARY KEY, source_id VARCHAR(36))");
                statement.execute("CREATE TABLE maintenance_model_budget(id INT PRIMARY KEY, budget_day DATE)");
                statement.execute("CREATE TABLE maintenance_model_call(id INT PRIMARY KEY, repair_id VARCHAR(36), "
                        + "request_bytes INT, reserved_output_tokens INT, created_at TIMESTAMP WITH TIME ZONE)");
                statement.execute("INSERT INTO maintenance_source VALUES ('A'),('B')");
                statement.execute("INSERT INTO maintenance_repair VALUES ('r1','A'),('r2','A'),('r3','B')");
                statement.execute("INSERT INTO maintenance_model_budget VALUES (1, DATE '2026-11-07')");
                statement.execute("INSERT INTO maintenance_model_call VALUES "
                        + "(1,'r1',100,128,TIMESTAMP WITH TIME ZONE '2026-11-07 00:00:00+00'),"
                        + "(2,'r2',200,256,TIMESTAMP WITH TIME ZONE '2026-11-08 07:59:59+08'),"
                        + "(3,'r1',400,512,TIMESTAMP WITH TIME ZONE '2026-11-07 07:59:59+08'),"
                        + "(4,'r2',800,1024,TIMESTAMP WITH TIME ZONE '2026-11-08 08:00:00+08'),"
                        + "(5,'r3',50,64,TIMESTAMP WITH TIME ZONE '2026-11-07 12:00:00+00')");
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("db/migration/h2/V10__ModelSourceBudgets.sql"));
                try (var result = statement.executeQuery(
                        "SELECT source_id,budget_day,used_calls,reserved_input_bytes,reserved_output_tokens "
                                + "FROM maintenance_model_source_budget ORDER BY source_id")) {
                    assertTrue(result.next());
                    assertEquals("A", result.getString(1));
                    assertEquals("2026-11-07", result.getDate(2).toString());
                    assertEquals(2, result.getInt(3));
                    assertEquals(300, result.getLong(4));
                    assertEquals(384, result.getLong(5));
                    assertTrue(result.next());
                    assertEquals("B", result.getString(1));
                    assertEquals(1, result.getInt(3));
                    assertEquals(50, result.getLong(4));
                    assertEquals(64, result.getLong(5));
                    assertFalse(result.next());
                }
                try (var result = statement.executeQuery("SELECT COUNT(*) FROM maintenance_model_call WHERE budget_json IS NULL")) {
                    assertTrue(result.next());
                    assertEquals(5, result.getInt(1));
                }
            }
        }
    }
}
