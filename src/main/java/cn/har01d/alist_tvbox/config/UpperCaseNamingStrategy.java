package cn.har01d.alist_tvbox.config;

import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.naming.PhysicalNamingStrategy;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;

import java.util.Locale;

/**
 * Database-aware naming strategy:
 * - H2: converts camelCase to UPPER_CASE to match H2's uppercase storage
 * - MySQL: converts camelCase to lower_case to match Linux MySQL
 */
public class UpperCaseNamingStrategy implements PhysicalNamingStrategy {

    @Override
    public Identifier toPhysicalCatalogName(Identifier name, JdbcEnvironment jdbcEnvironment) {
        return name;
    }

    @Override
    public Identifier toPhysicalSchemaName(Identifier name, JdbcEnvironment jdbcEnvironment) {
        return name;
    }

    @Override
    public Identifier toPhysicalTableName(Identifier name, JdbcEnvironment jdbcEnvironment) {
        // Convert table names to lowercase snake_case to match Flyway SQL
        // ConfigFile -> config_file (not CONFIGFILE)
        if (name == null) {
            return null;
        }
        String snakeCase = camelToSnakeCase(name.getText());
        return Identifier.toIdentifier(snakeCase.toLowerCase(Locale.ROOT));
    }

    @Override
    public Identifier toPhysicalSequenceName(Identifier name, JdbcEnvironment jdbcEnvironment) {
        return convertIdentifier(name, jdbcEnvironment);
    }

    @Override
    public Identifier toPhysicalColumnName(Identifier name, JdbcEnvironment jdbcEnvironment) {
        return convertIdentifier(name, jdbcEnvironment);
    }

    private Identifier convertIdentifier(Identifier identifier, JdbcEnvironment jdbcEnvironment) {
        if (identifier == null) {
            return null;
        }

        String databaseName = jdbcEnvironment.getDialect().getClass().getSimpleName().toLowerCase();
        String text = identifier.getText();

        // Convert camelCase to snake_case first
        String snakeCase = camelToSnakeCase(text);

        // For H2: convert to uppercase
        if (databaseName.contains("h2")) {
            return Identifier.toIdentifier(snakeCase.toUpperCase(Locale.ROOT));
        }

        // For MySQL and others: keep lowercase
        return Identifier.toIdentifier(snakeCase.toLowerCase(Locale.ROOT));
    }

    private String camelToSnakeCase(String str) {
        // If already in snake_case (contains underscore), return as-is
        if (str.contains("_")) {
            return str;
        }

        // Convert camelCase to snake_case
        // Handle consecutive uppercase letters: AListAlias -> alist_alias (not a_list_alias)
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);

            // Add underscore before uppercase letter if:
            // 1. Not at start (i > 0)
            // 2. Previous char is lowercase OR next char is lowercase (end of acronym)
            if (Character.isUpperCase(c) && i > 0) {
                char prev = str.charAt(i - 1);
                boolean hasNext = i + 1 < str.length();
                char next = hasNext ? str.charAt(i + 1) : '\0';

                // Add underscore if previous is lowercase (camelCase boundary)
                // OR if this is end of acronym (next is lowercase): HTMLParser -> html_parser
                if (Character.isLowerCase(prev) || (hasNext && Character.isLowerCase(next))) {
                    result.append('_');
                }
            }
            result.append(c);
        }
        return result.toString();
    }
}
