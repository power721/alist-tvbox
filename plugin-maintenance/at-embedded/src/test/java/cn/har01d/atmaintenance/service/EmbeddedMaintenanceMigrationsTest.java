package cn.har01d.atmaintenance.service;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddedMaintenanceMigrationsTest {
    @Test
    void hostAndMaintenanceCanBothOwnVersion59AndRestart() throws Exception {
        String url = "jdbc:h2:mem:separate_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        var host = Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:db/migration/h2", "classpath:db/migration/common",
                        "classpath:db/migration/current")
                .javaMigrations(new V59_1__HostNamespaceFixture()).load();
        var strategy = new EmbeddedMaintenanceMigrations();
        strategy.migrate(host);
        strategy.migrate(host);
        assertEquals("59.1", host.info().current().getVersion().toString());
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            var rows = statement.executeQuery("SELECT COUNT(*) FROM \"maintenance_schema_history\" "
                    + "WHERE \"script\"='V59__PluginMaintenance.sql' AND \"success\"=TRUE");
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
            assertTrue(connection.getMetaData().getTables(null, null, "MAINTENANCE_SOURCE", null).next());
            assertTrue(connection.getMetaData().getTables(null, null, "HOST_NAMESPACE_FIXTURE", null).next());
        }
    }

    static class V59_1__HostNamespaceFixture extends BaseJavaMigration {
        @Override public void migrate(Context context) throws Exception {
            try (var statement = context.getConnection().createStatement()) {
                statement.execute("CREATE TABLE HOST_NAMESPACE_FIXTURE(ID INT)");
            }
        }
    }

    @Test
    void legacyPrivateRcHistoryIsRejectedWithoutRewritingIt() throws Exception {
        String url = "jdbc:h2:mem:legacy_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        var host = Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration/h2").load();
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE \"flyway_schema_history\"(\"script\" VARCHAR(200))");
            statement.execute("INSERT INTO \"flyway_schema_history\" VALUES('V59__PluginMaintenance.sql')");
            assertEquals("MAINTENANCE_LEGACY_SCHEMA_REQUIRES_EXPORT", assertThrows(IllegalStateException.class,
                    () -> new EmbeddedMaintenanceMigrations().migrate(host)).getMessage());
            var rows = statement.executeQuery("SELECT COUNT(*) FROM \"flyway_schema_history\"");
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
            assertFalse(connection.getMetaData().getTables(null, null, "maintenance_schema_history", null).next());
        }
    }
}
