package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V24:追剧订阅损坏集登记 —— media_subscription 增加 broken_episodes(TEXT,JSON {集号: "源目录|时间戳"})。
 * 用于"分享有效但某集被和谐"场景:转存校验发现源里有但拷不过去的集,登记后从该源的有效覆盖中剔除,
 * 触发补源;7 天过期自动重试,换源时清空。标识符不加引号(见 V21 教训)。
 */
public class V24__MediaSubscriptionBrokenEpisodes extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String actualTable = findTable(connection, "media_subscription");
        if (actualTable == null || findColumn(connection, actualTable, "broken_episodes") != null) {
            return;
        }
        execute(connection, "ALTER TABLE " + actualTable + " ADD COLUMN broken_episodes TEXT");
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
