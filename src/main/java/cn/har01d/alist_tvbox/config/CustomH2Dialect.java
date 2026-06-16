package cn.har01d.alist_tvbox.config;

import org.hibernate.dialect.H2Dialect;

/**
 * Custom H2 dialect that works with H2's uppercase identifier storage.
 * Relies on H2's case-insensitive identifier matching to connect
 * Hibernate's lowercase column references to H2's uppercase storage.
 */
public class CustomH2Dialect extends H2Dialect {

    public CustomH2Dialect() {
        super();
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
