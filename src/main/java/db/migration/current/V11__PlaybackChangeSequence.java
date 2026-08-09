package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** V11:为同步变更增加服务端单调序列,避免迟到的客户端时间戳被游标永久跳过。 */
public class V11__PlaybackChangeSequence extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        addColumnIfMissing(connection, "history", "change_seq", "BIGINT");
        addColumnIfMissing(connection, "playback_tombstone", "change_seq", "BIGINT");
        execute(connection, """
                CREATE TABLE IF NOT EXISTS playback_change_sequence (
                    id INTEGER NOT NULL PRIMARY KEY,
                    next_val BIGINT NOT NULL
                )""");

        execute(connection, "UPDATE history SET change_seq = updated_at WHERE change_seq IS NULL AND updated_at IS NOT NULL");
        execute(connection, "UPDATE playback_tombstone SET change_seq = deleted_at WHERE change_seq IS NULL");
        long highWater = maxChangeSeq(connection);
        ensureSequenceRow(connection, highWater);

        createIndexIfMissing(connection, "history", "idx_history_change", "uid", "change_seq");
        createIndexIfMissing(connection, "playback_tombstone", "idx_pb_tomb_change", "uid", "change_seq");
    }

    private void ensureSequenceRow(Connection connection, long highWater) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT next_val FROM playback_change_sequence WHERE id = 1")) {
            if (rs.next()) {
                if (rs.getLong(1) < highWater) {
                    execute(connection, "UPDATE playback_change_sequence SET next_val = " + highWater + " WHERE id = 1");
                }
                return;
            }
        }
        execute(connection, "INSERT INTO playback_change_sequence (id, next_val) VALUES (1, " + highWater + ")");
    }

    private long maxChangeSeq(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT MAX(change_seq) FROM ("
                     + "SELECT change_seq FROM history UNION ALL SELECT change_seq FROM playback_tombstone) changes")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private void addColumnIfMissing(Connection connection, String table, String column, String type) throws SQLException {
        String actualTable = findTable(connection, table);
        if (actualTable != null && findColumn(connection, actualTable, column) == null) {
            execute(connection, "ALTER TABLE " + quote(connection, actualTable) + " ADD COLUMN " + column + " " + type);
        }
    }

    private void createIndexIfMissing(Connection connection, String table, String indexName, String... columns)
            throws SQLException {
        String actualTable = findTable(connection, table);
        if (actualTable == null || findIndex(connection, actualTable, indexName)) {
            return;
        }
        StringBuilder names = new StringBuilder();
        for (String column : columns) {
            String actualColumn = findColumn(connection, actualTable, column);
            if (actualColumn == null) {
                return;
            }
            if (!names.isEmpty()) {
                names.append(", ");
            }
            names.append(quote(connection, actualColumn));
        }
        execute(connection, "CREATE INDEX " + indexName + " ON " + quote(connection, actualTable) + " (" + names + ")");
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
        try (ResultSet rs = connection.getMetaData().getIndexInfo(
                connection.getCatalog(), schemaPattern(connection), table, false, false)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                if (name != null && name.equalsIgnoreCase(indexName)) {
                    return true;
                }
            }
        } catch (SQLException ignored) {
            // 部分驱动对无索引表会抛错,按不存在处理。
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

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
