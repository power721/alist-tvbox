package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** V14:保存插件网盘资源、子目录和集数的跨端恢复上下文。 */
public class V14__PlaybackSelectionContext extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String table = findTable(connection, "history");
        if (table == null) {
            return;
        }
        addColumn(connection, table, "playlist_index", "INTEGER");
        addColumn(connection, table, "source_group_index", "INTEGER");
        addColumn(connection, table, "source_index", "INTEGER");
        addColumn(connection, table, "source_subgroup_index", "INTEGER");
        addColumn(connection, table, "source_subgroup_name", "VARCHAR(255)");
        addColumn(connection, table, "drive_dir_id", "TEXT");
    }

    private void addColumn(Connection connection, String table, String column, String type) throws SQLException {
        if (findColumn(connection, table, column) != null) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + quote(connection, table)
                    + " ADD COLUMN " + column + " " + type);
        }
    }

    private String findTable(Connection connection, String table) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet rs = metadata.getTables(connection.getCatalog(), schemaPattern(connection), null, null)) {
            while (rs.next()) {
                if (rs.getString("TABLE_NAME").equalsIgnoreCase(table)) {
                    return rs.getString("TABLE_NAME");
                }
            }
        }
        return null;
    }

    private String findColumn(Connection connection, String table, String column) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet rs = metadata.getColumns(connection.getCatalog(), schemaPattern(connection), table, null)) {
            while (rs.next()) {
                if (rs.getString("COLUMN_NAME").equalsIgnoreCase(column)) {
                    return rs.getString("COLUMN_NAME");
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
        String value = connection.getMetaData().getIdentifierQuoteString();
        return value == null || value.isBlank() ? identifier : value + identifier + value;
    }
}
