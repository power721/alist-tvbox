package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V31:media_subscription 加 cover_url(封面快照)。
 * <p>
 * 列表接口原先实时调 {@code MetadataService.details()} 取封面,provider 缓存是内存
 * Caffeine(6h),重启即空 —— 冷缓存时每个订阅要串行发多次外部请求,列表页被拖到几十秒。
 * 封面改落订阅行,列表纯读库;缺失时由巡检/异步预热回填。
 * <p>
 * 标识符不加引号(见 V21 教训);列已存在时静默跳过,可重复执行。
 */
public class V31__MediaSubscriptionCover extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String table = findTable(connection, "media_subscription");
        if (table == null || findColumn(connection, table, "cover_url") != null) {
            return;
        }
        execute(connection, "ALTER TABLE " + table + " ADD COLUMN cover_url VARCHAR(512)");
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
