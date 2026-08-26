package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V36:Telegram 通知升级(借鉴 media-vault P5)—— 同剧编辑同一条消息 + 持久化重试。
 * <p>
 * 旧 notifyTelegram 是 fire-and-forget sendMessage:一集一条刷屏、失败只打 debug 日志即丢。
 * 新结构两部分:
 * <ul>
 * <li>media_subscription 加 tg_message_id / tg_chat_id(消息绑定):首条 sendMessage 落 message_id,
 *     后续事件 editMessageText 改同一条消息;chat 配置变更后旧 id 失效,自动重发新消息换绑。</li>
 * <li>media_subscription_notify_task(outbox):事件落任务,发送失败退避重试(非稳态网络/限流不丢通知),
 *     成功 SENT、超限 FAILED 留审计。同订阅多条 PENDING 由处理端合并为一次卡片刷新,天然去重。</li>
 * </ul>
 * 跨库幂等(CREATE IF NOT EXISTS / 列先查 metadata);标识符不加引号(见 V21 教训)。
 */
public class V36__MediaSubscriptionNotify extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        String subscription = findTable(connection, "media_subscription");
        if (subscription != null) {
            if (findColumn(connection, subscription, "tg_message_id") == null) {
                execute(connection, "ALTER TABLE " + subscription + " ADD COLUMN tg_message_id BIGINT");
            }
            if (findColumn(connection, subscription, "tg_chat_id") == null) {
                execute(connection, "ALTER TABLE " + subscription + " ADD COLUMN tg_chat_id VARCHAR(64)");
            }
        }

        execute(connection, """
                CREATE TABLE IF NOT EXISTS media_subscription_notify_task (
                    id INTEGER NOT NULL PRIMARY KEY,
                    subscription_id INTEGER NOT NULL,
                    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                    attempts INTEGER DEFAULT 0,
                    next_attempt_at BIGINT DEFAULT 0,
                    last_error VARCHAR(500) DEFAULT '',
                    created_time BIGINT NOT NULL,
                    sent_time BIGINT
                )""");
        createIndexIfMissing(connection, "media_subscription_notify_task", "idx_msub_notify_due", false, "status", "next_attempt_at");
        createIndexIfMissing(connection, "media_subscription_notify_task", "idx_msub_notify_sub", false, "subscription_id");
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

    private void createIndexIfMissing(Connection connection, String table, String index, boolean unique, String... columns)
            throws SQLException {
        // 先解析库内实际表名(H2 存大写,getIndexInfo 按精确匹配),否则重跑时查不到已有索引
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
        } catch (SQLException e) {
            // 部分驱动对无索引表会抛错,容忍并按"不存在"处理
        }
        StringBuilder sql = new StringBuilder("CREATE ").append(unique ? "UNIQUE " : "").append("INDEX ")
                .append(index).append(" ON ").append(actualTable).append(" (");
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(columns[i]);
        }
        sql.append(")");
        execute(connection, sql.toString());
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
