package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

public class V4__Fix_remaining_columns extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String quote = connection.getMetaData().getIdentifierQuoteString();
        
        // Fix remaining columns that H2 stored as UPPERCASE
        // Need to rename to lowercase with quotes for Hibernate compatibility
        
        // Setting table - svalue
        renameColumnIfNeeded(connection, "setting", "SVALUE", quote + "svalue" + quote);
    }
    
    private void renameColumnIfNeeded(Connection connection, String table, String oldName, String newName) throws Exception {
        // Check if column exists
        String existingColumn = findColumn(connection, table, oldName);
        if (existingColumn == null) {
            return;
        }
        
        // Rename using H2 syntax
        String sql = "ALTER TABLE " + table + " RENAME COLUMN " + oldName + " TO " + newName;
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception e) {
            // Ignore if already renamed
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
