package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V32:建 media_metadata(媒体元数据持久层)。
 * <p>
 * provider 内存缓存重启即空,完结剧每次冷启动都要重新外网拉取;详情页分集(标题/播出时间/简介)
 * 也无本地来源。此表存 provider+条目+季 → 详情 JSON 快照:完结剧永久命中零网络,
 * 在播剧(RETURNING)按 TTL 重刷。标识符不加引号(见 V21 教训);表已存在时静默跳过。
 */
public class V32__MediaMetadata extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (findTable(connection, "media_metadata") != null) {
            return;
        }
        execute(connection, "CREATE TABLE media_metadata ("
                + " id INTEGER NOT NULL PRIMARY KEY,"
                + " provider VARCHAR(16) NOT NULL,"
                + " meta_id VARCHAR(64) NOT NULL,"
                + " season INTEGER NOT NULL,"
                + " status VARCHAR(16) NOT NULL,"
                + " payload TEXT NOT NULL,"
                + " fetch_time BIGINT NOT NULL)");
        execute(connection, "CREATE UNIQUE INDEX idx_media_meta_key ON media_metadata (provider, meta_id, season)");
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
