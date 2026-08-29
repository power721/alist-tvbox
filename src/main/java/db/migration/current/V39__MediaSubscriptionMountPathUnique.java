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
 * V39:media_subscription.mount_path 加库级唯一索引。
 * <p>
 * 转存模式的挂载路径必须独占(个人网盘),此前仅靠应用层 existsByMountPath 预检,
 * 并发创建时两个事务都能通过预检后落同一路径(跨用户挂载劫持)。迁移先重命名存量
 * 重复路径(保留最小 id,其余追加 " u{uid}" 消歧,口径同 ensureUniqueMountPath),
 * 再建唯一索引;服务层撞约束时追加后缀重试。标识符不加引号(见 V21 教训)。
 */
public class V39__MediaSubscriptionMountPathUnique extends BaseJavaMigration {

    private static final String INDEX_NAME = "idx_media_subscription_mount_path";

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String table = findTable(connection, "media_subscription");
        if (table == null) {
            return;
        }
        deduplicate(connection, table);
        if (!indexExists(connection, table)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE UNIQUE INDEX " + INDEX_NAME + " ON " + table + " (mount_path)");
            }
        }
    }

    private void deduplicate(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet groups = statement.executeQuery(
                     "SELECT mount_path, MIN(id) FROM " + table
                             + " WHERE mount_path IS NOT NULL GROUP BY mount_path HAVING COUNT(*) > 1")) {
            while (groups.next()) {
                String mountPath = groups.getString(1);
                int keepId = groups.getInt(2);
                renameDuplicates(connection, table, mountPath, keepId);
            }
        }
    }

    private void renameDuplicates(Connection connection, String table, String mountPath, int keepId) throws SQLException {
        try (PreparedStatement find = connection.prepareStatement(
                "SELECT id, uid FROM " + table + " WHERE mount_path = ? AND id <> ? ORDER BY id")) {
            find.setString(1, mountPath);
            find.setInt(2, keepId);
            try (ResultSet rows = find.executeQuery();
                 PreparedStatement update = connection.prepareStatement(
                         "UPDATE " + table + " SET mount_path = ? WHERE id = ?")) {
                while (rows.next()) {
                    String renamed = abbreviate(mountPath + " u" + rows.getInt(2), 512);
                    update.setString(1, renamed);
                    update.setInt(2, rows.getInt(1));
                    update.executeUpdate();
                }
            }
        }
    }

    private static String abbreviate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(text.length() - maxLength);
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

    private boolean indexExists(Connection connection, String table) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getIndexInfo(connection.getCatalog(), schemaPattern(connection), table, false, false)) {
            while (rs.next()) {
                if (INDEX_NAME.equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
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
}
