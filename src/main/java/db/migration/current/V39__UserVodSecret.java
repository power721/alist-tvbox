package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V39:x_user 加 vod_secret(随机 16 hex)并回填存量行。
 * <p>
 * 用户级 vod token 是 u-{username},用户名可猜测;/cookies/u-{username} 曾以"带前缀用户名"当授权
 * 直接下发该用户全部网盘凭证。凭证下载改为要求 u-{username}-{vod_secret},熵来自本列。
 * 标识符不加引号(见 V21 教训);列已存在时跳过加列但仍兜底回填空值。
 */
public class V39__UserVodSecret extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String table = findTable(connection, "x_user");
        if (table == null) {
            return;
        }
        if (findColumn(connection, table, "vod_secret") == null) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + table + " ADD COLUMN vod_secret VARCHAR(32)");
            }
        }
        backfill(connection, table);
    }

    private void backfill(Connection connection, String table) throws SQLException {
        SecureRandom random = new SecureRandom();
        try (Statement statement = connection.createStatement();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE " + table + " SET vod_secret = ? WHERE id = ?");
             ResultSet rows = statement.executeQuery("SELECT id FROM " + table + " WHERE vod_secret IS NULL")) {
            while (rows.next()) {
                byte[] bytes = new byte[8];
                random.nextBytes(bytes);
                StringBuilder secret = new StringBuilder(16);
                for (byte b : bytes) {
                    secret.append(String.format("%02x", b));
                }
                update.setString(1, secret.toString());
                update.setInt(2, rows.getInt(1));
                update.executeUpdate();
            }
        }
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
}
