package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V16:把旧 /history、/api/history 写入的 legacy 行(source_kind IS NULL)迁移到多端同步身份,
 * 使其在 /api/playback/records 与 /changes 可见。legacy key 两种形态:
 * <ul>
 *   <li>网页端 = vodId(无分隔符)→ source_key = csp_AList(与 Phase-1 新写一致,自然合并)</li>
 *   <li>设备同步 = csp_AList@@@vodId@@@cid → 按 @@@ 拆出 source_key/vod_id</li>
 * </ul>
 * 已存在同身份的新记录则删除 legacy 行,避免重复(下次播放 upsert 也会自愈)。
 * change_seq 取 create_time,沿用 V11 的回填约定(旧记录 epoch ms,低于当前水位)。
 */
public class V16__MigrateLegacyHistory extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String table = findTable(connection, "history");
        if (table == null || findColumn(connection, table, "source_kind") == null) {
            return;
        }
        String keyCol = findColumn(connection, table, "key");
        if (keyCol == null) {
            return;
        }
        String select = "SELECT id, " + quote(connection, keyCol) + ", uid, create_time"
                + " FROM " + table + " WHERE source_kind IS NULL";
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(select)) {
            while (rs.next()) {
                int id = rs.getInt(1);
                String key = rs.getString(2);
                int uid = rs.getInt(3);
                long createTime = rs.getLong(4);
                if (key == null || key.isEmpty()) {
                    continue;
                }
                String sourceKey;
                String vodId;
                int at = key.indexOf("@@@");
                if (at > 0) {
                    sourceKey = key.substring(0, at);
                    int at2 = key.indexOf("@@@", at + 3);
                    vodId = at2 > 0 ? key.substring(at + 3, at2) : key.substring(at + 3);
                } else {
                    sourceKey = "csp_AList";
                    vodId = key;
                }
                if (vodId.isEmpty()) {
                    continue;
                }
                if (hasNewRow(connection, table, uid, sourceKey, vodId)) {
                    execute(connection, "DELETE FROM " + table + " WHERE id = " + id);
                } else {
                    migrateRow(connection, table, id, sourceKey, vodId, createTime);
                }
            }
        }
    }

    private boolean hasNewRow(Connection connection, String table, int uid, String sourceKey, String vodId) throws SQLException {
        String sql = "SELECT 1 FROM " + table
                + " WHERE uid = ? AND source_kind IS NOT NULL AND source_key = ? AND vod_id = ? LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, uid);
            ps.setString(2, sourceKey);
            ps.setString(3, vodId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void migrateRow(Connection connection, String table, int id, String sourceKey, String vodId, long createTime) throws SQLException {
        String sql = "UPDATE " + table
                + " SET source_kind = 'site', source_key = ?, source_name = 'AList',"
                + " vod_id = ?, updated_at = ?, change_seq = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sourceKey);
            ps.setString(2, vodId);
            ps.setLong(3, createTime);
            ps.setLong(4, createTime);
            ps.setInt(5, id);
            ps.executeUpdate();
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

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
