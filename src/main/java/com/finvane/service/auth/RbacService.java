package com.finvane.service.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RbacService {

    private final JdbcTemplate controlJdbcTemplate;

    public static final List<String> ALL_ROLES = List.of(
            "admin",
            "accountant",
            "approver",
            "sales",
            "inventory_manager",
            "payroll_manager",
            "auditor"
    );

    public static final List<String> ALL_PERMISSIONS = List.of(
            "accounts_manage",
            "vouchers_create",
            "vouchers_approve",
            "reports_view",
            "invoices_manage",
            "inventory_manage",
            "payroll_manage",
            "banking_manage",
            "users_manage",
            "audit_view",
            "dashboard_view",
            "locations_manage",
            "stock_transfers_manage",
            "orders_manage",
            "notes_manage",
            "assets_manage",
            "budgets_manage",
            "compliance_manage",
            "attachments_manage",
            "financial_model_view"
    );

    public RbacService(JdbcTemplate controlJdbcTemplate) {
        this.controlJdbcTemplate = controlJdbcTemplate;
    }

    /**
     * Resolves effective permissions for a user given their role and per-user overrides.
     */
    public List<String> getEffectivePermissions(String role, String permissionsJson) {
        if ("admin".equalsIgnoreCase(role)) {
            return new ArrayList<>(ALL_PERMISSIONS);
        }

        Set<String> effective = new HashSet<>();

        // 1. Fetch role permissions from control DB
        try {
            String sql = """
                SELECT p.permission_key 
                FROM config_permissions p
                JOIN config_role_permissions rp ON rp.permission_id = p.id
                JOIN config_roles r ON r.id = rp.role_id
                WHERE r.role_key = ?
            """;
            List<String> rolePerms = controlJdbcTemplate.queryForList(sql, String.class, role.toLowerCase());
            effective.addAll(rolePerms);
        } catch (Exception ignored) {
        }
        if (effective.isEmpty() && role != null) {
            // Fallback to in-memory default matrix when DB role mapping has no rows
            effective.addAll(getDefaultRolePermissions(role));
        }

        // 2. Apply per-user overrides from permissions_json
        if (permissionsJson != null && !permissionsJson.isBlank()) {
            try {
                // Simple parser for { "perm_key": true/false }
                String clean = permissionsJson.replace("{", "").replace("}", "").trim();
                if (!clean.isEmpty()) {
                    String[] entries = clean.split(",");
                    for (String entry : entries) {
                        String[] kv = entry.split(":");
                        if (kv.length == 2) {
                            String perm = kv[0].replace("\"", "").trim();
                            boolean allowed = Boolean.parseBoolean(kv[1].trim());
                            if (allowed) {
                                effective.add(perm);
                            } else {
                                effective.remove(perm);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        return new ArrayList<>(effective);
    }

    private List<String> getDefaultRolePermissions(String role) {
        return switch (role.toLowerCase()) {
            case "accountant" -> List.of("accounts_manage", "vouchers_create", "reports_view", "invoices_manage", "inventory_manage", "banking_manage", "dashboard_view", "locations_manage", "orders_manage", "notes_manage", "assets_manage", "budgets_manage", "compliance_manage", "attachments_manage", "financial_model_view");
            case "approver" -> List.of("vouchers_approve", "dashboard_view");
            case "sales" -> List.of("invoices_manage", "dashboard_view", "orders_manage", "notes_manage", "attachments_manage");
            case "inventory_manager" -> List.of("inventory_manage", "dashboard_view", "locations_manage", "stock_transfers_manage", "orders_manage", "attachments_manage");
            case "payroll_manager" -> List.of("payroll_manage", "dashboard_view");
            case "auditor" -> List.of("reports_view", "audit_view", "dashboard_view", "financial_model_view");
            default -> List.of();
        };
    }
}

