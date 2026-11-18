package cn.har01d.atmaintenance.service;

import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;

import java.sql.SQLException;

/** Keep optional maintenance migrations outside the host's version namespace. */
public final class EmbeddedMaintenanceMigrations implements FlywayMigrationStrategy {
    @Override
    public void migrate(Flyway host) {
        var dataSource = host.getConfiguration().getDataSource();
        try (var connection = dataSource.getConnection()) {
            if (connection.getMetaData().getTables(null, null, "flyway_schema_history", null).next()) {
                try (var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT \"script\" FROM \"flyway_schema_history\"")) {
                    while (rows.next()) {
                        if ("V59__PluginMaintenance.sql".equals(rows.getString(1))) {
                            throw new IllegalStateException("MAINTENANCE_LEGACY_SCHEMA_REQUIRES_EXPORT");
                        }
                    }
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("MAINTENANCE_SCHEMA_INSPECTION_FAILED", error);
        }
        host.migrate();
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/maintenance/h2")
                .table("maintenance_schema_history")
                .baselineOnMigrate(true).baselineVersion("0")
                .load().migrate();
    }
}
