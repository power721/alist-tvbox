package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V17:修复 V16 回填 legacy 行(change_seq = create_time)后未抬高序列水位的问题。
 * 若 playback_change_sequence.next_val 低于现有 change_seq 最大值,后续新记录会分配到
 * 小于客户端游标的 change_seq,被 /changes 的 GreaterThan 查询永久跳过。
 */
public class V17__FixChangeSequenceWatermark extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String sequenceTable = findTable(connection, "playback_change_sequence");
        if (sequenceTable == null) {
            return;
        }
        long highWater = maxChangeSeq(connection, "history");
        highWater = Math.max(highWater, maxChangeSeq(connection, "playback_tombstone"));
        if (highWater <= 0) {
            return;
        }
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT next_val FROM " + sequenceTable + " WHERE id = 1")) {
            if (!rs.next() || rs.getLong(1) >= highWater) {
                return;
            }
        }
        execute(connection, "UPDATE " + sequenceTable + " SET next_val = " + highWater + " WHERE id = 1");
    }

    private long maxChangeSeq(Connection connection, String table) throws SQLException {
        String actualTable = findTable(connection, table);
        if (actualTable == null) {
            return 0L;
        }
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT MAX(change_seq) FROM " + actualTable)) {
            return rs.next() ? rs.getLong(1) : 0L;
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
