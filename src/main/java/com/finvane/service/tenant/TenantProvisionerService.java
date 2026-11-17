package com.finvane.service.tenant;

import com.finvane.config.TenantRoutingDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.stream.Collectors;

@Service
public class TenantProvisionerService {

    private final DataSource controlDataSource;
    private final TenantRoutingDataSource routingDataSource;

    @Value("${DB_HOST:localhost}")
    private String dbHost;

    @Value("${DB_PORT:3306}")
    private int dbPort;

    @Value("${DB_USER:root}")
    private String dbUser;

    @Value("${DB_PASS:root_secret_password}")
    private String dbPassword;

    public TenantProvisionerService(DataSource controlDataSource,
                                    TenantRoutingDataSource routingDataSource) {
        this.controlDataSource = controlDataSource;
        this.routingDataSource = routingDataSource;
    }

    /**
     * Provisions a new business entity in single-database mode (finvane_control only).
     * No fv_<entity_id> databases are created; all tables reside in finvane_control.
     */
    public synchronized void provisionTenant(long entityId) {
        // Single database mode: No separate databases or views created.
    }

    private String loadResourceSql(String resourcePath) {
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Resource not found: " + resourcePath);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed reading " + resourcePath, e);
        }
    }

    private void executeSqlScript(Statement stmt, String sqlScript) throws Exception {
        // Remove comments
        String cleanSql = sqlScript.replaceAll("(?m)^--.*$", "");
        String[] statements = cleanSql.split(";");
        for (String sql : statements) {
            String trimmed = sql.trim();
            if (!trimmed.isEmpty()) {
                stmt.execute(trimmed);
            }
        }
    }
}

