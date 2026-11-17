package com.finvane.controller.settings;

import com.finvane.model.common.ApiResponse;
import com.finvane.model.common.TenantContext;
import com.finvane.service.settings.TenantSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping({"/api/v1/settings", "/settings"})
@RequiredArgsConstructor
public class SettingsController {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final TenantSettingsService tenantSettingsService;

    @GetMapping
    public ApiResponse<Map<String, Object>> getSettings() {
        return getServices();
    }

    @GetMapping("/services")
    public ApiResponse<Map<String, Object>> getServices() {
        String tenantId = TenantContext.getTenantId();
        Map<String, Object> res = tenantSettingsService.getContextFlags(tenantId);
        return ApiResponse.ok(res);
    }

    @PostMapping("/services")
    @PreAuthorize("hasAuthority('admin') or hasAuthority('users_manage')")
    @Transactional
    public ApiResponse<Map<String, Object>> saveServices(@RequestBody Map<String, Object> body) {
        String tenantId = TenantContext.getTenantId();
        String error = tenantSettingsService.saveServices(tenantId, body);
        if (error != null) {
            return ApiResponse.error(error);
        }

        try {
            jdbcTemplate.update(
                "INSERT INTO audit_log (tenant_id, entity_type, entity_id, action, changed_by, detail) VALUES (?, 'settings', 1, 'update:services', 1, ?)",
                tenantId, "updated=" + (body != null ? body.keySet() : Set.of()));
        } catch (Exception ignored) {
            try {
                jdbcTemplate.update(
                    "INSERT INTO audit_log (entity_type, entity_id, action, changed_by, detail) VALUES ('settings', 1, 'update:services', 1, ?)",
                    "updated=" + (body != null ? body.keySet() : Set.of()));
            } catch (Exception ignored2) {}
        }

        Map<String, Object> res = tenantSettingsService.getContextFlags(tenantId);
        res.put("message", "Services saved. The menu shows only the services that are active today.");
        return ApiResponse.ok(res);
    }

    @GetMapping("/factory-reset")
    @PreAuthorize("hasAuthority('admin') or hasAuthority('users_manage')")
    public ApiResponse<Map<String, Object>> getFactoryResetPreview() {
        String tenantId = TenantContext.getTenantId();
        Map<String, Object> counts = new LinkedHashMap<>();
        String[] tables = {"invoices", "invoice_lines", "vouchers", "voucher_lines", "stock_ledger", "stock_lots", "stock_transfers", "payroll_runs", "payslips", "fixed_assets"};
        for (String t : tables) {
            counts.put(t, countTenantRows(t, tenantId));
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("counts", counts);
        res.putAll(counts);
        try {
            List<Map<String, Object>> entities = jdbcTemplate.queryForList("SELECT id, name FROM entities WHERE id = ? LIMIT 1", tenantId);
            if (!entities.isEmpty()) {
                res.put("entity", entities.get(0));
            }
        } catch (Exception ignored) {}
        return ApiResponse.ok(res);
    }

    @PostMapping("/factory-reset")
    @PreAuthorize("hasAuthority('admin') or hasAuthority('users_manage')")
    @Transactional
    public ApiResponse<String> executeFactoryReset(@RequestBody Map<String, Object> body, Authentication authentication) {
        String tenantId = TenantContext.getTenantId();
        String confirmName = body != null && body.get("confirm_name") != null ? String.valueOf(body.get("confirm_name")).trim() : "";
        String password = body != null && body.get("password") != null ? String.valueOf(body.get("password")) : "";

        if (confirmName.isBlank()) {
            return ApiResponse.error("Please type the business name exactly as shown to confirm.");
        }

        try {
            List<String> names = jdbcTemplate.query("SELECT name FROM entities WHERE id = ? LIMIT 1",
                    (rs, r) -> rs.getString("name"), tenantId);
            if (!names.isEmpty() && names.get(0) != null && !names.get(0).trim().equalsIgnoreCase(confirmName)) {
                return ApiResponse.error("Business name confirmation does not match (" + names.get(0) + ").");
            }
        } catch (Exception ignored) {}

        if (authentication != null && !password.isBlank()) {
            try {
                List<String> hashes = jdbcTemplate.query("SELECT password_hash FROM users WHERE LOWER(email) = LOWER(?) LIMIT 1",
                        (rs, r) -> rs.getString("password_hash"), authentication.getName());
                if (!hashes.isEmpty() && !passwordEncoder.matches(password, hashes.get(0))) {
                    return ApiResponse.error("Incorrect password.");
                }
            } catch (Exception ignored) {}
        }

        // Wipe tenant-scoped transaction tables while preserving COA, users, and business profile
        String[] tables = {
            "payment_allocations", "stock_lot_consumptions", "stock_transfer_lines", "stock_transfers",
            "stock_ledger", "stock_lots", "invoice_lines", "credit_notes", "debit_notes", "invoices",
            "quotation_lines", "quotations", "sales_order_lines", "sales_orders", "purchase_order_lines", "purchase_orders",
            "payslips", "payroll_runs", "asset_depreciation_lines", "asset_depreciation_runs", "fixed_assets",
            "bank_transactions", "attachments", "voucher_items", "voucher_lines", "vouchers"
        };

        for (String t : tables) {
            deleteTenantRows(t, tenantId);
        }

        try {
            jdbcTemplate.update(
                "INSERT INTO audit_log (tenant_id, entity_type, entity_id, action, changed_by, detail) VALUES (?, 'business', ?, 'reset:factory_reset', 1, 'Transaction tables cleared')",
                tenantId, tenantId);
        } catch (Exception ignored) {
            try {
                jdbcTemplate.update(
                    "INSERT INTO audit_log (entity_type, entity_id, action, changed_by, detail) VALUES ('business', 1, 'reset:factory_reset', 1, 'Transaction tables cleared')");
            } catch (Exception ignored2) {}
        }

        return ApiResponse.ok("Business data has been reset. Business profile, logins, and standard chart of accounts were preserved.");
    }

    private boolean tableHasColumn(String table, String column) {
        try {
            Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Integer.class, table, column);
            return c != null && c > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private int countTenantRows(String table, String tenantId) {
        try {
            if (tableHasColumn(table, "tenant_id")) {
                Integer c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, tenantId);
                return c != null ? c : 0;
            }
            if (tableHasColumn(table, "entity_id")) {
                Integer c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE entity_id = ?", Integer.class, tenantId);
                return c != null ? c : 0;
            }
            Integer c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            return c != null ? c : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private void deleteTenantRows(String table, String tenantId) {
        try {
            if (tableHasColumn(table, "tenant_id")) {
                jdbcTemplate.update("DELETE FROM " + table + " WHERE tenant_id = ?", tenantId);
            } else if (tableHasColumn(table, "entity_id")) {
                jdbcTemplate.update("DELETE FROM " + table + " WHERE entity_id = ?", tenantId);
            }
        } catch (Exception ignored) {}
    }
}

