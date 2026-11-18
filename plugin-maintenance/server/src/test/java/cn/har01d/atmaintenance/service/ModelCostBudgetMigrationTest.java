package cn.har01d.atmaintenance.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelCostBudgetMigrationTest {
    @TempDir Path temporary;

    @Test
    void v16AddsNullableAmountColumnsWithoutInventingLegacyReservations() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID(), "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE maintenance_model_budget(id INTEGER PRIMARY KEY)");
            statement.execute("CREATE TABLE maintenance_model_source_budget(source_id VARCHAR(36) PRIMARY KEY)");
            statement.execute("CREATE TABLE maintenance_model_named_budget(model_name VARCHAR(120) PRIMARY KEY)");
            statement.execute("INSERT INTO maintenance_model_budget(id) VALUES(1)");
            statement.execute("INSERT INTO maintenance_model_source_budget(source_id) VALUES('source')");
            statement.execute("INSERT INTO maintenance_model_named_budget(model_name) VALUES('model')");
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/h2/V16__ModelCostBudgets.sql"));
            try (var columns = statement.executeQuery(
                    "SELECT TABLE_NAME, COLUMN_NAME, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                            + "WHERE COLUMN_NAME='RESERVED_COST_USD' ORDER BY TABLE_NAME")) {
                List<String> tables = new ArrayList<>();
                while (columns.next()) {
                    tables.add(columns.getString(1) + ":" + columns.getString(2) + ":" + columns.getString(3));
                }
                assertEquals(List.of(
                        "MAINTENANCE_MODEL_BUDGET:RESERVED_COST_USD:YES",
                        "MAINTENANCE_MODEL_NAMED_BUDGET:RESERVED_COST_USD:YES",
                        "MAINTENANCE_MODEL_SOURCE_BUDGET:RESERVED_COST_USD:YES"), tables);
            }
            try (var rows = statement.executeQuery(
                    "SELECT b.reserved_cost_usd, s.reserved_cost_usd, n.reserved_cost_usd, b.cost_breach_day "
                            + "FROM maintenance_model_budget b, maintenance_model_source_budget s, "
                            + "maintenance_model_named_budget n")) {
                assertTrue(rows.next());
                assertNull(rows.getBigDecimal(1));
                assertNull(rows.getBigDecimal(2));
                assertNull(rows.getBigDecimal(3));
                assertNull(rows.getDate(4));
            }
        }
    }

    @Test
    void preciseAmountsAndBreachDaySurviveFileDatabaseReopen() throws Exception {
        String url = "jdbc:h2:file:" + temporary.resolve("cost-ledger").toAbsolutePath().toString().replace('\\', '/');
        String precise = "1000000000000.000000000000000001";
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE maintenance_model_budget(id INTEGER PRIMARY KEY)");
            statement.execute("CREATE TABLE maintenance_model_source_budget(source_id VARCHAR(36) PRIMARY KEY)");
            statement.execute("CREATE TABLE maintenance_model_named_budget(model_name VARCHAR(120) PRIMARY KEY)");
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/h2/V16__ModelCostBudgets.sql"));
            statement.execute("INSERT INTO maintenance_model_budget VALUES(1," + precise + ",DATE '2030-01-02')");
        }
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT reserved_cost_usd, cost_breach_day FROM maintenance_model_budget")) {
            assertTrue(row.next());
            assertEquals(new BigDecimal(precise), row.getBigDecimal(1));
            assertEquals("2030-01-02", row.getDate(2).toString());
        }
    }
}
