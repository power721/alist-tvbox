package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V50:media_subscription 加 custom_keywords(TEXT)。
 * <p>
 * 自定义搜索词(手动,换行分隔,至多 5 个):主搜索词以外的额外召回词(英文名/别名/简繁写法),
 * 巡检填池与补搜轮次各词独立成路。标识符不加引号(见 V21 教训)。
 */
public class V50__MediaSubscriptionCustomKeywords extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String table = findTable(connection, "media_subscription");
        if (table == null) {
            return;
        }
        if (findColumn(connection, table, "custom_keywords") == null) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + table + " ADD COLUMN custom_keywords TEXT");
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
