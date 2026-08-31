package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V45:豆瓣 diff 更新执行记录表 movie_diff。
 * <p>
 * 每个 sql 文件(版本号主键)一条:状态(SUCCESS/FAILED)、语句数、失败数、尝试次数。
 * 背景:线上曾出现版本号被先于应用盖章,1317-1339 整批 diff 行永久漏放 —— 有了逐文件
 * 记录,执行规则变为「无记录或 FAILED 且尝试&lt;3 才执行,SUCCESS 跳过」,开机自检即可
 * 补放历史缺口。标识符不加引号(见 V21 教训),DDL 用三方言通用类型。
 */
public class V45__MovieDiffLog extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (findTable(connection, "movie_diff") != null) {
            return;
        }
        execute(connection, "CREATE TABLE movie_diff ("
                + "version VARCHAR(50) NOT NULL PRIMARY KEY, "
                + "status VARCHAR(16) NOT NULL, "
                + "statements INT NOT NULL DEFAULT 0, "
                + "failed INT NOT NULL DEFAULT 0, "
                + "attempts INT NOT NULL DEFAULT 0, "
                + "updated_time TIMESTAMP NOT NULL)");
    }

    private String findTable(Connection connection, String table) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getTables(connection.getCatalog(), null, null, null)) {
            while (rs.next()) {
                if (rs.getString("TABLE_NAME").equalsIgnoreCase(table)) {
                    return rs.getString("TABLE_NAME");
                }
            }
        }
        return null;
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
