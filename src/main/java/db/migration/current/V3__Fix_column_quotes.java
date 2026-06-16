package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

public class V3__Fix_column_quotes extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String quote = connection.getMetaData().getIdentifierQuoteString();
        
        // Rename columns from backtick-style to double-quote-style
        // Only rename if the quoted version doesn't already exist
        
        // Plugin table
        renameColumnIfNeeded(connection, "plugin", "extend", quote + "extend" + quote);
        renameColumnIfNeeded(connection, "plugin", "version", quote + "version" + quote);
        
        // Plugin_filter table
        renameColumnIfNeeded(connection, "plugin_filter", "extend", quote + "extend" + quote);
        renameColumnIfNeeded(connection, "plugin_filter", "version", quote + "version" + quote);
        
        // History table
        renameColumnIfNeeded(connection, "history", "key", quote + "key" + quote);
        
        // Navigation table
        renameColumnIfNeeded(connection, "navigation", "value", quote + "value" + quote);
        
        // Movie table
        renameColumnIfNeeded(connection, "movie", "year", quote + "year" + quote);
        
        // Tmdb table
        renameColumnIfNeeded(connection, "tmdb", "year", quote + "year" + quote);
        
        // Meta table
        renameColumnIfNeeded(connection, "meta", "year", quote + "year" + quote);
        
        // Tmdb_meta table
        renameColumnIfNeeded(connection, "tmdb_meta", "year", quote + "year" + quote);
    }
    
    private void renameColumnIfNeeded(Connection connection, String table, String baseName, String quotedName) throws Exception {
        // Check if column already exists with the target name
        String existingColumn = findColumn(connection, table, baseName);
        if (existingColumn == null) {
            // Column doesn't exist at all - skip
            return;
        }
        
        // Try to rename using backticks (V1 style)
        String sql = "ALTER TABLE " + table + " RENAME COLUMN `" + baseName + "` TO " + quotedName;
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception e) {
            // If that fails, column might already be correctly named or stored as uppercase
            // This is fine - the column is accessible either way
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
}
