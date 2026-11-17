package com.finvane.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic Multi-Tenant Routing DataSource.
 * Routes connection requests to finvane_control or tenant databases named fv_<entity_id>.
 */
public class TenantRoutingDataSource extends AbstractRoutingDataSource {

    private final DataSource defaultControlDataSource;
    private final String dbHost;
    private final int dbPort;
    private final String dbUser;
    private final String dbPassword;
    private final Map<Object, Object> resolvedTenantDataSources = new ConcurrentHashMap<>();

    public TenantRoutingDataSource(DataSource defaultControlDataSource,
                                   String dbHost,
                                   int dbPort,
                                   String dbUser,
                                   String dbPassword) {
        this.defaultControlDataSource = defaultControlDataSource;
        this.dbHost = dbHost;
        this.dbPort = dbPort;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;

        setDefaultTargetDataSource(defaultControlDataSource);
        setTargetDataSources(resolvedTenantDataSources);
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return TenantContext.getTenantId();
    }

    @Override
    protected DataSource determineTargetDataSource() {
        // Single database mode: all operations use finvane_control exclusively
        return defaultControlDataSource;
    }

    private DataSource createTenantDataSource(Object tenantKey) {
        return defaultControlDataSource;
    }

    /**
     * Drops a tenant database if provisioning fails. In single-database mode, this is a no-op.
     */
    public void dropTenantDatabase(String tenantId) {
        // No separate tenant databases in single-database mode
    }
}

