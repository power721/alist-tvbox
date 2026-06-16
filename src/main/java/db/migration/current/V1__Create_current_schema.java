package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.stream.Collectors;

/**
 * Smart V1 migration that detects whether this is a fresh install or an upgrade.
 * - Fresh install: use V1_new_install.sql (no backticks, modern column names)
 * - Upgrade: use V1_old_upgrade.sql (with backticks, old column names, V3 will rename)
 */
public class V1__Create_current_schema extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        // Check if this is a fresh install by looking for any existing tables
        boolean isFreshInstall = isEmptySchema(connection);

        String sqlFile;
        if (isFreshInstall) {
            sqlFile = "/db/migration/current/V1_new_install.sql";
            System.out.println("V1: Fresh install detected, using new schema with modern column names");
        } else {
            sqlFile = "/db/migration/current/V1_old_upgrade.sql";
            System.out.println("V1: Existing data detected, using old schema for compatibility");
        }

        // Load and execute the SQL file
        String sql = loadResourceFile(sqlFile);
        executeSql(connection, sql);
    }

    private boolean isEmptySchema(Connection connection) throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet tables = metaData.getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String tableName = tables.getString("TABLE_NAME");
                // Ignore Flyway's own table
                if (!tableName.equalsIgnoreCase("flyway_schema_history")) {
                    return false;
                }
            }
        }
        return true;
    }

    private String loadResourceFile(String path) throws Exception {
        try (InputStream is = getClass().getResourceAsStream(path);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    private void executeSql(Connection connection, String sql) throws Exception {
        // Split by semicolon and execute each statement
        String[] statements = sql.split(";");
        try (Statement stmt = connection.createStatement()) {
            for (String statement : statements) {
                String trimmed = statement.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
                    stmt.execute(trimmed);
                }
            }
        }
    }
}
