package cn.har01d.alist_tvbox.config;

import org.hibernate.dialect.H2Dialect;

/**
 * Custom H2 dialect that preserves identifier case to match H2's uppercase storage.
 */
public class CustomH2Dialect extends H2Dialect {

    @Override
    public String quote(String name) {
        // Don't quote identifiers - let H2 store them as uppercase
        // This makes Hibernate's unquoted lowercase references match H2's uppercase storage
        // via H2's case-insensitive matching
        return name;
    }

    @Override
    protected String getCreateSequenceString(String sequenceName) {
        return "create sequence if not exists " + sequenceName;
    }

    @Override
    protected String getDropSequenceString(String sequenceName) {
        return "drop sequence if exists " + sequenceName;
    }
}
