package cn.har01d.alist_tvbox.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SettingService {
    private final JdbcTemplate jdbcTemplate;

    public SettingService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void exportDatabase() {
        jdbcTemplate.execute("SCRIPT TO '/data/data-h2.sql' TABLE ACCOUNT, ALIST_ALIAS, CONFIG_FILE, INDEX_TEMPLATE, PIK_PAK_ACCOUNT, SETTING, SHARE, SITE, SUBSCRIPTION, USERS");
    }
}
