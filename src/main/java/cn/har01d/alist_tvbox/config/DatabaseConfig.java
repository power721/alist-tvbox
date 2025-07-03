package cn.har01d.alist_tvbox.config;

import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import cn.har01d.alist_tvbox.util.Utils;
import lombok.extern.slf4j.Slf4j;


@Slf4j
@Configuration
public class DatabaseConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSource dataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean
    public DataSource alistDataSource() {
        String path = Utils.getAListPath("data/data.db");
        log.info("use AList database path: {}", path);
        return DataSourceBuilder.create()
                .url("jdbc:sqlite:" + path)
                .driverClassName("org.sqlite.JDBC")
                .build();
    }

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public JdbcTemplate alistJdbcTemplate(@Qualifier("alistDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
