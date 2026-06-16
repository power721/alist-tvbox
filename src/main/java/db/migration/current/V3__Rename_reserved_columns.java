package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Rename columns that use SQL reserved keywords or unclear names
 * to avoid quoting issues and improve readability
 */
public class V3__Rename_reserved_columns extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        // History table: key -> item_key
        renameColumn(connection, "history", "key", "item_key");
        verifyColumnExists(connection, "history", "item_key");

        // Navigation table: value -> nav_value
        renameColumn(connection, "navigation", "value", "nav_value");
        verifyColumnExists(connection, "navigation", "nav_value");

        // Movie table: year -> release_year
        renameColumn(connection, "movie", "year", "release_year");
        verifyColumnExists(connection, "movie", "release_year");

        // Tmdb table: year -> release_year
        renameColumn(connection, "tmdb", "year", "release_year");
        verifyColumnExists(connection, "tmdb", "release_year");

        // Meta table: year -> release_year
        renameColumn(connection, "meta", "year", "release_year");
        verifyColumnExists(connection, "meta", "release_year");

        // Tmdb_meta table: year -> release_year
        renameColumn(connection, "tmdb_meta", "year", "release_year");
        verifyColumnExists(connection, "tmdb_meta", "release_year");

        // Plugin table: extend -> extension, version -> plugin_version
        renameColumn(connection, "plugin", "extend", "extension");
        verifyColumnExists(connection, "plugin", "extension");
        renameColumn(connection, "plugin", "version", "plugin_version");
        verifyColumnExists(connection, "plugin", "plugin_version");

        // Plugin_filter table: extend -> extension, version -> plugin_version
        renameColumn(connection, "plugin_filter", "extend", "extension");
        verifyColumnExists(connection, "plugin_filter", "extension");
        renameColumn(connection, "plugin_filter", "version", "plugin_version");
        verifyColumnExists(connection, "plugin_filter", "plugin_version");

        // Setting table: svalue -> setting_value
        renameColumn(connection, "setting", "svalue", "setting_value");
        verifyColumnExists(connection, "setting", "setting_value");
    }

    private void verifyColumnExists(Connection connection, String table, String column) throws Exception {
        String found = findColumn(connection, table, column);
        if (found == null) {
            throw new Exception("VERIFICATION FAILED: Column " + table + "." + column + " does not exist after rename!");
        }
        System.out.println("Verified: " + table + "." + column + " exists");
    }

    private void renameColumn(Connection connection, String table, String oldName, String newName) throws Exception {
        // Find the actual column name (case-insensitive)
        String actualOldName = findColumn(connection, table, oldName);
        if (actualOldName == null) {
            // Column doesn't exist, skip
            System.out.println("Column " + table + "." + oldName + " not found, skipping");
            return;
        }

        // Check if target column already exists
        String existingNew = findColumn(connection, table, newName);
        if (existingNew != null) {
            // Target already exists, skip
            System.out.println("Column " + table + "." + newName + " already exists, skipping");
            return;
        }

        System.out.println("Renaming " + table + "." + actualOldName + " to " + newName);

        // Try multiple strategies to rename the column
        Exception lastException = null;

        // Strategy 1: Try with backticks (for SQL reserved keywords created with backticks in V1)
        if (isReservedKeyword(oldName)) {
            try {
                String sql = "ALTER TABLE " + table + " RENAME COLUMN `" + oldName + "` TO " + newName;
                executeRename(connection, sql);
                System.out.println("Successfully renamed using backticks");
                return;
            } catch (Exception e) {
                lastException = e;
                System.out.println("Backtick strategy failed: " + e.getMessage());
            }
        }

        // Strategy 2: Try with actual column name (as stored in DB)
        try {
            String sql = "ALTER TABLE " + table + " RENAME COLUMN " + actualOldName + " TO " + newName;
            executeRename(connection, sql);
            System.out.println("Successfully renamed using actual name");
            return;
        } catch (Exception e) {
            lastException = e;
            System.out.println("Actual name strategy failed: " + e.getMessage());
        }

        // Strategy 3: Try with lowercase
        try {
            String sql = "ALTER TABLE " + table + " RENAME COLUMN " + oldName.toLowerCase() + " TO " + newName;
            executeRename(connection, sql);
            System.out.println("Successfully renamed using lowercase");
            return;
        } catch (Exception e) {
            lastException = e;
            System.out.println("Lowercase strategy failed: " + e.getMessage());
        }

        // Strategy 4: Try with uppercase
        try {
            String sql = "ALTER TABLE " + table + " RENAME COLUMN " + oldName.toUpperCase() + " TO " + newName;
            executeRename(connection, sql);
            System.out.println("Successfully renamed using uppercase");
            return;
        } catch (Exception e) {
            lastException = e;
            System.out.println("Uppercase strategy failed: " + e.getMessage());
        }

        // All strategies failed, throw exception
        throw new Exception("Failed to rename column " + table + "." + oldName + " to " + newName +
                          ". Last error: " + (lastException != null ? lastException.getMessage() : "unknown"), lastException);
    }

    private void executeRename(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
    
    private String findColumn(Connection connection, String table, String column) throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        String schema = connection.getSchema();
        try (ResultSet resultSet = metaData.getColumns(connection.getCatalog(), schema, null, null)) {
            while (resultSet.next()) {
                String tableName = resultSet.getString("TABLE_NAME");
                String columnName = resultSet.getString("COLUMN_NAME");
                if (tableName.equalsIgnoreCase(table) && columnName.equalsIgnoreCase(column)) {
                    return columnName;
                }
            }
        }
        return null;
    }
    
    private boolean isReservedKeyword(String name) {
        String upper = name.toUpperCase();
        return upper.equals("KEY") || upper.equals("VALUE") || upper.equals("YEAR") 
            || upper.equals("VERSION") || upper.equals("EXTEND");
    }
}
