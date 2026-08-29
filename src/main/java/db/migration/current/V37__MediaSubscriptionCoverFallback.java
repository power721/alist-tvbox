package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** V37:追剧订阅增加跨图床封面回退快照。 */
public class V37__MediaSubscriptionCoverFallback extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String table = findTable(connection, "media_subscription");
        if (table == null) {
            return;
        }
        if (findColumn(connection, table, "cover_fallback_url") == null) {
            execute(connection, "ALTER TABLE " + table + " ADD COLUMN cover_fallback_url VARCHAR(512)");
        }
        if (findColumn(connection, table, "cover_fallback_status") == null) {
            execute(connection, "ALTER TABLE " + table + " ADD COLUMN cover_fallback_status VARCHAR(16)");
        }
        execute(connection, "UPDATE " + table
                + " SET cover_fallback_status = CASE"
                + " WHEN cover_fallback_url IS NOT NULL AND cover_fallback_url <> '' THEN 'MATCH'"
                + " ELSE 'PENDING' END"
                + " WHERE LOWER(meta_provider) = 'tmdb' AND cover_fallback_status IS NULL");
    }

    private String findTable(Connection connection, String table) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getTables(connection.getCatalog(), schemaPattern(connection), null, null)) {
            while (rs.next()) {
                if (rs.getString("TABLE_NAME").equalsIgnoreCase(table)) {
                    return rs.getString("TABLE_NAME");
                }
            }
        }
        return null;
    }

    private String findColumn(Connection connection, String table, String column) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getColumns(connection.getCatalog(), schemaPattern(connection), table, null)) {
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

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
