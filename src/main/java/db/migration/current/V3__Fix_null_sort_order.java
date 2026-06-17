package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

public class V3__Fix_null_sort_order extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        System.out.println("V3: Fixing NULL sort_order values and normalizing column names");

        // Fix NULL values in sort_order columns
        execute(connection, "UPDATE site SET sort_order = 0 WHERE sort_order IS NULL");
        execute(connection, "UPDATE navigation SET sort_order = 0 WHERE sort_order IS NULL");
        execute(connection, "UPDATE emby SET sort_order = 0 WHERE sort_order IS NULL");
        execute(connection, "UPDATE jellyfin SET sort_order = 0 WHERE sort_order IS NULL");
        execute(connection, "UPDATE telegram_channel SET sort_order = 0 WHERE sort_order IS NULL");
        execute(connection, "UPDATE feiniu SET sort_order = 0 WHERE sort_order IS NULL");
        execute(connection, "UPDATE plugin SET sort_order = 0 WHERE sort_order IS NULL");
        execute(connection, "UPDATE plugin_filter SET sort_order = 0 WHERE sort_order IS NULL");

        // Normalize setting.SVALUE to svalue (for new installations where H2 created uppercase)
        normalizeSettingColumn(connection);

        // Normalize all reserved word columns to lowercase
        normalizeReservedWordColumns(connection);

        System.out.println("V3: Migration completed successfully");
    }

    private void normalizeSettingColumn(Connection connection) {
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet rs = metaData.getColumns(connection.getCatalog(), null, "SETTING", null)) {
                boolean hasUppercase = false;
                boolean hasLowercase = false;

                while (rs.next()) {
                    String colName = rs.getString("COLUMN_NAME");
                    if ("SVALUE".equals(colName)) {
                        hasUppercase = true;
                    } else if ("svalue".equals(colName)) {
                        hasLowercase = true;
                    }
                }

                if (hasUppercase && !hasLowercase) {
                    // New installation: rename SVALUE to lowercase svalue
                    System.out.println("V3: Renaming SETTING.SVALUE to svalue");
                    execute(connection, "ALTER TABLE SETTING ADD COLUMN \"svalue\" TEXT");
                    execute(connection, "UPDATE SETTING SET \"svalue\" = SVALUE");
                    execute(connection, "ALTER TABLE SETTING DROP COLUMN SVALUE");
                    System.out.println("V3: Successfully normalized SETTING.SVALUE to svalue");
                } else if (hasLowercase) {
                    System.out.println("V3: SETTING.svalue already correct (old user or already migrated)");
                }
            }
        } catch (Exception e) {
            System.err.println("V3: Failed to normalize setting column: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void normalizeReservedWordColumns(Connection connection) {
        // Normalize reserved word columns from uppercase to lowercase
        // This handles new H2 installations where columns are created as uppercase
        normalizeColumn(connection, "MOVIE", "YEAR", "year", "INTEGER");
        normalizeColumn(connection, "META", "YEAR", "year", "INTEGER");
        normalizeColumn(connection, "TMDB_META", "YEAR", "year", "INTEGER");
        normalizeColumn(connection, "TMDB", "YEAR", "year", "INTEGER");
        normalizeColumn(connection, "HISTORY", "KEY", "key", "TEXT");
        normalizeColumn(connection, "NAVIGATION", "VALUE", "value", "VARCHAR(255)");
        normalizeColumn(connection, "PLUGIN", "EXTEND", "extend", "TEXT");
        normalizeColumn(connection, "PLUGIN", "VERSION", "version", "INTEGER");
        normalizeColumn(connection, "PLUGIN_FILTER", "EXTEND", "extend", "TEXT");
        normalizeColumn(connection, "PLUGIN_FILTER", "VERSION", "version", "INTEGER");
    }

    private void normalizeColumn(Connection connection, String table, String upperName, String lowerName, String type) {
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet rs = metaData.getColumns(connection.getCatalog(), null, table, null)) {
                boolean hasUpper = false;
                boolean hasLower = false;

                while (rs.next()) {
                    String colName = rs.getString("COLUMN_NAME");
                    if (upperName.equals(colName)) {
                        hasUpper = true;
                    } else if (lowerName.equals(colName)) {
                        hasLower = true;
                    }
                }

                if (hasUpper && !hasLower) {
                    System.out.println("V3: Normalizing " + table + "." + upperName + " to " + lowerName);
                    execute(connection, "ALTER TABLE " + table + " ADD COLUMN \"" + lowerName + "\" " + type);
                    execute(connection, "UPDATE " + table + " SET \"" + lowerName + "\" = \"" + upperName + "\"");
                    execute(connection, "ALTER TABLE " + table + " DROP COLUMN \"" + upperName + "\"");
                }
            }
        } catch (Exception e) {
            System.err.println("V3: Failed to normalize " + table + "." + upperName + ": " + e.getMessage());
        }
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
