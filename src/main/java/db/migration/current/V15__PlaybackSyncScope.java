package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V15:播放记录同步按订阅分区(sync_scope)。
 * <p>
 * sync_scope 为空 = uid 级(现状,所有订阅互通);非空 = 订阅身份(vod token 或 token/id),
 * 仅同分区互相同步。同步轨(history.source_kind 非空)、墓碑、令牌三处各加一列。
 */
public class V15__PlaybackSyncScope extends BaseJavaMigration {
    private static final String INDEX_NAME = "idx_history_sync_scope";

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        addColumn(connection, "history", "sync_scope", "VARCHAR(255)");
        addColumn(connection, "playback_token", "sync_scope", "VARCHAR(255)");
        addColumn(connection, "playback_tombstone", "sync_scope", "VARCHAR(255)");
        createIndexIfMissing(connection, "history", INDEX_NAME,
                "uid", "sync_scope", "source_kind", "source_key");
    }

    private void addColumn(Connection connection, String table, String column, String type) throws SQLException {
        String realTable = findTable(connection, table);
        if (realTable == null || findColumn(connection, realTable, column) != null) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + quote(connection, realTable)
                    + " ADD COLUMN " + column + " " + type);
        }
    }

    private void createIndexIfMissing(Connection connection, String table, String indexName,
                                      String... columns) throws SQLException {
        String realTable = findTable(connection, table);
        if (realTable == null || findIndex(connection, realTable, indexName)) {
            return;
        }
        StringBuilder columnList = new StringBuilder();
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) {
                columnList.append(", ");
            }
            columnList.append(columns[i]);
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE INDEX " + indexName + " ON " + quote(connection, realTable)
                    + " (" + columnList + ")");
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

    private boolean findIndex(Connection connection, String table, String indexName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet rs = metadata.getIndexInfo(connection.getCatalog(), schemaPattern(connection), table, false, false)) {
            while (rs.next()) {
                if (indexName.equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                    return true;
                }
            }
        }
        return false;
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
