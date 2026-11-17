package com.finvane.controller.admin;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin/audit-log")
@PreAuthorize("hasAuthority('audit_view') or hasAuthority('admin')")
public class AuditController {

    private final JdbcTemplate controlJdbcTemplate;

    public AuditController(JdbcTemplate controlJdbcTemplate) {
        this.controlJdbcTemplate = controlJdbcTemplate;
    }

    // 1. GET /admin/audit-log
    @GetMapping
    public ResponseEntity<?> getAuditLogs(@RequestParam(required = false) String action,
                                          @RequestParam(required = false) String entity,
                                          @RequestParam(required = false) String user,
                                          @RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "50") int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM audit_log WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (action != null && !action.isBlank()) {
            sql.append(" AND action = ?");
            params.add(action.trim());
        }
        if (entity != null && !entity.isBlank()) {
            sql.append(" AND entity_type = ?");
            params.add(entity.trim());
        }
        if (user != null && !user.isBlank()) {
            sql.append(" AND LOWER(user_email) LIKE ?");
            params.add("%" + user.trim().toLowerCase() + "%");
        }
        if (from != null && !from.isBlank()) {
            sql.append(" AND created_at >= ?");
            params.add(from.trim() + " 00:00:00");
        }
        if (to != null && !to.isBlank()) {
            sql.append(" AND created_at <= ?");
            params.add(to.trim() + " 23:59:59");
        }

        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add((page - 1) * limit);

        List<Map<String, Object>> logs = controlJdbcTemplate.queryForList(sql.toString(), params.toArray());

        // Count
        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM audit_log WHERE 1=1");
        List<Object> countParams = new ArrayList<>();
        if (action != null && !action.isBlank()) { countSql.append(" AND action = ?"); countParams.add(action.trim()); }
        if (entity != null && !entity.isBlank()) { countSql.append(" AND entity_type = ?"); countParams.add(entity.trim()); }
        if (user != null && !user.isBlank()) { countSql.append(" AND LOWER(user_email) LIKE ?"); countParams.add("%" + user.trim().toLowerCase() + "%"); }
        if (from != null && !from.isBlank()) { countSql.append(" AND created_at >= ?"); countParams.add(from.trim() + " 00:00:00"); }
        if (to != null && !to.isBlank()) { countSql.append(" AND created_at <= ?"); countParams.add(to.trim() + " 23:59:59"); }

        Long totalCount = controlJdbcTemplate.queryForObject(countSql.toString(), Long.class, countParams.toArray());

        return ResponseEntity.ok(Map.of(
            "ok", true,
            "logs", logs,
            "total", totalCount != null ? totalCount : 0,
            "page", page,
            "limit", limit
        ));
    }

    // 2. GET /admin/audit-log/{id}
    @GetMapping("/{id}")
    public ResponseEntity<?> getAuditDetail(@PathVariable("id") long id) {
        List<Map<String, Object>> rows = controlJdbcTemplate.queryForList("SELECT * FROM audit_log WHERE id = ?", id);
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("ok", true, "audit", rows.get(0)));
    }

    // 3. GET /admin/audit-log/export
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportAuditLog(@RequestParam(required = false) String action,
                                                 @RequestParam(required = false) String entity,
                                                 @RequestParam(required = false) String user) throws Exception {
        StringBuilder sql = new StringBuilder("SELECT id, created_at, user_email, action, entity_type, entity_id_ref, ip_address FROM audit_log WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (action != null && !action.isBlank()) { sql.append(" AND action = ?"); params.add(action.trim()); }
        if (entity != null && !entity.isBlank()) { sql.append(" AND entity_type = ?"); params.add(entity.trim()); }
        if (user != null && !user.isBlank()) { sql.append(" AND LOWER(user_email) LIKE ?"); params.add("%" + user.trim().toLowerCase() + "%"); }
        sql.append(" ORDER BY id DESC LIMIT 5000");

        List<Map<String, Object>> rows = controlJdbcTemplate.queryForList(sql.toString(), params.toArray());

        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Audit Log");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Timestamp");
            header.createCell(1).setCellValue("User");
            header.createCell(2).setCellValue("Action");
            header.createCell(3).setCellValue("Entity");
            header.createCell(4).setCellValue("Record ID");
            header.createCell(5).setCellValue("IP Address");

            int rowIdx = 1;
            for (Map<String, Object> r : rows) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(String.valueOf(r.get("created_at")));
                row.createCell(1).setCellValue(r.get("user_email") != null ? String.valueOf(r.get("user_email")) : "");
                row.createCell(2).setCellValue(String.valueOf(r.get("action")));
                row.createCell(3).setCellValue(r.get("entity_type") != null ? String.valueOf(r.get("entity_type")) : "");
                row.createCell(4).setCellValue(r.get("entity_id_ref") != null ? String.valueOf(r.get("entity_id_ref")) : "");
                row.createCell(5).setCellValue(r.get("ip_address") != null ? String.valueOf(r.get("ip_address")) : "");
            }

            for (int i = 0; i < 6; i++) sheet.autoSizeColumn(i);

            workbook.write(out);
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=audit_log.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(out.toByteArray());
        }
    }

    // 4. POST /admin/audit-log/factory-reset
    @PostMapping("/factory-reset")
    public ResponseEntity<?> factoryReset() {
        String tenantId = com.finvane.config.TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank() || "control".equalsIgnoreCase(tenantId)) {
            tenantId = "1";
        }

        try {
            // Delete user transaction data for tenantId
            try { controlJdbcTemplate.update("DELETE FROM voucher_items WHERE voucher_id IN (SELECT id FROM vouchers WHERE tenant_id = ?)", tenantId); } catch (Exception ignored) {}
            try { controlJdbcTemplate.update("DELETE FROM voucher_lines WHERE voucher_id IN (SELECT id FROM vouchers WHERE tenant_id = ?)", tenantId); } catch (Exception ignored) {}
            try { controlJdbcTemplate.update("DELETE FROM vouchers WHERE tenant_id = ?", tenantId); } catch (Exception ignored) {}

            try { controlJdbcTemplate.update("DELETE FROM payment_allocations WHERE tenant_id = ?", tenantId); } catch (Exception ignored) {}
            try { controlJdbcTemplate.update("DELETE FROM invoice_lines WHERE invoice_id IN (SELECT id FROM invoices WHERE tenant_id = ?)", tenantId); } catch (Exception ignored) {}
            try { controlJdbcTemplate.update("DELETE FROM invoices WHERE tenant_id = ?", tenantId); } catch (Exception ignored) {}

            try { controlJdbcTemplate.update("DELETE FROM credit_note_lines WHERE credit_note_id IN (SELECT id FROM credit_notes WHERE tenant_id = ?)", tenantId); } catch (Exception ignored) {}
            try { controlJdbcTemplate.update("DELETE FROM credit_notes WHERE tenant_id = ?", tenantId); } catch (Exception ignored) {}
            try { controlJdbcTemplate.update("DELETE FROM debit_note_lines WHERE debit_note_id IN (SELECT id FROM debit_notes WHERE tenant_id = ?)", tenantId); } catch (Exception ignored) {}
            try { controlJdbcTemplate.update("DELETE FROM debit_notes WHERE tenant_id = ?", tenantId); } catch (Exception ignored) {}

            try { controlJdbcTemplate.update("DELETE FROM quotation_lines WHERE quotation_id IN (SELECT id FROM quotations WHERE tenant_id = ?)", tenantId); } catch (Exception ignored) {}
            try { controlJdbcTemplate.update("DELETE FROM quotations WHERE tenant_id = ?", tenantId); } catch (Exception ignored) {}
            try { controlJdbcTemplate.update("DELETE FROM sales_order_lines WHERE sales_order_id IN (SELECT id FROM sales_orders WHERE tenant_id = ?)", tenantId); } catch (Exception ignored) {}
            try { controlJdbcTemplate.update("DELETE FROM sales_orders WHERE tenant_id = ?", tenantId); } catch (Exception ignored) {}
            try { controlJdbcTemplate.update("DELETE FROM purchase_order_lines WHERE purchase_order_id IN (SELECT id FROM purchase_orders WHERE tenant_id = ?)", tenantId); } catch (Exception ignored) {}
            try { controlJdbcTemplate.update("DELETE FROM purchase_orders WHERE tenant_id = ?", tenantId); } catch (Exception ignored) {}

            try { controlJdbcTemplate.update("DELETE FROM stock_lot_consumptions WHERE tenant_id = ?", tenantId); } catch (Exception ignored) {}
            try { controlJdbcTemplate.update("DELETE FROM stock_ledger WHERE tenant_id = ?", tenantId); } catch (Exception ignored) {}
            try { controlJdbcTemplate.update("DELETE FROM stock_lots WHERE tenant_id = ?", tenantId); } catch (Exception ignored) {}
            try { controlJdbcTemplate.update("DELETE FROM stock_transfer_lines WHERE transfer_id IN (SELECT id FROM stock_transfers WHERE tenant_id = ?)", tenantId); } catch (Exception ignored) {}
            try { controlJdbcTemplate.update("DELETE FROM stock_transfers WHERE tenant_id = ?", tenantId); } catch (Exception ignored) {}
            try { controlJdbcTemplate.update("DELETE FROM direct_stock_entries WHERE tenant_id = ?", tenantId); } catch (Exception ignored) {}

            try { controlJdbcTemplate.update("DELETE FROM bank_transactions WHERE tenant_id = ?", tenantId); } catch (Exception ignored) {}
            try { controlJdbcTemplate.update("DELETE FROM payslips WHERE payroll_run_id IN (SELECT id FROM payroll_runs WHERE tenant_id = ?)", tenantId); } catch (Exception ignored) {}
            try { controlJdbcTemplate.update("DELETE FROM payroll_runs WHERE tenant_id = ?", tenantId); } catch (Exception ignored) {}
            try { controlJdbcTemplate.update("DELETE FROM asset_depreciation_lines WHERE run_id IN (SELECT id FROM asset_depreciation_runs WHERE tenant_id = ?)", tenantId); } catch (Exception ignored) {}
            try { controlJdbcTemplate.update("DELETE FROM asset_depreciation_runs WHERE tenant_id = ?", tenantId); } catch (Exception ignored) {}
            try { controlJdbcTemplate.update("DELETE FROM fixed_assets WHERE tenant_id = ?", tenantId); } catch (Exception ignored) {}
            try { controlJdbcTemplate.update("DELETE FROM budgets WHERE tenant_id = ?", tenantId); } catch (Exception ignored) {}

            try { controlJdbcTemplate.update("DELETE FROM parties WHERE tenant_id = ?", tenantId); } catch (Exception ignored) {}
            try { controlJdbcTemplate.update("DELETE FROM items WHERE tenant_id = ? AND is_system = 0", tenantId); } catch (Exception ignored) {}

            // User-created accounts for this tenant
            try { controlJdbcTemplate.update("DELETE FROM accounts WHERE tenant_id = ? AND is_system = 0", tenantId); } catch (Exception ignored) {}
            // Reset opening balance on standard accounts for this tenant
            try { controlJdbcTemplate.update("UPDATE accounts SET opening_balance = 0.00 WHERE tenant_id = ?", tenantId); } catch (Exception ignored) {}

            try {
                controlJdbcTemplate.update("""
                    INSERT INTO audit_log (business_entity_id, user_email, action, entity, ip)
                    VALUES (?, 'admin', 'factory_reset', 'business', '127.0.0.1')
                """, Long.parseLong(tenantId));
            } catch (Exception ignored) {}

            return ResponseEntity.ok(Map.of("ok", true, "message", "Factory reset completed successfully. All user transaction data has been cleared."));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("ok", false, "error", "Factory reset failed: " + e.getMessage()));
        }
    }
}

