package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V30:追更系统两级状态机(见 docs/media-subscription-redesign.md §4)。
 * <ol>
 *   <li>建 {@code msub_episode}(分集,元数据侧)与 {@code msub_episode_source}(集源,可用性唯一记录处)</li>
 *   <li>建 {@code dead_link}(失效黑名单,跨订阅共享的唯一内容)</li>
 *   <li>{@code media_subscription_resource} 加 {@code state}(CANDIDATE/MOUNTED/RETIRED/REJECTED),
 *       由旧 active/gap/validity 推导初值;主源行回填 mount_path(旧代码从不给主源写该字段)</li>
 *   <li>drop 旧列:resource 的 validity/active/gap/episode_list、subscription 的 episode_list/broken_episodes</li>
 * </ol>
 * <b>不回填任何 episode_source 行</b>:旧 episode_list 本身可能是陈旧脏数据(死资源冒领集数),
 * 回填等于把脏状态固化进新模型。首次巡检按真实目录重建。
 * 分集骨架也不在迁移里建 —— ensureEpisode 建行时从 subscription.schedule 快照取播出时间,效果等价,
 * 还免掉在 SQL 里手工分配 id_generator 序列。schedule 列因此保留。
 * <p>
 * 未发布功能,旧列直接 drop 不设过渡期(Q32)。标识符不加引号(V21 教训);全程幂等可重跑。
 */
public class V30__MediaSubscriptionEpisodeSource extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        execute(connection, """
                CREATE TABLE IF NOT EXISTS msub_episode (
                    id INTEGER NOT NULL PRIMARY KEY,
                    subscription_id INTEGER NOT NULL,
                    season INTEGER NOT NULL,
                    number INTEGER NOT NULL,
                    title VARCHAR(255),
                    air_time BIGINT,
                    aired BOOLEAN,
                    CONSTRAINT uk_msub_episode UNIQUE (subscription_id, season, number)
                )""");
        createIndexIfMissing(connection, "msub_episode", "idx_msub_episode_sub", "subscription_id");

        execute(connection, """
                CREATE TABLE IF NOT EXISTS msub_episode_source (
                    id INTEGER NOT NULL PRIMARY KEY,
                    episode_id INTEGER NOT NULL,
                    resource_id INTEGER NOT NULL,
                    rel_path VARCHAR(512) NOT NULL,
                    file_size BIGINT,
                    state VARCHAR(16) NOT NULL DEFAULT 'LISTED',
                    success_count INTEGER DEFAULT 0,
                    fail_count INTEGER DEFAULT 0,
                    last_verified_time BIGINT,
                    CONSTRAINT uk_msub_episode_source UNIQUE (episode_id, resource_id)
                )""");
        createIndexIfMissing(connection, "msub_episode_source", "idx_msub_es_resource", "resource_id");

        // link 单列 VARCHAR(1024):内联 UNIQUE 约束在 MySQL utf8mb4 下即 4096 字节,超 InnoDB 3072 字节
        // 键长上限,CREATE TABLE 直接 ERROR 1071 → 拆出来建索引,MySQL 用前缀(760×4=3040 字节 ≤ 3072)
        execute(connection, """
                CREATE TABLE IF NOT EXISTS dead_link (
                    id INTEGER NOT NULL PRIMARY KEY,
                    link VARCHAR(1024) NOT NULL,
                    reason VARCHAR(255),
                    fail_count INTEGER DEFAULT 0,
                    time BIGINT NOT NULL
                )""");
        createIndexIfMissing(connection, "dead_link", "uk_dead_link_link", true, 760, "link");

        String resourceTable = findTable(connection, "media_subscription_resource");
        if (resourceTable != null && findColumn(connection, resourceTable, "state") == null) {
            execute(connection, "ALTER TABLE " + resourceTable + " ADD COLUMN state VARCHAR(16) NOT NULL DEFAULT 'CANDIDATE'");
            // 初值推导:挂着的(active/gap)都是 MOUNTED —— 即使旧 validity=BAD(死补缺挂载)也先 MOUNTED,
            // 由运行期 retireResource 负责删 share 干净退役,避免迁移里留孤儿挂载;未挂而 BAD → RETIRED。
            execute(connection, "UPDATE " + resourceTable + " SET state = 'MOUNTED' WHERE active = TRUE OR gap = TRUE");
            execute(connection, "UPDATE " + resourceTable + " SET state = 'RETIRED' WHERE validity = 'BAD'");
            // 主源行旧代码从不写 mount_path(只有补缺挂载写),回填为订阅固定路径
            execute(connection, "UPDATE " + resourceTable + " r SET mount_path = ("
                    + "SELECT s.mount_path FROM media_subscription s WHERE s.id = r.subscription_id)"
                    + " WHERE state = 'MOUNTED' AND (mount_path IS NULL OR mount_path = '')");
        }
        dropColumn(connection, resourceTable, "validity");
        dropColumn(connection, resourceTable, "active");
        dropColumn(connection, resourceTable, "gap");
        dropColumn(connection, resourceTable, "episode_list");

        String subscriptionTable = findTable(connection, "media_subscription");
        dropColumn(connection, subscriptionTable, "episode_list");
        dropColumn(connection, subscriptionTable, "broken_episodes");
    }

    private void dropColumn(Connection connection, String table, String column) throws SQLException {
        if (table == null || findColumn(connection, table, column) == null) {
            return;
        }
        execute(connection, "ALTER TABLE " + table + " DROP COLUMN " + column);
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

    private void createIndexIfMissing(Connection connection, String table, String index, String... columns) throws SQLException {
        createIndexIfMissing(connection, table, index, false, null, columns);
    }

    /** @param mysqlLastColumnPrefix MySQL 专用:末列前缀索引字符数(避开 InnoDB 3072 字节键长上限),其它库忽略 */
    private void createIndexIfMissing(Connection connection, String table, String index, boolean unique,
                                      Integer mysqlLastColumnPrefix, String... columns) throws SQLException {
        // 先解析真实表名再查索引:H2 把未加引号标识符折叠为大写,拿小写名查 getIndexInfo 永远落空,
        // 重复执行时会撞 "index already exists"(幂等性回归测试抓到的)
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
        String cols = String.join(", ", columns);
        if (mysqlLastColumnPrefix != null && isMySql(connection)) {
            cols = cols + "(" + mysqlLastColumnPrefix + ")";
        }
        execute(connection, "CREATE " + (unique ? "UNIQUE " : "") + "INDEX " + index + " ON " + actualTable + " (" + cols + ")");
    }

    private boolean isMySql(Connection connection) throws SQLException {
        String name = connection.getMetaData().getDatabaseProductName();
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("mysql") || lower.contains("mariadb");
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
