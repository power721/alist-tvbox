package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Rename columns that use SQL reserved keywords to improve compatibility.
 * This migration checks if columns exist before renaming to handle various database states.
 */
public class V3__Rename_reserved_columns extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        // Rename year -> release_year in multiple tables
        renameColumn(connection, "history", "key", "item_key");
        renameColumn(connection, "navigation", "value", "nav_value");
        renameColumn(connection, "movie", "year", "release_year");
        renameColumn(connection, "tmdb", "year", "release_year");
        renameColumn(connection, "meta", "year", "release_year");
        renameColumn(connection, "tmdb_meta", "year", "release_year");
        renameColumn(connection, "plugin", "extend", "extension");
        renameColumn(connection, "plugin", "version", "plugin_version");
        renameColumn(connection, "plugin_filter", "extend", "extension");
        renameColumn(connection, "plugin_filter", "version", "plugin_version");
        renameColumn(connection, "setting", "svalue", "setting_value");
    }

    private void renameColumn(Connection connection, String tableName, String oldName, String newName) throws SQLException {
        String actualTable = findTable(connection, tableName);
        if (actualTable == null) {
            // Table doesn't exist, skip
            System.out.println("V3: Table " + tableName + " not found, skipping");
            return;
        }

        String actualOldColumn = findColumn(connection, actualTable, oldName);
        String actualNewColumn = findColumn(connection, actualTable, newName);

        System.out.println("V3: " + tableName + "." + oldName + " -> " + newName +
                           " (old=" + actualOldColumn + ", new=" + actualNewColumn + ")");

        if (actualOldColumn != null && actualNewColumn == null) {
            // Old column exists, new column doesn't exist -> rename
            // H2 with MySQL mode still stores identifiers based on how they're created in V1
            // V1 uses backticks which H2 treats as uppercase
            // Solution: Rename to uppercase unquoted name so H2 stores it as uppercase
            // Then H2's case-insensitive matching will work with Hibernate's lowercase queries
            String sql = "ALTER TABLE " + quote(connection, actualTable)
                    + " RENAME COLUMN " + quote(connection, actualOldColumn)
                    + " TO " + newName.toUpperCase();
            System.out.println("V3: Executing: " + sql);
            execute(connection, sql);
            System.out.println("V3: Successfully renamed " + tableName + "." + oldName + " to " + newName);
        } else if (actualOldColumn == null && actualNewColumn != null) {
            // New column already exists, old column doesn't -> already renamed, skip
            System.out.println("V3: Column already renamed, skipping");
            return;
        } else if (actualOldColumn != null && actualNewColumn != null) {
            // Both columns exist -> unexpected state, but don't fail
            // This might happen if migration was partially applied
            System.out.println("V3: Both columns exist, skipping");
            return;
        } else {
            System.out.println("V3: Neither column exists, skipping");
        }
    }

    private String findTable(Connection connection, String table) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getTables(connection.getCatalog(), schemaPattern(connection), null, null)) {
            while (resultSet.next()) {
                if (resultSet.getString("TABLE_NAME").equalsIgnoreCase(table)) {
                    return resultSet.getString("TABLE_NAME");
                }
            }
        }
        return null;
    }

    private String findColumn(Connection connection, String table, String column) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getColumns(connection.getCatalog(), schemaPattern(connection), table, null)) {
            while (resultSet.next()) {
                String columnName = resultSet.getString("COLUMN_NAME");
                // H2 stores unquoted reserved keywords in uppercase
                // Try both exact match and case-insensitive match
                if (columnName.equalsIgnoreCase(column)) {
                    return columnName;
                }
            }
        }
        return null;
    }

    private String schemaPattern(Connection connection) throws SQLException {
        String schema = connection.getSchema();
        return schema == null || schema.isBlank() ? null : schema;
    }

    private String quote(Connection connection, String identifier) throws SQLException {
        String quote = connection.getMetaData().getIdentifierQuoteString();
        if (quote == null || quote.isBlank()) {
            return identifier;
        }
        return quote + identifier + quote;
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
