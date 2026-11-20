package cn.har01d.atmaintenance.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.DriverManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelUsageMigrationTest {
    @Test
    void migrationAddsUnknownCostWithoutRewritingLegacyUsageSnapshotsOrUtcTimestamps() throws Exception {
        for (String zone : List.of("UTC", "Asia/Macau")) {
            try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID(), "sa", "");
                 var statement = connection.createStatement()) {
                statement.execute("SET TIME ZONE '" + zone + "'");
                statement.execute("CREATE TABLE maintenance_model_call(id INT PRIMARY KEY, model_name VARCHAR(120), "
                        + "created_at TIMESTAMP WITH TIME ZONE, usage_json TEXT, budget_json TEXT)");
                statement.execute("INSERT INTO maintenance_model_call VALUES "
                        + "(1,'model-A',TIMESTAMP WITH TIME ZONE '2026-11-08 07:59:59+08',"
                        + "'{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}', 'old-snapshot'),"
                        + "(2,'model-A',TIMESTAMP WITH TIME ZONE '2026-11-08 08:00:00+08', NULL, NULL)");
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("db/migration/h2/V15__ModelUsageCosts.sql"));
                try (var rows = statement.executeQuery("SELECT usage_json,budget_json,cost_json,"
                        + "created_at AT TIME ZONE 'UTC' FROM maintenance_model_call ORDER BY id")) {
                    assertTrue(rows.next());
                    assertEquals("{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}", rows.getString(1));
                    assertEquals("old-snapshot", rows.getString(2));
                    assertNull(rows.getString(3));
                    assertEquals("2026-11-07T23:59:59Z", rows.getObject(4, java.time.OffsetDateTime.class).toString());
                    assertTrue(rows.next());
                    assertNull(rows.getString(1));
                    assertNull(rows.getString(2));
                    assertNull(rows.getString(3));
                    assertFalse(rows.next());
                }
            }
        }
    }
}
