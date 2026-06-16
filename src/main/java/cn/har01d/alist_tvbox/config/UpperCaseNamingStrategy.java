package cn.har01d.alist_tvbox.config;

import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.naming.PhysicalNamingStrategy;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;

/**
 * Database-aware naming strategy:
 * - H2: converts to uppercase to match H2's uppercase storage
 * - MySQL: preserves original case (lowercase) to match Linux MySQL
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
        return convertIdentifier(name, jdbcEnvironment);
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

        // For H2: convert to uppercase to match H2's uppercase storage
        if (databaseName.contains("h2")) {
            return Identifier.toIdentifier(identifier.getText().toUpperCase());
        }

        // For MySQL and others: keep original case (lowercase from @Column annotations)
        return identifier;
    }
}
