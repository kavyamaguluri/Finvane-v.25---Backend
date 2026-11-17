package com.finvane.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.stream.Collectors;

@Configuration
public class DataSourceConfig {

    @Value("${DB_HOST:localhost}")
    private String dbHost;

    @Value("${DB_PORT:3306}")
    private int dbPort;

    @Value("${DB_USER:root}")
    private String dbUser;

    @Value("${DB_PASS:root_secret_password}")
    private String dbPassword;

    @Value("${app.multitenancy.control-db:finvane_control}")
    private String controlDbName;

    @Bean(name = "controlDataSource")
    public DataSource controlDataSource() {
        String jdbcUrl = String.format("jdbc:mysql://%s:%d/%s?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Kolkata&sessionVariables=sql_mode='STRICT_TRANS_TABLES,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO'",
                dbHost, dbPort, controlDbName);

        HikariConfig config = new HikariConfig();
        config.setPoolName("HikariPool-Control");
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(dbUser);
        config.setPassword(dbPassword);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(3);
        config.setIdleTimeout(300000);
        config.setMaxLifetime(1800000);
        config.setConnectionTimeout(20000);

        return new HikariDataSource(config);
    }

    @Bean
    @Primary
    public TenantRoutingDataSource routingDataSource(@org.springframework.beans.factory.annotation.Qualifier("controlDataSource") DataSource controlDataSource) {
        return new TenantRoutingDataSource(controlDataSource, dbHost, dbPort, dbUser, dbPassword);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(TenantRoutingDataSource routingDataSource) {
        return new JdbcTemplate(routingDataSource);
    }

    @Bean(name = "controlJdbcTemplate")
    public JdbcTemplate controlJdbcTemplate(@org.springframework.beans.factory.annotation.Qualifier("controlDataSource") DataSource controlDataSource) {
        return new JdbcTemplate(controlDataSource);
    }

    @Bean
    public org.springframework.boot.ApplicationRunner initControlDatabaseRunner(@org.springframework.beans.factory.annotation.Qualifier("controlDataSource") DataSource controlDataSource) {
        return args -> {
            try (Connection conn = controlDataSource.getConnection();
                 Statement stmt = conn.createStatement()) {

                // Run Control V1 Schema
                String v1 = loadResourceSql("/db/migration/control/V1__init_control_schema.sql");
                executeSqlScript(stmt, v1);

                // Run Control V2 Dynamic Config Seeds
                String v2 = loadResourceSql("/db/migration/control/V2__seed_dynamic_config.sql");
                executeSqlScript(stmt, v2);
            } catch (Exception e) {
                // Log notice if already initialized
                System.err.println("Control DB init notice: " + e.getMessage());
            }
        };
    }

    private String loadResourceSql(String resourcePath) {
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) return "";
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            return "";
        }
    }

    private void executeSqlScript(Statement stmt, String sqlScript) throws Exception {
        String cleanSql = sqlScript.replaceAll("(?m)^--.*$", "");
        String[] statements = cleanSql.split(";");
        for (String sql : statements) {
            String trimmed = sql.trim();
            if (!trimmed.isEmpty()) {
                try {
                    stmt.execute(trimmed);
                } catch (Exception ignored) {
                }
            }
        }
    }
}

