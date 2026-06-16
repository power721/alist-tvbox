package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class V2__Normalize_reserved_columns extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        migrateSortOrder(connection, "site", true);
        migrateSortOrder(connection, "navigation", true);
        migrateSortOrder(connection, "emby", false);
        migrateSortOrder(connection, "jellyfin", false);
        migrateSortOrder(connection, "telegram_channel", true);
        migrateSortOrder(connection, "feiniu", false);
        migrateSiteVersion(connection);
    }

    private void migrateSortOrder(Connection connection, String table, boolean required) throws SQLException {
        String actualTable = findTable(connection, table);
        if (actualTable == null) {
            return;
        }

        // Step 1: Add nullable column with default
        boolean hasSortOrder = findColumn(connection, actualTable, "sort_order") != null;
        if (!hasSortOrder) {
            execute(connection, "ALTER TABLE " + quote(connection, actualTable)
                    + " ADD COLUMN sort_order INTEGER DEFAULT 0");
        }

        // Step 2: Migrate data from old column if it exists
        String oldOrder = findColumn(connection, actualTable, "order");
        if (oldOrder != null) {
            String value = required ? "COALESCE(" + quote(connection, oldOrder) + ", 0)" : quote(connection, oldOrder);
            execute(connection, "UPDATE " + quote(connection, actualTable)
                    + " SET sort_order = " + value
                    + " WHERE sort_order IS NULL OR sort_order = 0");
            execute(connection, "ALTER TABLE " + quote(connection, actualTable) + " DROP COLUMN " + quote(connection, oldOrder));
        }

        // Step 3: Add NOT NULL constraint after data is populated (if required)
        if (required && !hasSortOrder) {
            // Ensure all nulls are filled before adding constraint
            execute(connection, "UPDATE " + quote(connection, actualTable)
                    + " SET sort_order = 0 WHERE sort_order IS NULL");

            // Add NOT NULL constraint (database-specific syntax)
            String dbProduct = connection.getMetaData().getDatabaseProductName().toLowerCase();
            if (dbProduct.contains("mysql") || dbProduct.contains("mariadb")) {
                execute(connection, "ALTER TABLE " + quote(connection, actualTable)
                        + " MODIFY COLUMN sort_order INTEGER NOT NULL DEFAULT 0");
            } else if (dbProduct.contains("postgresql")) {
                execute(connection, "ALTER TABLE " + quote(connection, actualTable)
                        + " ALTER COLUMN sort_order SET NOT NULL");
            } else if (dbProduct.contains("h2")) {
                execute(connection, "ALTER TABLE " + quote(connection, actualTable)
                        + " ALTER COLUMN sort_order SET NOT NULL");
            }
            // For other databases, leave as nullable (safe fallback)
        }
    }

    private void migrateSiteVersion(Connection connection) throws SQLException {
        String actualTable = findTable(connection, "site");
        if (actualTable == null) {
            return;
        }

        boolean hasStorageVersion = findColumn(connection, actualTable, "storage_version") != null;
        if (!hasStorageVersion) {
            execute(connection, "ALTER TABLE " + quote(connection, actualTable) + " ADD COLUMN storage_version INTEGER");
        }

        String oldVersion = findColumn(connection, actualTable, "version");
        if (oldVersion != null) {
            execute(connection, "UPDATE " + quote(connection, actualTable)
                    + " SET storage_version = " + quote(connection, oldVersion)
                    + " WHERE storage_version IS NULL");
            execute(connection, "ALTER TABLE " + quote(connection, actualTable) + " DROP COLUMN " + quote(connection, oldVersion));
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
                if (resultSet.getString("COLUMN_NAME").equalsIgnoreCase(column)) {
                    return resultSet.getString("COLUMN_NAME");
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
