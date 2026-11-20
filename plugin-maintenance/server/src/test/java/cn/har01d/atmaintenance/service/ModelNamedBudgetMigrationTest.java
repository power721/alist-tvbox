package cn.har01d.atmaintenance.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ModelNamedBudgetMigrationTest {
    @Test
    void exactMigrationGroupsAllOutcomesByCaseSensitiveNameAndUtcDayWithoutRewritingSnapshots() throws Exception {
        for (String zone : List.of("UTC", "Asia/Macau")) {
            try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID(), "sa", "");
                 var statement = connection.createStatement()) {
                statement.execute("SET TIME ZONE '" + zone + "'");
                statement.execute("CREATE TABLE maintenance_model_budget(id INT PRIMARY KEY, budget_day DATE)");
                statement.execute("CREATE TABLE maintenance_model_call(id INT PRIMARY KEY, model_name VARCHAR(120), "
                        + "request_bytes INT, reserved_output_tokens INT, created_at TIMESTAMP WITH TIME ZONE, "
                        + "status VARCHAR(40), budget_json TEXT)");
                statement.execute("INSERT INTO maintenance_model_budget VALUES (1, DATE '2026-11-07')");
                statement.execute("INSERT INTO maintenance_model_call VALUES "
                        + "(1,'model-A',100,128,TIMESTAMP WITH TIME ZONE '2026-11-07 00:00:00+00','FAILED','old-snapshot'),"
                        + "(2,'model-A',200,256,TIMESTAMP WITH TIME ZONE '2026-11-08 07:59:59+08','INTERRUPTED',NULL),"
                        + "(3,'model-A',400,512,TIMESTAMP WITH TIME ZONE '2026-11-07 07:59:59+08','PROPOSED',NULL),"
                        + "(4,'model-A',800,1024,TIMESTAMP WITH TIME ZONE '2026-11-08 08:00:00+08','CANCELLED',NULL),"
                        + "(5,'model-a',50,64,TIMESTAMP WITH TIME ZONE '2026-11-07 12:00:00+00','CANCELLED',NULL)");
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("db/migration/h2/V14__ModelNamedBudgets.sql"));
                try (var result = statement.executeQuery(
                        "SELECT model_name,budget_day,used_calls,reserved_input_bytes,reserved_output_tokens "
                                + "FROM maintenance_model_named_budget ORDER BY model_name")) {
                    assertTrue(result.next());
                    assertEquals("model-A", result.getString(1));
                    assertEquals("2026-11-07", result.getDate(2).toString());
                    assertEquals(2, result.getInt(3));
                    assertEquals(300, result.getLong(4));
                    assertEquals(384, result.getLong(5));
                    assertTrue(result.next());
                    assertEquals("model-a", result.getString(1));
                    assertEquals(1, result.getInt(3));
                    assertEquals(50, result.getLong(4));
                    assertEquals(64, result.getLong(5));
                    assertFalse(result.next());
                }
                try (var result = statement.executeQuery("SELECT budget_json FROM maintenance_model_call ORDER BY id")) {
                    assertTrue(result.next());
                    assertEquals("old-snapshot", result.getString(1));
                    for (int i = 0; i < 4; i++) {
                        assertTrue(result.next());
                        assertNull(result.getString(1));
                    }
                    assertFalse(result.next());
                }
            }
        }
    }

    @Test
    void uninitializedGlobalDayDoesNotInventHistoricalModelBudget() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID(), "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE maintenance_model_budget(id INT PRIMARY KEY, budget_day DATE)");
            statement.execute("CREATE TABLE maintenance_model_call(model_name VARCHAR(120), request_bytes INT, "
                    + "reserved_output_tokens INT, created_at TIMESTAMP WITH TIME ZONE)");
            statement.execute("INSERT INTO maintenance_model_budget VALUES (1,NULL)");
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/h2/V14__ModelNamedBudgets.sql"));
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM maintenance_model_named_budget")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
        }
    }
}
