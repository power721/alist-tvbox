package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * V34:media_subscription_resource 加 link_hash,唯一索引从 (subscription_id, link) 换到 (subscription_id, link_hash)。
 * <p>
 * V20 在 MySQL 上的唯一索引是 link 前 760 字符前缀(InnoDB 3072 字节键长上限):两条前 760 字符
 * 相同的长分享链会被误判重复拒绝插入,而入池前 findBySubscriptionIdAndLink 预查两行都找不到、拦不住。
 * SHA-256 全链哈希无长度截断,三库统一换列。存量行在此回填(实体 @PrePersist 只覆盖新写入),
 * 哈希算法与 {@code MediaSubscriptionResource#hashOf} 保持一致:小写 hex、UTF-8,不可改动。
 * 标识符不加引号(V21 教训);索引按列清单判重,可重复执行。
 */
public class V34__MediaSubscriptionResourceLinkHash extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String table = findTable(connection, "media_subscription_resource");
        if (table == null) {
            return;
        }
        if (findColumn(connection, table, "link_hash") == null) {
            execute(connection, "ALTER TABLE " + table + " ADD COLUMN link_hash VARCHAR(64)");
        }
        try (Statement select = connection.createStatement();
             PreparedStatement update = connection.prepareStatement("UPDATE " + table + " SET link_hash = ? WHERE id = ?")) {
            try (ResultSet rs = select.executeQuery("SELECT id, link FROM " + table)) {
                while (rs.next()) {
                    update.setString(1, sha256Hex(rs.getString(2)));
                    update.setInt(2, rs.getInt(1));
                    update.addBatch();
                }
            }
            update.executeBatch();
        }
        if (!indexCovers(connection, table, "uk_msub_resource", "subscription_id", "link_hash")) {
            dropIndexIfPresent(connection, table, "uk_msub_resource");
            execute(connection, "CREATE UNIQUE INDEX uk_msub_resource ON " + table + " (subscription_id, link_hash)");
        }
    }

    private static String sha256Hex(String value) throws NoSuchAlgorithmException {
        if (value == null) {
            return null;
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private void dropIndexIfPresent(Connection connection, String table, String indexName) throws SQLException {
        if (!findIndex(connection, table, indexName)) {
            return;
        }
        // MySQL 索引属于表必须带 ON;H2/PG 索引是 schema 级对象(V12 同款分支)
        String name = connection.getMetaData().getDatabaseProductName();
        boolean mysql = name != null && (name.toLowerCase(java.util.Locale.ROOT).contains("mysql")
                || name.toLowerCase(java.util.Locale.ROOT).contains("mariadb"));
        execute(connection, mysql ? "DROP INDEX " + indexName + " ON " + table : "DROP INDEX " + indexName);
    }

    /** 指定索引的列清单是否与期望一致(忽略大小写/序):判断 uk_msub_resource 是否已换到 link_hash。 */
    private boolean indexCovers(Connection connection, String table, String indexName, String... columns) throws SQLException {
        List<String> actual = indexColumns(connection, table, indexName);
        if (actual.size() != columns.length) {
            return false;
        }
        for (int i = 0; i < columns.length; i++) {
            if (!actual.get(i).equalsIgnoreCase(columns[i])) {
                return false;
            }
        }
        return true;
    }

    private List<String> indexColumns(Connection connection, String table, String indexName) throws SQLException {
        List<String> columns = new ArrayList<>();
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getIndexInfo(connection.getCatalog(), schemaPattern(connection), table, false, false)) {
            while (rs.next()) {
                if (indexName.equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                    String column = rs.getString("COLUMN_NAME");
                    if (column != null) {
                        columns.add(column);
                    }
                }
            }
        } catch (SQLException e) {
            // 部分驱动对无索引表会抛错,容忍并按"不存在"处理
        }
        return columns;
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

    private boolean findIndex(Connection connection, String table, String indexName) throws SQLException {
        return !indexColumns(connection, table, indexName).isEmpty();
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
