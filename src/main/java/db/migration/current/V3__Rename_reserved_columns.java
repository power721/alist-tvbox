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
        
        // Navigation table: value -> nav_value
        renameColumn(connection, "navigation", "value", "nav_value");
        
        // Movie table: year -> release_year
        renameColumn(connection, "movie", "year", "release_year");
        
        // Tmdb table: year -> release_year
        renameColumn(connection, "tmdb", "year", "release_year");
        
        // Meta table: year -> release_year
        renameColumn(connection, "meta", "year", "release_year");
        
        // Tmdb_meta table: year -> release_year
        renameColumn(connection, "tmdb_meta", "year", "release_year");
        
        // Plugin table: extend -> extension, version -> plugin_version
        renameColumn(connection, "plugin", "extend", "extension");
        renameColumn(connection, "plugin", "version", "plugin_version");
        
        // Plugin_filter table: extend -> extension, version -> plugin_version
        renameColumn(connection, "plugin_filter", "extend", "extension");
        renameColumn(connection, "plugin_filter", "version", "plugin_version");
        
        // Setting table: svalue -> setting_value
        renameColumn(connection, "setting", "svalue", "setting_value");
    }
    
    private void renameColumn(Connection connection, String table, String oldName, String newName) throws Exception {
        // Find the actual column name (case-insensitive)
        String actualOldName = findColumn(connection, table, oldName);
        if (actualOldName == null) {
            // Column doesn't exist, skip
            return;
        }
        
        // Check if target column already exists
        String existingNew = findColumn(connection, table, newName);
        if (existingNew != null) {
            // Target already exists, skip
            return;
        }
        
        // H2 syntax: ALTER TABLE table RENAME COLUMN oldName TO newName
        // Use backticks if the old column was created with backticks
        String sql;
        if (isReservedKeyword(oldName)) {
            // Try with backticks first (V1 style)
            sql = "ALTER TABLE " + table + " RENAME COLUMN `" + oldName + "` TO " + newName;
        } else {
            // Regular column (might be uppercase in H2)
            sql = "ALTER TABLE " + table + " RENAME COLUMN " + actualOldName + " TO " + newName;
        }
        
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception e) {
            // If backtick fails, try without
            sql = "ALTER TABLE " + table + " RENAME COLUMN " + actualOldName + " TO " + newName;
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
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
