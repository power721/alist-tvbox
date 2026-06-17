package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

public class V3__Fix_null_sort_order extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        // Fix NULL values in sort_order columns
        execute(connection, "UPDATE site SET sort_order = 0 WHERE sort_order IS NULL");
        execute(connection, "UPDATE navigation SET sort_order = 0 WHERE sort_order IS NULL");
        execute(connection, "UPDATE emby SET sort_order = 0 WHERE sort_order IS NULL");
        execute(connection, "UPDATE jellyfin SET sort_order = 0 WHERE sort_order IS NULL");
        execute(connection, "UPDATE telegram_channel SET sort_order = 0 WHERE sort_order IS NULL");
        execute(connection, "UPDATE feiniu SET sort_order = 0 WHERE sort_order IS NULL");
        execute(connection, "UPDATE plugin SET sort_order = 0 WHERE sort_order IS NULL");
        execute(connection, "UPDATE plugin_filter SET sort_order = 0 WHERE sort_order IS NULL");

        // Migrate old column names to new ones (for very old users)
        migrateSettingValue(connection);

        // Normalize case for columns that were created with backticks
        normalizeCaseSensitiveColumns(connection);
    }

    private void normalizeCaseSensitiveColumns(Connection connection) {
        // Rename lowercase columns to uppercase for H2 compatibility
        // All columns (including reserved words) need to be uppercase for consistent access
        renameIfExists(connection, "setting", "svalue", "SVALUE");
        renameIfExists(connection, "navigation", "value", "VALUE");
        renameIfExists(connection, "history", "key", "KEY");
        renameIfExists(connection, "movie", "year", "YEAR");
        renameIfExists(connection, "meta", "year", "YEAR");
        renameIfExists(connection, "tmdb_meta", "year", "YEAR");
        renameIfExists(connection, "tmdb", "year", "YEAR");
        renameIfExists(connection, "plugin", "extend", "EXTEND");
        renameIfExists(connection, "plugin", "version", "VERSION");
        renameIfExists(connection, "plugin_filter", "extend", "EXTEND");
        renameIfExists(connection, "plugin_filter", "version", "VERSION");
    }

    private void renameIfExists(Connection connection, String table, String fromCol, String toCol) {
        try {
            String actualTable = findTable(connection, table);
            if (actualTable == null) {
                return;
            }

            String existing = findColumn(connection, actualTable, fromCol);
            String target = findColumn(connection, actualTable, toCol);

            if (existing != null && target == null) {
                // Lowercase exists, uppercase doesn't - rename it
                String sql = "ALTER TABLE " + actualTable + " ALTER COLUMN \"" + fromCol + "\" RENAME TO " + toCol;
                System.out.println("V3: Renaming " + table + "." + fromCol + " to " + toCol);
                execute(connection, sql);
            }
        } catch (Exception e) {
            System.err.println("V3: Error renaming " + table + "." + fromCol + ": " + e.getMessage());
        }
    }

    private void migrateSettingValue(Connection connection) {
        try {
            String actualTable = findTable(connection, "setting");
            if (actualTable == null) {
                System.out.println("V3: setting table not found");
                return;
            }

            // List all columns to debug
            System.out.println("V3: Columns in setting table:");
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet rs = metaData.getColumns(connection.getCatalog(), null, actualTable, null)) {
                while (rs.next()) {
                    System.out.println("  - " + rs.getString("COLUMN_NAME"));
                }
            }

            // Check all possible old column names
            String oldCol = findColumn(connection, actualTable, "setting_value");
            if (oldCol == null) {
                oldCol = findColumn(connection, actualTable, "SETTING_VALUE");
            }

            String newCol = findColumn(connection, actualTable, "svalue");
            if (newCol == null) {
                newCol = findColumn(connection, actualTable, "SVALUE");
            }

            System.out.println("V3: old column: " + oldCol + ", new column: " + newCol);

            if (oldCol != null && newCol == null) {
                // Add new column
                execute(connection, "ALTER TABLE " + actualTable + " ADD COLUMN SVALUE TEXT");
                // Copy data - need to quote the old column name
                String copySQL = "UPDATE " + actualTable + " SET SVALUE = \"" + oldCol + "\"";
                System.out.println("V3: Executing: " + copySQL);
                execute(connection, copySQL);
                // Drop old column
                String dropSQL = "ALTER TABLE " + actualTable + " DROP COLUMN \"" + oldCol + "\"";
                System.out.println("V3: Executing: " + dropSQL);
                execute(connection, dropSQL);
                System.out.println("V3: Successfully migrated setting_value to svalue");
            } else if (oldCol != null && newCol != null) {
                // Both exist, just drop the old one
                execute(connection, "ALTER TABLE " + actualTable + " DROP COLUMN \"" + oldCol + "\"");
                System.out.println("V3: Dropped duplicate column: " + oldCol);
            } else if (oldCol == null && newCol == null) {
                System.err.println("V3: WARNING - No svalue or setting_value column found!");
            } else {
                System.out.println("V3: Column already correct: " + newCol);
            }
        } catch (Exception e) {
            System.err.println("V3: Error migrating setting_value: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String findTable(Connection connection, String table) throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getTables(connection.getCatalog(), null, null, null)) {
            while (resultSet.next()) {
                if (resultSet.getString("TABLE_NAME").equalsIgnoreCase(table)) {
                    return resultSet.getString("TABLE_NAME");
                }
            }
        }
        return null;
    }

    private String findColumn(Connection connection, String table, String column) throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getColumns(connection.getCatalog(), null, table, null)) {
            while (resultSet.next()) {
                String colName = resultSet.getString("COLUMN_NAME");
                // Exact match, case-sensitive
                if (colName.equals(column)) {
                    return colName;
                }
            }
        }
        return null;
    }

    private void execute(Connection connection, String sql) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception e) {
            // Silently ignore - table/column may not exist or already clean
        }
    }
}
