package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V52:追剧采集源兜底覆盖层 —— 新表 msub_episode_fallback。
 * 播放链路最后一级:候选源(转存/主源/补缺)全灭时,从 MacCMS 采集站搜索补齐
 * 「当前集+后 3 集」的缺口。行只存资源标识(site+vod_id)与直链缓存,
 * <b>不改写</b> media_subscription / msub_episode_source / 资源行(原始源恢复后自然夺回优先级,
 * 覆盖层行到 expires_at 自然淘汰)。标识符不加引号(见 V21 教训);全程幂等可重跑。
 */
public class V52__MediaSubscriptionEpisodeFallback extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        execute(connection, """
                CREATE TABLE IF NOT EXISTS msub_episode_fallback (
                    id INTEGER NOT NULL PRIMARY KEY,
                    subscription_id INTEGER NOT NULL,
                    episode INTEGER NOT NULL,
                    site_id VARCHAR(16) NOT NULL,
                    resource_id VARCHAR(64) NOT NULL,
                    line VARCHAR(128),
                    title VARCHAR(255),
                    url VARCHAR(1024),
                    state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                    validated_at BIGINT,
                    expires_at BIGINT,
                    CONSTRAINT uk_msub_episode_fallback UNIQUE (subscription_id, episode)
                )""");
        createIndexIfMissing(connection, "msub_episode_fallback", "idx_msub_ef_sub", "subscription_id");
    }

    private void createIndexIfMissing(Connection connection, String table, String index, String... columns) throws SQLException {
        // 先解析真实表名再查索引:H2 把未加引号标识符折叠为大写,拿小写名查 getIndexInfo 永远落空
        String actualTable = findTable(connection, table);
        if (actualTable == null) {
            return;
        }
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getIndexInfo(connection.getCatalog(), schemaPattern(connection), actualTable, false, false)) {
            while (rs.next()) {
                if (index.equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                    return;
                }
            }
        }
        execute(connection, "CREATE INDEX " + index + " ON " + actualTable + " (" + String.join(", ", columns) + ")");
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
