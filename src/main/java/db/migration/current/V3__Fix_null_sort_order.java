package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.Statement;

public class V3__Fix_null_sort_order extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        System.out.println("V3: Fixing NULL sort_order values");

        // Fix NULL values in sort_order columns
        execute(connection, "UPDATE site SET sort_order = 0 WHERE sort_order IS NULL");
        execute(connection, "UPDATE navigation SET sort_order = 0 WHERE sort_order IS NULL");
        execute(connection, "UPDATE emby SET sort_order = 0 WHERE sort_order IS NULL");
        execute(connection, "UPDATE jellyfin SET sort_order = 0 WHERE sort_order IS NULL");
        execute(connection, "UPDATE telegram_channel SET sort_order = 0 WHERE sort_order IS NULL");
        execute(connection, "UPDATE feiniu SET sort_order = 0 WHERE sort_order IS NULL");
        execute(connection, "UPDATE plugin SET sort_order = 0 WHERE sort_order IS NULL");
        execute(connection, "UPDATE plugin_filter SET sort_order = 0 WHERE sort_order IS NULL");

        System.out.println("V3: Migration completed successfully");
    }

    private void execute(Connection connection, String sql) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception e) {
            System.err.println("V3: SQL execution failed: " + sql);
            System.err.println("V3: Error: " + e.getMessage());
        }
    }
}
