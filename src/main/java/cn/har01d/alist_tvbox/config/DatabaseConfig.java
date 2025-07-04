package cn.har01d.alist_tvbox.config;

import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Configuration
public class DatabaseConfig {
    private final ObjectMapper objectMapper;

    public DatabaseConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSource dataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public DataSource alistDataSource() throws IOException {
        Path path = Path.of(Utils.getAListPath("data/config.json"));
        String text = Files.readString(path);
        var json = objectMapper.readTree(text);
        var database = json.get("database");
        String type = database.get("type").asText();
        if ("sqlite3".equals(type)) {
            String dbFile = Utils.getAListPath(database.get("db_file").asText());
            log.info("AList use sqlite3 database file: {}", dbFile);

            return DataSourceBuilder.create()
                    .url("jdbc:sqlite:" + dbFile)
                    .driverClassName("org.sqlite.JDBC")
                    .build();
        } else if ("mysql".equals(type)) {
            String url = generateMysqlJdbcUrl(database);
            log.info("AList use mysql database url: {}", url);
            return DataSourceBuilder.create()
                    .url(url)
                    .username(database.get("user").asText())
                    .password(database.get("password").asText())
                    .driverClassName("com.mysql.cj.jdbc.Driver")
                    .build();
        } else {
            throw new IllegalArgumentException("unknown database type: " + type);
        }
    }

    private static String generateMysqlJdbcUrl(JsonNode databaseConfig) {
        if (databaseConfig == null || !databaseConfig.has("type") ||
                !"mysql".equalsIgnoreCase(databaseConfig.get("type").asText())) {
            throw new IllegalArgumentException("Database configuration is not for MySQL");
        }

        StringBuilder url = new StringBuilder("jdbc:mysql://");

        String host = databaseConfig.has("host") ? databaseConfig.get("host").asText() : "";
        if (host.isEmpty()) {
            host = "localhost";
        }
        url.append(host);

        int port = databaseConfig.has("port") ? databaseConfig.get("port").asInt() : 0;
        if (port <= 0) {
            port = 3306;
        }
        url.append(":").append(port);

        if (databaseConfig.has("name")) {
            String dbName = databaseConfig.get("name").asText();
            if (!dbName.isEmpty()) {
                url.append("/").append(dbName);
            }
        }

        StringBuilder params = new StringBuilder();

        if (databaseConfig.has("ssl_mode")) {
            String sslMode = databaseConfig.get("ssl_mode").asText();
            if (!sslMode.isEmpty()) {
                params.append("useSSL=").append("require".equalsIgnoreCase(sslMode));
            }
        }

        if (params.isEmpty()) {
            params.append("useSSL=false");
        }

        params.append("&serverTimezone=Asia/Shanghai").append("&characterEncoding=UTF-8");
        url.append("?").append(params);

        return url.toString();
    }

    public static String generatePostgresJdbcUrl(JsonNode databaseConfig) {
        String host = databaseConfig.path("host").asText("localhost");
        int port = databaseConfig.path("port").asInt(5432);
        String database = databaseConfig.path("name").asText();

        if (database.isEmpty()) {
            throw new IllegalArgumentException("Database name is required for PostgreSQL");
        }

        String url = String.format("jdbc:postgresql://%s:%d/%s", host, port, database);

        List<String> params = new ArrayList<>();

        String sslMode = databaseConfig.path("ssl_mode").asText().toLowerCase();
        if (!sslMode.isEmpty()) {
            params.add("sslmode=" + sslMode);
        } else {
            params.add("sslmode=disable");
        }

        params.add("ApplicationName=Alist");

        if (!params.isEmpty()) {
            url += "?" + String.join("&", params);
        }

        return url;
    }

    @Bean
    public JdbcTemplate alistJdbcTemplate(@Qualifier("alistDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
