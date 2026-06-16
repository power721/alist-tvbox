package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.Statement;

public class V3__Fix_column_quotes extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String quote = connection.getMetaData().getIdentifierQuoteString();
        
        // Rename columns from backtick-style to double-quote-style
        // Source: use backticks (as they were created in V1)
        // Target: use double quotes (SQL standard)
        
        // Plugin table
        renameColumn(connection, "plugin", "`extend`", quote + "extend" + quote);
        renameColumn(connection, "plugin", "`version`", quote + "version" + quote);
        
        // Plugin_filter table
        renameColumn(connection, "plugin_filter", "`extend`", quote + "extend" + quote);
        renameColumn(connection, "plugin_filter", "`version`", quote + "version" + quote);
        
        // History table
        renameColumn(connection, "history", "`key`", quote + "key" + quote);
        
        // Navigation table
        renameColumn(connection, "navigation", "`value`", quote + "value" + quote);
        
        // Movie table
        renameColumn(connection, "movie", "`year`", quote + "year" + quote);
        
        // Tmdb table
        renameColumn(connection, "tmdb", "`year`", quote + "year" + quote);
        
        // Meta table
        renameColumn(connection, "meta", "`year`", quote + "year" + quote);
        
        // Tmdb_meta table
        renameColumn(connection, "tmdb_meta", "`year`", quote + "year" + quote);
    }
    
    private void renameColumn(Connection connection, String table, String oldName, String newName) throws Exception {
        // H2 syntax: ALTER TABLE table RENAME COLUMN oldName TO newName
        String sql = "ALTER TABLE " + table + " RENAME COLUMN " + oldName + " TO " + newName;
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
