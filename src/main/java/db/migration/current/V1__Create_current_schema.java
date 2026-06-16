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

        // Detect database type
        String dbProduct = connection.getMetaData().getDatabaseProductName().toLowerCase();
        boolean isH2 = dbProduct.contains("h2");
        boolean isMySql = dbProduct.contains("mysql");

        // Check if this is a fresh install
        boolean isFreshInstall = isEmptySchema(connection);

        String sqlFile;
        if (isFreshInstall) {
            if (isH2) {
                // For H2 fresh install: use old schema with backticks
                // H2 will store as uppercase, and we'll rely on case-insensitive matching
                sqlFile = "/db/migration/current/V1_old_upgrade.sql";
                System.out.println("V1: H2 fresh install - using schema with backticks (V3 will rename)");
            } else {
                // For MySQL fresh install: use new schema with correct names
                sqlFile = "/db/migration/current/V1_new_install.sql";
                System.out.println("V1: MySQL fresh install - using new schema with modern column names");
            }
        } else {
            // Upgrade path (though V1 never re-runs)
            sqlFile = "/db/migration/current/V1_old_upgrade.sql";
            System.out.println("V1: Existing data detected, using old schema for compatibility");
        }

        // Load and execute the SQL file
        String sql = loadResourceFile(sqlFile);
        executeSql(connection, sql);
    }

    private boolean isEmptySchema(Connection connection) throws Exception {
        // Check if any of our application tables exist
        // These are tables that V1 creates, so if they exist, V1 was already run with old schema
        String[] appTables = {"account", "movie", "meta", "history", "setting"};

        DatabaseMetaData metaData = connection.getMetaData();
        String schema = connection.getSchema();

        for (String tableName : appTables) {
            try (ResultSet tables = metaData.getTables(connection.getCatalog(), schema, tableName, new String[]{"TABLE"})) {
                if (tables.next()) {
                    System.out.println("V1: Found existing application table: " + tableName);
                    return false;
                }
            }
            // Also try uppercase (H2 stores uppercase)
            try (ResultSet tables = metaData.getTables(connection.getCatalog(), schema, tableName.toUpperCase(), new String[]{"TABLE"})) {
                if (tables.next()) {
                    System.out.println("V1: Found existing application table: " + tableName.toUpperCase());
                    return false;
                }
            }
        }

        System.out.println("V1: No application tables found - fresh install");
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
