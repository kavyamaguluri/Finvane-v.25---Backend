package com.finvane.service.config;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class DynamicConfigService {

    private final JdbcTemplate jdbcTemplate;
    private final JdbcTemplate controlJdbcTemplate;

    public DynamicConfigService(JdbcTemplate jdbcTemplate, JdbcTemplate controlJdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.controlJdbcTemplate = controlJdbcTemplate;
    }

    @Cacheable(value = "menuItems")
    public List<Map<String, Object>> getActiveMenuItems() {
        return controlJdbcTemplate.queryForList("""
            SELECT id, menu_key, parent_key, label, url, icon, permission_key, sort_order, is_active, service_key
            FROM config_menu_items
            WHERE is_active = 1
            ORDER BY sort_order, label
        """);
    }

    @Cacheable(value = "states")
    public List<Map<String, Object>> getActiveStates() {
        return controlJdbcTemplate.queryForList("""
            SELECT code, name, is_active
            FROM config_states
            WHERE is_active = 1
            ORDER BY code
        """);
    }

    @Cacheable(value = "lookups", key = "#category")
    public List<Map<String, Object>> getLookupsByCategory(String category) {
        return jdbcTemplate.queryForList("""
            SELECT code, label, sort_order, is_active, is_system
            FROM config_lookups
            WHERE category = ? AND is_active = 1
            ORDER BY sort_order, label
        """, category);
    }

    @Cacheable(value = "gstRates")
    public List<Map<String, Object>> getActiveGstRates() {
        return jdbcTemplate.queryForList("""
            SELECT rate_pct, label, is_active
            FROM config_gst_rates
            WHERE is_active = 1
            ORDER BY rate_pct
        """);
    }

    @Cacheable(value = "tdsSections")
    public List<Map<String, Object>> getActiveTdsSections() {
        return jdbcTemplate.queryForList("""
            SELECT section_code, description, rate_individual, rate_company, threshold_amount
            FROM config_tds_sections
            WHERE is_active = 1
            ORDER BY section_code
        """);
    }

    @Cacheable(value = "services")
    public List<Map<String, Object>> getServiceDetails() {
        return jdbcTemplate.queryForList("""
            SELECT s.service_key, s.service_group, s.label, s.hint,
                   COALESCE(p.is_enabled, s.is_active) AS is_enabled,
                   p.period_from, p.period_to
            FROM config_services s
            LEFT JOIN config_service_periods p ON p.service_key = s.service_key
            ORDER BY s.id
        """);
    }

    @CacheEvict(value = {"menuItems", "states", "lookups", "gstRates", "tdsSections", "services"}, allEntries = true)
    public void evictCache() {
        // Cache refreshed on admin updates
    }
}

