package com.finvane.controller.accounts;

import com.finvane.config.TenantContext;
import com.finvane.service.audit.AuditLogService;
import com.finvane.utils.ScheduleIIIUtility;
import com.finvane.utils.SectorMappingUtility;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/accounts")
public class ChartOfAccountsController {

    private final JdbcTemplate jdbcTemplate;
    private final JdbcTemplate controlJdbcTemplate;
    private final AuditLogService auditLogService;
    private final TransactionTemplate transactionTemplate;

    public ChartOfAccountsController(JdbcTemplate jdbcTemplate,
                                     JdbcTemplate controlJdbcTemplate,
                                     AuditLogService auditLogService,
                                     TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.controlJdbcTemplate = controlJdbcTemplate;
        this.auditLogService = auditLogService;
        this.transactionTemplate = transactionTemplate;
    }

    private static final int COA_PAGE_SIZE = 200;
    private static final int SM_PAGE_SIZE = 100;
    private static final int CM_PAGE_SIZE = 100;

    private static final Set<String> SYSTEM_CODES = Set.of(
            "1000", "1100", "1200", "1400", "1401", "1402",
            "2000", "2100", "2200", "2201", "2202",
            "3900", "4000", "5000"
    );

    private static final List<String> SECTORS_LIST = List.of(
            "Manufacturing", "FMCG / Consumer Goods", "Pharma & Life Sciences", "Textiles & Apparel",
            "Auto Components", "Chemicals & Industrial Products", "Agro & Food Processing", "Agriculture",
            "Trading / Retail", "Wholesale / Distribution", "E-commerce",
            "IT & Software Services", "SaaS / Software Products",
            "Professional Services (Legal, Accounting, Consulting)", "Healthcare",
            "Education", "Construction & Real Estate", "Hospitality & Food Service",
            "Transportation & Logistics", "Financial Services", "Media & Entertainment"
    );

    private static final List<String[]> COA_HEADERS = List.of(
            new String[]{"entity_gstin", "Entity GSTIN*"},
            new String[]{"branch_name", "Branch Name*"},
            new String[]{"ledger_type_code", "Type"},
            new String[]{"primary_group", "Ledger Group 1"},
            new String[]{"secondary_group", "Ledger Group 2"},
            new String[]{"ledger_group_3", "Ledger Group 3"},
            new String[]{"ledger_group_4", "Ledger Group 4"},
            new String[]{"id", "Ledger Id*"},
            new String[]{"ledger_code", "Ledger Code"},
            new String[]{"pl_type", "P&L Type"},
            new String[]{"sector_line", "Sector Report Line"},
            new String[]{"ledger_name", "Ledger Name"},
            new String[]{"gst_applicable", "GST Applicable"},
            new String[]{"tds_applicable", "TDS Applicable"},
            new String[]{"tcs_applicable", "TCS Applicable"},
            new String[]{"gst_type_of_supply", "GST Type of Supply"},
            new String[]{"gst_nature_of_supply", "GST Nature of Supply"},
            new String[]{"tds_section_number", "TDS Section Number"},
            new String[]{"tcs_section_number", "TCS Section Number"},
            new String[]{"hsn_code", "HSN Code"},
            new String[]{"igst_rate", "IGST Rate"},
            new String[]{"cgst_rate", "CGST Rate"},
            new String[]{"sgst_rate", "SGST Rate"},
            new String[]{"cess_rate", "Cess Rate"},
            new String[]{"opening_balance", "Opening Balance"},
            new String[]{"rcm_applicable", "RCM Applicability (Y/N)"},
            new String[]{"itc_eligible", "ITC Eligibility (Y/N)"}
    );

    // =========================================================================
    // 4. GET /api/v1/accounts -> listAccount
    // =========================================================================
    @GetMapping
    @PreAuthorize("hasAuthority('reports_view')")
    public ResponseEntity<?> listAccount(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "false") boolean all,
            @RequestParam(required = false) Integer size) {

        String query = q != null ? q.trim() : "";
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank() || "control".equalsIgnoreCase(tenantId)) {
            tenantId = "1";
        }

        String where = "WHERE a.tenant_id = ? " + (!query.isEmpty() ? "AND (LOWER(a.code) LIKE LOWER(?) OR LOWER(a.name) LIKE LOWER(?) OR LOWER(a.ledger_code) LIKE LOWER(?)) " : "");
        List<Object> countParams = new ArrayList<>();
        countParams.add(tenantId);
        if (!query.isEmpty()) {
            countParams.add("%" + query + "%");
            countParams.add("%" + query + "%");
            countParams.add("%" + query + "%");
        }

        Integer totalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM accounts a " + where,
                Integer.class,
                countParams.toArray()
        );
        int total = totalCount != null ? totalCount : 0;
        int effectivePageSize = (all || (size != null && size > COA_PAGE_SIZE)) ? Math.max(total, COA_PAGE_SIZE) : (size != null && size > 0 ? size : COA_PAGE_SIZE);
        int pages = Math.max(1, (int) Math.ceil((double) total / effectivePageSize));
        int currentPage = all ? 1 : Math.min(pages, Math.max(1, page));
        int offset = (currentPage - 1) * effectivePageSize;

        String sql = "SELECT a.*, " +
                "       g.name AS group_name, g.parent_id AS group_parent_id, " +
                "       pg.name AS primary_group_name " +
                "FROM accounts a " +
                "LEFT JOIN account_groups g ON g.id = a.group_id " +
                "LEFT JOIN account_groups pg ON pg.id = COALESCE(g.parent_id, g.id) " +
                where + " " +
                "ORDER BY a.code " +
                "LIMIT ? OFFSET ?";

        List<Object> selectParams = new ArrayList<>(countParams);
        selectParams.add(effectivePageSize);
        selectParams.add(offset);

        List<Map<String, Object>> accounts = jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", rs.getLong("id"));
            map.put("code", rs.getString("code"));
            map.put("name", rs.getString("name"));
            map.put("type", rs.getString("type"));
            map.put("group_id", rs.getObject("group_id"));
            map.put("parent_id", rs.getObject("parent_id"));
            BigDecimal op = rs.getBigDecimal("opening_balance");
            map.put("opening_balance", op != null ? op.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            map.put("is_active", rs.getInt("is_active") == 1);
            map.put("ledger_code", rs.getString("ledger_code"));
            map.put("ledger_type_code", rs.getString("ledger_type_code"));
            map.put("ledger_group_3", rs.getString("ledger_group_3"));
            map.put("ledger_group_4", rs.getString("ledger_group_4"));
            map.put("pl_type", rs.getString("pl_type"));
            map.put("sector_line", rs.getString("sector_line"));
            map.put("gst_applicable", rs.getString("gst_applicable"));
            map.put("tds_applicable", rs.getString("tds_applicable"));
            map.put("tcs_applicable", rs.getString("tcs_applicable"));
            map.put("gst_type_of_supply", rs.getString("gst_type_of_supply"));
            map.put("gst_nature_of_supply", rs.getString("gst_nature_of_supply"));
            map.put("tds_section_number", rs.getString("tds_section_number"));
            map.put("tcs_section_number", rs.getString("tcs_section_number"));
            map.put("hsn_code", rs.getString("hsn_code"));
            map.put("igst_rate", rs.getBigDecimal("igst_rate"));
            map.put("cgst_rate", rs.getBigDecimal("cgst_rate"));
            map.put("sgst_rate", rs.getBigDecimal("sgst_rate"));
            map.put("cess_rate", rs.getBigDecimal("cess_rate"));
            map.put("rcm_applicable", rs.getString("rcm_applicable"));
            map.put("itc_eligible", rs.getString("itc_eligible"));
            map.put("branch_name", rs.getString("branch_name"));

            String pGroup = rs.getString("primary_group_name");
            Long gParentId = rs.getObject("group_parent_id") != null ? rs.getLong("group_parent_id") : null;
            String gName = rs.getString("group_name");

            Map<String, String> labels = new HashMap<>();
            labels.put("primary", pGroup != null ? pGroup : "—");
            labels.put("secondary", gParentId != null ? gName : null);
            map.put("groupLabels", labels);

            return map;
        }, selectParams.toArray());

        boolean inventoryEnabled = getSetting("inventory_enabled", "1").equals("1");

        BigDecimal openingBalanceTotal = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(opening_balance), 0) FROM accounts WHERE tenant_id = ?",
                BigDecimal.class,
                tenantId
        );
        if (openingBalanceTotal == null) openingBalanceTotal = BigDecimal.ZERO;
        openingBalanceTotal = openingBalanceTotal.setScale(2, RoundingMode.HALF_UP);

        Map<String, String> sectorLineLabels = new LinkedHashMap<>();
        SectorMappingUtility.LINES.forEach((k, v) -> sectorLineLabels.put(k, v.getLabel()));

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "accounts", accounts,
                "inventoryEnabled", inventoryEnabled,
                "openingBalanceTotal", openingBalanceTotal,
                "sectorLineLabels", sectorLineLabels,
                "q", query,
                "page", currentPage,
                "pages", pages,
                "total", total,
                "pageSize", COA_PAGE_SIZE
        ));
    }

    // =========================================================================
    // 5. POST /api/v1/accounts/{id}/opening-balance -> openingBalanceAccount
    // =========================================================================
    @PostMapping("/{id}/opening-balance")
    @PreAuthorize("hasAuthority('accounts_manage')")
    public ResponseEntity<?> openingBalanceAccount(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM accounts WHERE id = ?", id);
        if (rows.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", "Account not found."));
        }
        Map<String, Object> oldAcc = rows.get(0);

        Object obObj = body.get("opening_balance");
        if (obObj == null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Opening balance must be a number."));
        }

        BigDecimal amount;
        try {
            amount = new BigDecimal(obObj.toString()).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Opening balance must be a number."));
        }

        jdbcTemplate.update("UPDATE accounts SET opening_balance = ? WHERE id = ?", amount, id);
        auditLogService.logAction("update", "account", String.valueOf(id),
                Map.of("opening_balance", oldAcc.get("opening_balance")),
                Map.of("opening_balance", amount), request);

        return ResponseEntity.ok(Map.of("ok", true, "message", "Opening balance updated successfully."));
    }

    // =========================================================================
    // 6. POST /api/v1/accounts/settings/inventory -> inventoryAccount
    // =========================================================================
    @PostMapping("/settings/inventory")
    @PreAuthorize("hasAuthority('accounts_manage')")
    public ResponseEntity<?> inventoryAccount(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Object val = body.get("inventory_enabled");
        boolean enabled = "1".equals(String.valueOf(val)) || Boolean.TRUE.equals(val);
        setSetting("inventory_enabled", enabled ? "1" : "0");
        auditLogService.logAction("update", "app_settings", null, null, Map.of("inventory_enabled", enabled), request);

        return ResponseEntity.ok(Map.of("ok", true, "inventoryEnabled", enabled));
    }

    // =========================================================================
    // 7. GET /api/v1/accounts/form-options -> getAccountFormOptions
    // =========================================================================
    @GetMapping("/form-options")
    @PreAuthorize("hasAuthority('accounts_manage')")
    public ResponseEntity<?> getAccountFormOptions() {
        List<Map<String, Object>> groupOptions = getGroupOptions();
        Map<String, Object> groupData = getGroupPickerData();
        List<String> natures = List.of("asset", "liability", "equity", "income", "expense");
        List<String> cashFlowCategories = List.of("operating", "investing", "financing", "cash_equivalent");

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "groupOptions", groupOptions,
                "groupData", groupData,
                "natures", natures,
                "cashFlowCategories", cashFlowCategories
        ));
    }

    // =========================================================================
    // 8. POST /api/v1/accounts -> createAccount
    // =========================================================================
    @PostMapping
    @PreAuthorize("hasAuthority('accounts_manage')")
    public ResponseEntity<?> createAccount(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String code = String.valueOf(body.getOrDefault("code", "")).trim();
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        if (code.isEmpty() || name.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "code and name are required."));
        }

        Map<String, Object> group = pickGroupFromBody(body);
        if (group == null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Select a valid group (Level 1, and Level 2 if needed)."));
        }

        String g3 = String.valueOf(body.getOrDefault("ledger_group_3", "")).trim();
        if (g3.isEmpty()) g3 = null;
        String g4 = (g3 != null) ? String.valueOf(body.getOrDefault("ledger_group_4", "")).trim() : null;
        if (g4 != null && g4.isEmpty()) g4 = null;

        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank() || "control".equalsIgnoreCase(tenantId)) {
            tenantId = "1";
        }

        // 1. Check existing accounts in COA for this tenant (case-insensitive check, exact user casing preserved)
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE tenant_id = ? AND LOWER(TRIM(code)) = LOWER(TRIM(?))",
                Integer.class, tenantId, code
        );
        if (count != null && count > 0) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Account code must be unique. This code is already in use in Chart of Accounts."));
        }

        // 2. Also check audit log (create, edit, delete) to prevent reuse of deleted or previous codes
        Integer auditCount = 0;
        try {
            auditCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM audit_log WHERE entity = 'account' AND (before_json LIKE ? OR after_json LIKE ?)",
                    Integer.class,
                    "%\"code\":\"" + code + "\"%", "%\"code\":\"" + code + "\"%"
            );
        } catch (Exception ignored) {}
        if (auditCount != null && auditCount > 0) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Account code must be unique. This code was already used in Chart of Accounts or audit history (create/edit/delete) and cannot be reused."));
        }

        Long groupId = toLong(group.get("id"));
        String nature = String.valueOf(group.get("nature"));

        jdbcTemplate.update(
                "INSERT INTO accounts (tenant_id, code, name, type, group_id, ledger_group_3, ledger_group_4, opening_balance, is_active) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, 0.00, 1)",
                tenantId, code, name, nature, groupId, g3, g4
        );

        Long newId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        try {
            jdbcTemplate.update("""
                INSERT INTO account_ledgers (id, code, name, group_id, opening_balance, current_balance, is_active, tenant_id, created_at)
                VALUES (?, ?, ?, ?, 0.00, 0.00, 1, ?, NOW())
                ON DUPLICATE KEY UPDATE name = VALUES(name), group_id = VALUES(group_id), is_active = VALUES(is_active)
            """, newId, code, name, groupId, tenantId);
        } catch (Exception ignored) {}

        auditLogService.logAction("create", "account", String.valueOf(newId), null,
                Map.of("code", code, "name", name, "group", group.get("name"), "ledger_group_3", g3 != null ? g3 : "", "ledger_group_4", g4 != null ? g4 : ""), request);

        return ResponseEntity.ok(Map.of("ok", true, "id", newId, "message", "Account created successfully."));
    }

    // =========================================================================
    // 9. POST /api/v1/accounts/groups/quick -> quickAccount
    // =========================================================================
    @PostMapping("/groups/quick")
    @PreAuthorize("hasAuthority('accounts_manage')")
    public ResponseEntity<?> quickAccount(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        if (name.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Enter the group name."));
        }

        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_groups WHERE LOWER(name) = LOWER(?)",
                Integer.class, name
        );
        if (exists != null && exists > 0) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "A group with that name already exists."));
        }

        Long parentId = null;
        String nature;
        int affectsGrossProfit = 0;
        String cashFlowCategory = "operating";

        Object pObj = body.get("parent_id");
        if (pObj != null && !pObj.toString().isBlank()) {
            try {
                parentId = Long.parseLong(pObj.toString());
            } catch (Exception ignored) {}
        }

        if (parentId != null) {
            List<Map<String, Object>> parents = jdbcTemplate.queryForList("SELECT * FROM account_groups WHERE id = ?", parentId);
            if (parents.isEmpty() || parents.get(0).get("parent_id") != null) {
                return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "A Level 2 group must sit under a Level 1 group."));
            }
            Map<String, Object> parent = parents.get(0);
            nature = String.valueOf(parent.get("nature"));
            affectsGrossProfit = toBoolean(parent.get("affects_gross_profit")) ? 1 : 0;
            cashFlowCategory = parent.get("cash_flow_category") != null ? String.valueOf(parent.get("cash_flow_category")) : "operating";
        } else {
            nature = String.valueOf(body.getOrDefault("nature", "")).trim();
            if (!List.of("asset", "liability", "equity", "income", "expense").contains(nature)) {
                return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Choose the nature of the new Level 1 group."));
            }
            if (List.of("asset", "liability", "equity").contains(nature)) {
                cashFlowCategory = "equity".equals(nature) ? "financing" : "operating";
            } else {
                cashFlowCategory = "operating";
            }
        }

        jdbcTemplate.update(
                "INSERT INTO account_groups (name, parent_id, nature, affects_gross_profit, cash_flow_category, is_system, sort_order) " +
                        "VALUES (?, ?, ?, ?, ?, 0, 999)",
                name, parentId, nature, affectsGrossProfit, cashFlowCategory
        );

        Long newId = jdbcTemplate.queryForObject("SELECT id FROM account_groups WHERE LOWER(name) = LOWER(?)", Long.class, name);
        auditLogService.logAction("create", "account_group", String.valueOf(newId), null,
                Map.of("name", name, "nature", nature, "parent_id", parentId != null ? parentId : ""), request);

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "group", Map.of("id", newId, "name", name, "parent_id", parentId != null ? parentId : (Object) "", "nature", nature)
        ));
    }

    // =========================================================================
    // 10. GET /api/v1/accounts/{id} -> getAccountForEdit
    // =========================================================================
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('accounts_manage')")
    public ResponseEntity<?> getAccountForEdit(@PathVariable Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM accounts WHERE id = ?", id);
        if (rows.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", "Ledger not found."));
        }
        Map<String, Object> account = rows.get(0);

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "account", account,
                "groupOptions", getGroupOptions(),
                "groupData", getGroupPickerData()
        ));
    }

    // =========================================================================
    // 11. PUT /api/v1/accounts/{id} -> updateAccount
    // =========================================================================
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('accounts_manage')")
    public ResponseEntity<?> updateAccount(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM accounts WHERE id = ?", id);
        if (rows.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", "Ledger not found."));
        }
        Map<String, Object> old = rows.get(0);

        String code = String.valueOf(body.getOrDefault("code", "")).trim();
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        if (code.isEmpty() || name.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "code and name are required."));
        }

        Map<String, Object> group = pickGroupFromBody(body);
        if (group == null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Select a valid group."));
        }

        String oldCode = String.valueOf(old.get("code"));
        if (SYSTEM_CODES.contains(oldCode) && !code.equals(oldCode)) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "This is a system ledger the application posts to — its code cannot be changed (the name and group can)."));
        }

        Integer used = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM voucher_lines WHERE account_id = ?", Integer.class, id);
        String newNature = String.valueOf(group.get("nature"));
        String oldType = String.valueOf(old.get("type"));
        if (used != null && used > 0 && !newNature.equalsIgnoreCase(oldType)) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "This ledger already has " + used + " posted entries, so it cannot be moved to a " + newNature + " group. Pick a group of the same kind."));
        }

        if (!code.equals(oldCode)) {
            Integer exists = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM accounts WHERE code = ? AND id != ?", Integer.class, code, id);
            if (exists != null && exists > 0) {
                return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Account code must be unique."));
            }
        }

        String g3 = String.valueOf(body.getOrDefault("ledger_group_3", "")).trim();
        if (g3.isEmpty()) g3 = null;
        String g4 = (g3 != null) ? String.valueOf(body.getOrDefault("ledger_group_4", "")).trim() : null;
        if (g4 != null && g4.isEmpty()) g4 = null;

        Long groupId = toLong(group.get("id"));

        jdbcTemplate.update(
                "UPDATE accounts SET code = ?, name = ?, type = ?, group_id = ?, ledger_group_3 = ?, ledger_group_4 = ? WHERE id = ?",
                code, name, newNature, groupId, g3, g4, id
        );

        auditLogService.logAction("update", "account", String.valueOf(id),
                Map.of("code", oldCode, "name", old.get("name"), "group_id", old.get("group_id")),
                Map.of("code", code, "name", name, "group_id", groupId, "ledger_group_3", g3 != null ? g3 : "", "ledger_group_4", g4 != null ? g4 : ""),
                request);

        return ResponseEntity.ok(Map.of("ok", true, "message", "Ledger updated."));
    }

    // =========================================================================
    // 12. DELETE /api/v1/accounts/{id} -> deleteAccount
    // =========================================================================
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('accounts_manage')")
    public ResponseEntity<?> deleteAccount(@PathVariable Long id, HttpServletRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM accounts WHERE id = ?", id);
        if (rows.isEmpty()) {
            return ResponseEntity.ok(Map.of("ok", true, "message", "Ledger not found or already deleted."));
        }
        Map<String, Object> acc = rows.get(0);
        String code = String.valueOf(acc.get("code"));

        if (SYSTEM_CODES.contains(code)) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "This is a system ledger the application posts to, so it cannot be deleted."));
        }

        String blockReason = getDeactivationBlockReason(acc);
        Integer childCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM accounts WHERE parent_id = ?", Integer.class, id);
        Integer linkedInv = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoice_lines WHERE ledger_account_id = ?", Integer.class, id);

        if (blockReason != null || (childCount != null && childCount > 0) || (linkedInv != null && linkedInv > 0)) {
            String err = blockReason != null ? blockReason : "This ledger is referenced elsewhere and cannot be deleted. Deactivate it instead.";
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", err));
        }

        try {
            transactionTemplate.execute(status -> {
                jdbcTemplate.update("DELETE FROM items WHERE sku = ?", "LEDGER-" + code);
                jdbcTemplate.update("DELETE FROM accounts WHERE id = ?", id);
                try {
                    jdbcTemplate.update("DELETE FROM account_ledgers WHERE id = ?", id);
                } catch (Exception ignored) {}
                return null;
            });
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "This ledger is referenced elsewhere and cannot be deleted. Deactivate it instead."));
        }

        auditLogService.logAction("delete", "account", String.valueOf(id), acc, null, request);
        return ResponseEntity.ok(Map.of("ok", true, "message", "Ledger \"" + acc.get("name") + "\" deleted."));
    }

    // =========================================================================
    // 13. POST /api/v1/accounts/{id}/toggle -> toggleAccount
    // =========================================================================
    @PostMapping("/{id}/toggle")
    @PreAuthorize("hasAuthority('accounts_manage')")
    public ResponseEntity<?> toggleAccount(@PathVariable Long id, HttpServletRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM accounts WHERE id = ?", id);
        if (rows.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", "Ledger not found."));
        }
        Map<String, Object> acc = rows.get(0);
        Object actObj = acc.get("is_active");
        boolean isActive = toBoolean(actObj);

        if (isActive) {
            String blockReason = getDeactivationBlockReason(acc);
            if (blockReason != null) {
                return ResponseEntity.badRequest().body(Map.of("ok", false, "error", blockReason));
            }
        }

        int newStatus = isActive ? 0 : 1;
        jdbcTemplate.update("UPDATE accounts SET is_active = ? WHERE id = ?", newStatus, id);
        try {
            jdbcTemplate.update("UPDATE account_ledgers SET is_active = ? WHERE id = ?", newStatus, id);
        } catch (Exception ignored) {}
        auditLogService.logAction("toggle_active", "account", String.valueOf(id), acc, Map.of("is_active", newStatus), request);

        return ResponseEntity.ok(Map.of("ok", true, "isActive", newStatus == 1, "message", "Ledger status updated."));
    }

    // =========================================================================
    // 14. GET /api/v1/accounts/groups -> getGroups
    // =========================================================================
    @GetMapping("/groups")
    @PreAuthorize("hasAuthority('accounts_manage')")
    public ResponseEntity<?> getGroups() {
        List<Map<String, Object>> allGroups = jdbcTemplate.queryForList("SELECT * FROM account_groups ORDER BY sort_order, name");

        List<Map<String, Object>> primaries = new ArrayList<>();
        for (Map<String, Object> g : allGroups) {
            if (g.get("parent_id") == null) {
                Map<String, Object> p = new HashMap<>(g);
                List<Map<String, Object>> children = new ArrayList<>();
                for (Map<String, Object> c : allGroups) {
                    if (g.get("id").equals(c.get("parent_id"))) {
                        children.add(c);
                    }
                }
                p.put("children", children);
                primaries.add(p);
            }
        }

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "primaries", primaries,
                "groupOptions", getGroupOptions()
        ));
    }

    // =========================================================================
    // 15. POST /api/v1/accounts/groups -> createAccount (account group)
    // =========================================================================
    @PostMapping("/groups")
    @PreAuthorize("hasAuthority('accounts_manage')")
    public ResponseEntity<?> createAccountGroup(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        String nature = String.valueOf(body.getOrDefault("nature", "")).trim();
        String cashFlowCategory = String.valueOf(body.getOrDefault("cash_flow_category", "operating")).trim();

        if (name.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Group name is required."));
        }
        if (!List.of("asset", "liability", "equity", "income", "expense").contains(nature)) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Invalid nature."));
        }
        if (!List.of("operating", "investing", "financing", "cash_equivalent").contains(cashFlowCategory)) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Invalid cash flow category."));
        }

        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_groups WHERE LOWER(name) = LOWER(?)",
                Integer.class, name
        );
        if (exists != null && exists > 0) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "A group with that name already exists."));
        }

        Long parentId = null;
        Object pObj = body.get("parent_id");
        if (pObj != null && !pObj.toString().isBlank()) {
            try { parentId = Long.parseLong(pObj.toString()); } catch (Exception ignored) {}
        }

        boolean gp = Boolean.TRUE.equals(body.get("affects_gross_profit")) || "1".equals(String.valueOf(body.get("affects_gross_profit")));

        jdbcTemplate.update(
                "INSERT INTO account_groups (name, parent_id, nature, affects_gross_profit, cash_flow_category, is_system, sort_order) " +
                        "VALUES (?, ?, ?, ?, ?, 0, 999)",
                name, parentId, nature, gp ? 1 : 0, cashFlowCategory
        );

        Long newId = jdbcTemplate.queryForObject("SELECT id FROM account_groups WHERE LOWER(name) = LOWER(?)", Long.class, name);
        auditLogService.logAction("create", "account_group", String.valueOf(newId), null,
                Map.of("name", name, "nature", nature, "parent_id", parentId != null ? parentId : ""), request);

        return ResponseEntity.ok(Map.of("ok", true, "id", newId, "message", "Account group created successfully."));
    }

    // =========================================================================
    // 16. GET /api/v1/accounts/export/template -> downloadAccountTemplate
    // =========================================================================
    @GetMapping("/export/template")
    @PreAuthorize("hasAuthority('accounts_manage')")
    public ResponseEntity<byte[]> downloadAccountTemplate() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Template");
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < COA_HEADERS.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(COA_HEADERS.get(i)[1]);
            }
            addReferenceSheet(workbook);
            workbook.write(out);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"coa-mapping-template.xlsx\"")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (Exception e) {
            log.error("Template generation error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // =========================================================================
    // 17. GET /api/v1/accounts/export -> exportAccountExcel
    // =========================================================================
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('reports_view')")
    public ResponseEntity<byte[]> exportAccountExcel() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Accounts");
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < COA_HEADERS.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(COA_HEADERS.get(i)[1]);
            }

            String tenantIdStr = TenantContext.getTenantId();
            Long businessEntityId = null;
            if (tenantIdStr != null && !"control".equalsIgnoreCase(tenantIdStr)) {
                try { businessEntityId = Long.parseLong(tenantIdStr); } catch (Exception ignored) {}
            }

            String entityGstin = "";
            String entityName = "";
            if (businessEntityId != null) {
                List<Map<String, Object>> entRows = controlJdbcTemplate.queryForList("SELECT name, gstin FROM entities WHERE id = ?", businessEntityId);
                if (!entRows.isEmpty()) {
                    entityName = String.valueOf(entRows.get(0).getOrDefault("name", ""));
                    entityGstin = String.valueOf(entRows.get(0).getOrDefault("gstin", ""));
                }
            }

            String sql = "SELECT a.*, " +
                    "       g.name AS group_name, g.parent_id AS group_parent_id, " +
                    "       pg.name AS primary_group_name " +
                    "FROM accounts a " +
                    "LEFT JOIN account_groups g ON g.id = a.group_id " +
                    "LEFT JOIN account_groups pg ON pg.id = COALESCE(g.parent_id, g.id) " +
                    "WHERE a.tenant_id = ? " +
                    "ORDER BY a.code";

            String tenantId = (tenantIdStr != null && !tenantIdStr.isBlank() && !"control".equalsIgnoreCase(tenantIdStr)) ? tenantIdStr : "1";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, tenantId);
            int rowIdx = 1;
            for (Map<String, Object> r : rows) {
                Row dataRow = sheet.createRow(rowIdx++);
                String pGroup = String.valueOf(r.getOrDefault("primary_group_name", ""));
                Long gParentId = r.get("group_parent_id") != null ? ((Number) r.get("group_parent_id")).longValue() : null;
                String gName = String.valueOf(r.getOrDefault("group_name", ""));
                String secGroup = gParentId != null ? gName : "";

                String bName = r.get("branch_name") != null ? String.valueOf(r.get("branch_name")) : entityName;
                String secLine = r.get("sector_line") != null ? String.valueOf(r.get("sector_line")) : "";
                if (SectorMappingUtility.LINES.containsKey(secLine)) {
                    secLine = SectorMappingUtility.LINES.get(secLine).getLabel();
                }

                dataRow.createCell(0).setCellValue(entityGstin);
                dataRow.createCell(1).setCellValue(bName);
                dataRow.createCell(2).setCellValue(String.valueOf(r.getOrDefault("ledger_type_code", "")));
                dataRow.createCell(3).setCellValue(pGroup);
                dataRow.createCell(4).setCellValue(secGroup);
                dataRow.createCell(5).setCellValue(String.valueOf(r.getOrDefault("ledger_group_3", "")));
                dataRow.createCell(6).setCellValue(String.valueOf(r.getOrDefault("ledger_group_4", "")));
                dataRow.createCell(7).setCellValue(String.valueOf(r.getOrDefault("code", "")));
                dataRow.createCell(8).setCellValue(String.valueOf(r.getOrDefault("ledger_code", "")));
                dataRow.createCell(9).setCellValue(String.valueOf(r.getOrDefault("pl_type", "")));
                dataRow.createCell(10).setCellValue(secLine);
                dataRow.createCell(11).setCellValue(String.valueOf(r.getOrDefault("name", "")));
                dataRow.createCell(12).setCellValue(String.valueOf(r.getOrDefault("gst_applicable", "")));
                dataRow.createCell(13).setCellValue(String.valueOf(r.getOrDefault("tds_applicable", "")));
                dataRow.createCell(14).setCellValue(String.valueOf(r.getOrDefault("tcs_applicable", "")));
                dataRow.createCell(15).setCellValue(String.valueOf(r.getOrDefault("gst_type_of_supply", "")));
                dataRow.createCell(16).setCellValue(String.valueOf(r.getOrDefault("gst_nature_of_supply", "")));
                dataRow.createCell(17).setCellValue(String.valueOf(r.getOrDefault("tds_section_number", "")));
                dataRow.createCell(18).setCellValue(String.valueOf(r.getOrDefault("tcs_section_number", "")));
                dataRow.createCell(19).setCellValue(String.valueOf(r.getOrDefault("hsn_code", "")));
                dataRow.createCell(20).setCellValue(r.get("igst_rate") != null ? r.get("igst_rate").toString() : "");
                dataRow.createCell(21).setCellValue(r.get("cgst_rate") != null ? r.get("cgst_rate").toString() : "");
                dataRow.createCell(22).setCellValue(r.get("sgst_rate") != null ? r.get("sgst_rate").toString() : "");
                dataRow.createCell(23).setCellValue(r.get("cess_rate") != null ? r.get("cess_rate").toString() : "");
                dataRow.createCell(24).setCellValue(r.get("opening_balance") != null ? r.get("opening_balance").toString() : "0.00");
                dataRow.createCell(25).setCellValue(String.valueOf(r.getOrDefault("rcm_applicable", "")));
                dataRow.createCell(26).setCellValue(String.valueOf(r.getOrDefault("itc_eligible", "")));
            }

            addReferenceSheet(workbook);
            workbook.write(out);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"coa-mapping.xlsx\"")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (Exception e) {
            log.error("COA Excel export error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // =========================================================================
    // 18. POST /api/v1/accounts/import -> importAccount
    // =========================================================================
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('accounts_manage')")
    public ResponseEntity<?> importAccount(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Please select a file to import."));
        }

        List<Map<String, String>> rows = new ArrayList<>();
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";

        try {
            if (filename.endsWith(".csv")) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
                    String line = reader.readLine();
                    if (line != null) {
                        String[] headers = parseCsvLine(line);
                        while ((line = reader.readLine()) != null) {
                            if (line.trim().isEmpty()) continue;
                            String[] values = parseCsvLine(line);
                            Map<String, String> rowMap = new HashMap<>();
                            for (int i = 0; i < headers.length; i++) {
                                rowMap.put(headers[i].trim(), i < values.length ? values[i].trim() : "");
                            }
                            rows.add(rowMap);
                        }
                    }
                }
            } else {
                try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
                    Sheet sheet = workbook.getSheetAt(0);
                    Row headerRow = sheet.getRow(0);
                    if (headerRow != null) {
                        List<String> headers = new ArrayList<>();
                        for (int i = 0; i < headerRow.getLastCellNum(); i++) {
                            Cell c = headerRow.getCell(i);
                            headers.add(c != null ? c.toString().trim() : "COL_" + i);
                        }
                        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                            Row row = sheet.getRow(r);
                            if (row == null) continue;
                            Map<String, String> rowMap = new HashMap<>();
                            boolean hasData = false;
                            for (int i = 0; i < headers.size(); i++) {
                                Cell c = row.getCell(i);
                                String val = c != null ? c.toString().trim() : "";
                                if (!val.isEmpty()) hasData = true;
                                rowMap.put(headers.get(i), val);
                            }
                            if (hasData) rows.add(rowMap);
                        }
                    }
                }
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Could not read the uploaded file. Make sure it is a valid .xlsx or .csv exported from the template."));
        }

        List<Map<String, Object>> errors = new ArrayList<>();
        int[] counts = new int[]{0, 0}; // created, updated

        transactionTemplate.execute(status -> {
            for (int i = 0; i < rows.size(); i++) {
                int rowNum = i + 2;
                Map<String, String> row = rows.get(i);
                String id = getCell(row, "id", "Ledger Id*");
                String name = getCell(row, "ledger_name", "Ledger Name");
                String pGroupName = getCell(row, "primary_group", "Ledger Group 1");
                String sGroupName = getCell(row, "secondary_group", "Ledger Group 2");
                String opRaw = getCell(row, "opening_balance", "Opening Balance");

                if (id.isEmpty() || name.isEmpty()) {
                    errors.add(Map.of("row", rowNum, "message", "Ledger Id and Ledger Name are required"));
                    continue;
                }

                BigDecimal op = BigDecimal.ZERO;
                if (!opRaw.isEmpty()) {
                    try {
                        op = new BigDecimal(opRaw).setScale(2, RoundingMode.HALF_UP);
                    } catch (Exception e) {
                        errors.add(Map.of("row", rowNum, "message", "Opening Balance must be a number"));
                        continue;
                    }
                }

                Map<String, Object> group = resolveGroupByNames(pGroupName, sGroupName);
                if (group == null) {
                    errors.add(Map.of("row", rowNum, "message", "Ledger Group 1 \"" + pGroupName + "\"" + (!sGroupName.isEmpty() ? " / Ledger Group 2 \"" + sGroupName + "\"" : "") + " does not match an existing group exactly (see /accounts/groups)"));
                    continue;
                }

                String sectorLineRaw = getCell(row, "sector_line", "Sector Report Line");
                String sectorLine = null;
                if (!sectorLineRaw.isEmpty()) {
                    sectorLine = SectorMappingUtility.parseLine(sectorLineRaw);
                    if (sectorLine == null) {
                        errors.add(Map.of("row", rowNum, "message", "Sector Report Line \"" + sectorLineRaw + "\" is not a recognised line -- ledger imported without it (see the Reference Lists sheet)"));
                    }
                }

                Long groupId = toLong(group.get("id"));
                String nature = String.valueOf(group.get("nature"));

                String ledgerCode = nullIfEmpty(getCell(row, "ledger_code", "Ledger Code"));
                String ledgerTypeCode = nullIfEmpty(getCell(row, "ledger_type_code", "Type"));
                String lg3 = nullIfEmpty(getCell(row, "ledger_group_3", "Ledger Group 3"));
                String lg4 = nullIfEmpty(getCell(row, "ledger_group_4", "Ledger Group 4"));
                String plType = nullIfEmpty(getCell(row, "pl_type", "P&L Type"));
                String gstApp = parseYn(getCell(row, "gst_applicable", "GST Applicable"));
                String tdsApp = parseYn(getCell(row, "tds_applicable", "TDS Applicable"));
                String tcsApp = parseYn(getCell(row, "tcs_applicable", "TCS Applicable"));
                String gstTypeSupply = nullIfEmpty(getCell(row, "gst_type_of_supply", "GST Type of Supply"));
                String gstNatureSupply = nullIfEmpty(getCell(row, "gst_nature_of_supply", "GST Nature of Supply"));
                String tdsSec = nullIfEmpty(getCell(row, "tds_section_number", "TDS Section Number"));
                String tcsSec = nullIfEmpty(getCell(row, "tcs_section_number", "TCS Section Number"));
                String hsn = nullIfEmpty(getCell(row, "hsn_code", "HSN Code"));
                BigDecimal igst = parseNumOrNull(getCell(row, "igst_rate", "IGST Rate"));
                BigDecimal cgst = parseNumOrNull(getCell(row, "cgst_rate", "CGST Rate"));
                BigDecimal sgst = parseNumOrNull(getCell(row, "sgst_rate", "SGST Rate"));
                BigDecimal cess = parseNumOrNull(getCell(row, "cess_rate", "Cess Rate"));
                String rcm = parseYn(getCell(row, "rcm_applicable", "RCM Applicability (Y/N)"));
                String itc = parseYn(getCell(row, "itc_eligible", "ITC Eligibility (Y/N)"));
                String branch = nullIfEmpty(getCell(row, "branch_name", "Branch Name*"));

                List<Map<String, Object>> existing = jdbcTemplate.queryForList("SELECT * FROM accounts WHERE code = ?", id);
                if (!existing.isEmpty()) {
                    Long existId = ((Number) existing.get(0).get("id")).longValue();
                    jdbcTemplate.update(
                            "UPDATE accounts SET " +
                                    "  name = ?, type = ?, group_id = ?, opening_balance = ?, ledger_code = ?, ledger_type_code = ?, " +
                                    "  ledger_group_3 = ?, ledger_group_4 = ?, pl_type = ?, gst_applicable = ?, tds_applicable = ?, " +
                                    "  tcs_applicable = ?, gst_type_of_supply = ?, gst_nature_of_supply = ?, tds_section_number = ?, " +
                                    "  tcs_section_number = ?, hsn_code = ?, igst_rate = ?, cgst_rate = ?, sgst_rate = ?, cess_rate = ?, " +
                                    "  rcm_applicable = ?, itc_eligible = ?, branch_name = ?, sector_line = COALESCE(?, sector_line) " +
                                    "WHERE id = ?",
                            name, nature, groupId, op, ledgerCode, ledgerTypeCode, lg3, lg4, plType, gstApp, tdsApp, tcsApp,
                            gstTypeSupply, gstNatureSupply, tdsSec, tcsSec, hsn, igst, cgst, sgst, cess, rcm, itc, branch,
                            sectorLine, existId
                    );
                    counts[1]++;
                } else {
                    jdbcTemplate.update(
                            "INSERT INTO accounts ( " +
                                    "  code, name, type, group_id, opening_balance, ledger_code, ledger_type_code, ledger_group_3, " +
                                    "  ledger_group_4, pl_type, gst_applicable, tds_applicable, tcs_applicable, gst_type_of_supply, " +
                                    "  gst_nature_of_supply, tds_section_number, tcs_section_number, hsn_code, igst_rate, cgst_rate, " +
                                    "  sgst_rate, cess_rate, rcm_applicable, itc_eligible, branch_name, sector_line, is_active " +
                                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
                            id, name, nature, groupId, op, ledgerCode, ledgerTypeCode, lg3, lg4, plType, gstApp, tdsApp, tcsApp,
                            gstTypeSupply, gstNatureSupply, tdsSec, tcsSec, hsn, igst, cgst, sgst, cess, rcm, itc, branch,
                            sectorLine
                    );
                    counts[0]++;
                }
            }
            return null;
        });

        auditLogService.logAction("import", "accounts", null, null,
                Map.of("total", rows.size(), "created", counts[0], "updated", counts[1]), request);

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "total", rows.size(),
                "created", counts[0],
                "updated", counts[1],
                "errors", errors,
                "message", "Import completed: " + counts[0] + " created, " + counts[1] + " updated."
        ));
    }

    // =========================================================================
    // 19. GET /api/v1/accounts/sector-mapping -> getSectorMapping
    // =========================================================================
    @GetMapping("/sector-mapping")
    @PreAuthorize("hasAuthority('reports_view')")
    public ResponseEntity<?> getSectorMapping(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String grp,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String active,
            @RequestParam(defaultValue = "1") int page) {

        String tenantIdStr = TenantContext.getTenantId();
        Long entityId = null;
        if (tenantIdStr != null && !"control".equalsIgnoreCase(tenantIdStr)) {
            try { entityId = Long.parseLong(tenantIdStr); } catch (Exception ignored) {}
        }

        String entitySector = "";
        String businessType = "";
        if (entityId != null) {
            List<Map<String, Object>> eRows = controlJdbcTemplate.queryForList("SELECT sector, business_type FROM entities WHERE id = ?", entityId);
            if (!eRows.isEmpty()) {
                entitySector = String.valueOf(eRows.get(0).getOrDefault("sector", ""));
                businessType = String.valueOf(eRows.get(0).getOrDefault("business_type", ""));
            }
        }

        String templateKey = SectorMappingUtility.pickTemplate(entitySector, businessType);

        List<Map<String, Object>> allSectorRows = getSectorMappingEvaluatedRows(templateKey);

        Map<String, Integer> counts = new HashMap<>(Map.of(
                "all", allSectorRows.size(),
                "mapped", 0,
                "suggested", 0,
                "unmapped", 0
        ));
        for (Map<String, Object> r : allSectorRows) {
            String s = String.valueOf(r.get("status"));
            counts.put(s, counts.getOrDefault(s, 0) + 1);
        }

        List<Map<String, Object>> filtered = filterSectorRows(allSectorRows, q, status, grp, type, active);
        int total = filtered.size();
        int pages = Math.max(1, (int) Math.ceil((double) total / SM_PAGE_SIZE));
        int currentPage = Math.min(pages, Math.max(1, page));
        int fromIdx = (currentPage - 1) * SM_PAGE_SIZE;
        int toIdx = Math.min(fromIdx + SM_PAGE_SIZE, total);
        List<Map<String, Object>> pageRows = fromIdx < total ? filtered.subList(fromIdx, toIdx) : List.of();

        List<Map<String, Object>> groupRules = jdbcTemplate.queryForList("SELECT group_id, line FROM sector_group_rules");
        Map<Long, String> rulesMap = new HashMap<>();
        for (Map<String, Object> gr : groupRules) {
            rulesMap.put(((Number) gr.get("group_id")).longValue(), String.valueOf(gr.get("line")));
        }

        Map<Long, Map<String, Object>> groupMap = new LinkedHashMap<>();
        for (Map<String, Object> r : allSectorRows) {
            Long gid = (Long) r.get("groupId");
            if (gid == null) continue;
            if (!groupMap.containsKey(gid)) {
                String label = r.get("g2") != null && !r.get("g2").toString().isEmpty()
                        ? r.get("g1") + " › " + r.get("g2") : String.valueOf(r.get("g1"));
                groupMap.put(gid, new HashMap<>(Map.of(
                        "id", gid,
                        "label", label,
                        "type", r.get("type"),
                        "count", 0,
                        "rule", rulesMap.getOrDefault(gid, "")
                )));
            }
            Map<String, Object> gm = groupMap.get(gid);
            gm.put("count", (Integer) gm.get("count") + 1);
        }

        List<Map<String, Object>> groupsList = new ArrayList<>(groupMap.values());
        groupsList.sort(Comparator.comparing(a -> String.valueOf(a.get("label"))));

        List<Map<String, String>> incomeLines = new ArrayList<>();
        for (Map.Entry<String, String> e : SectorMappingUtility.linesFor(templateKey, "income")) {
            incomeLines.add(Map.of("key", e.getKey(), "label", e.getValue()));
        }
        List<Map<String, String>> expenseLines = new ArrayList<>();
        for (Map.Entry<String, String> e : SectorMappingUtility.linesFor(templateKey, "expense")) {
            expenseLines.add(Map.of("key", e.getKey(), "label", e.getValue()));
        }

        Set<String> primaryGroups = new TreeSet<>();
        for (Map<String, Object> r : allSectorRows) {
            primaryGroups.add(String.valueOf(r.get("g1")));
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("sectors", SECTORS_LIST);
        resp.put("entitySector", entitySector);
        resp.put("template", Map.of("key", templateKey, "label", templateKey.substring(0, 1).toUpperCase() + templateKey.substring(1)));
        resp.put("rows", pageRows);
        resp.put("counts", counts);
        resp.put("lineOptions", Map.of("income", incomeLines, "expense", expenseLines));
        resp.put("groups", groupsList);
        resp.put("primaryGroups", primaryGroups);
        resp.put("page", currentPage);
        resp.put("pages", pages);
        resp.put("matchedCount", total);
        resp.put("pageSize", SM_PAGE_SIZE);
        return ResponseEntity.ok(resp);
    }

    // =========================================================================
    // 20. POST /api/v1/accounts/sector-mapping/sector -> sectorAccount
    // =========================================================================
    @PostMapping("/sector-mapping/sector")
    @PreAuthorize("hasAuthority('accounts_manage')")
    public ResponseEntity<?> sectorAccount(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String sector = String.valueOf(body.getOrDefault("sector", "")).trim();
        if (!SECTORS_LIST.contains(sector)) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Choose a sector from the list."));
        }

        String tenantIdStr = TenantContext.getTenantId();
        Long entityId = null;
        if (tenantIdStr != null && !"control".equalsIgnoreCase(tenantIdStr)) {
            try { entityId = Long.parseLong(tenantIdStr); } catch (Exception ignored) {}
        }
        if (entityId == null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Active business context required."));
        }

        controlJdbcTemplate.update("UPDATE entities SET sector = ? WHERE id = ?", sector, entityId);
        auditLogService.logAction("update", "entity", String.valueOf(entityId), null, Map.of("sector", sector), request);

        return ResponseEntity.ok(Map.of("ok", true, "sector", sector, "message", "Sector set to \"" + sector + "\" in Business details."));
    }

    // =========================================================================
    // 21. POST /api/v1/accounts/sector-mapping/save -> saveAccount (Sector Mapping)
    // =========================================================================
    @PostMapping("/sector-mapping/save")
    @PreAuthorize("hasAuthority('accounts_manage')")
    public ResponseEntity<?> saveAccountSectorMapping(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String raw = String.valueOf(body.getOrDefault("line", "")).trim();
        String line = !raw.isEmpty() ? SectorMappingUtility.parseLine(raw) : null;
        if (!raw.isEmpty() && line == null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Unknown report line."));
        }

        List<Long> ids = new ArrayList<>();
        String scope = String.valueOf(body.getOrDefault("scope", "selected"));
        if ("filtered".equalsIgnoreCase(scope)) {
            String tenantIdStr = TenantContext.getTenantId();
            Long entityId = null;
            if (tenantIdStr != null && !"control".equalsIgnoreCase(tenantIdStr)) {
                try { entityId = Long.parseLong(tenantIdStr); } catch (Exception ignored) {}
            }
            String entitySector = "";
            String businessType = "";
            if (entityId != null) {
                List<Map<String, Object>> eRows = controlJdbcTemplate.queryForList("SELECT sector, business_type FROM entities WHERE id = ?", entityId);
                if (!eRows.isEmpty()) {
                    entitySector = String.valueOf(eRows.get(0).getOrDefault("sector", ""));
                    businessType = String.valueOf(eRows.get(0).getOrDefault("business_type", ""));
                }
            }
            String templateKey = SectorMappingUtility.pickTemplate(entitySector, businessType);
            List<Map<String, Object>> allSectorRows = getSectorMappingEvaluatedRows(templateKey);
            List<Map<String, Object>> matched = filterSectorRows(allSectorRows,
                    (String) body.get("q"), (String) body.get("status"), (String) body.get("grp"),
                    (String) body.get("type"), (String) body.get("active"));
            for (Map<String, Object> m : matched) {
                ids.add((Long) m.get("id"));
            }
        } else {
            Object idsObj = body.get("ids");
            if (idsObj instanceof List<?> list) {
                for (Object item : list) {
                    try { ids.add(Long.parseLong(item.toString())); } catch (Exception ignored) {}
                }
            }
        }

        int changed = 0;
        int skipped = 0;

        for (Long id : ids) {
            List<Map<String, Object>> aList = jdbcTemplate.queryForList("SELECT id, type FROM accounts WHERE id = ?", id);
            if (aList.isEmpty()) { skipped++; continue; }
            String aType = String.valueOf(aList.get(0).get("type"));
            if (!List.of("income", "expense").contains(aType)) { skipped++; continue; }

            if (line != null) {
                SectorMappingUtility.LineDef def = SectorMappingUtility.LINES.get(line);
                if (def == null || !def.getKind().equalsIgnoreCase(aType)) {
                    skipped++;
                    continue;
                }
            }

            jdbcTemplate.update("UPDATE accounts SET sector_line = ? WHERE id = ?", line, id);
            changed++;
        }

        auditLogService.logAction("update", "sector_mapping", null, null,
                Map.of("line", line != null ? line : "(cleared)", "ledgers", changed, "scope", scope), request);

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "changed", changed,
                "skipped", skipped,
                "message", changed + " ledger(s) updated" + (skipped > 0 ? ", " + skipped + " skipped (the line does not suit that ledger type)" : "") + "."
        ));
    }

    // =========================================================================
    // 22. POST /api/v1/accounts/sector-mapping/group -> groupAccount
    // =========================================================================
    @PostMapping("/sector-mapping/group")
    @PreAuthorize("hasAuthority('accounts_manage')")
    public ResponseEntity<?> groupAccount(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long groupId = null;
        try {
            groupId = Long.parseLong(String.valueOf(body.get("group_id")));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Valid group_id is required."));
        }

        String raw = String.valueOf(body.getOrDefault("line", "")).trim();
        String line = !raw.isEmpty() ? SectorMappingUtility.parseLine(raw) : null;
        if (!raw.isEmpty() && line == null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Unknown group or report line."));
        }

        List<Map<String, Object>> grpRows = jdbcTemplate.queryForList("SELECT id, name FROM account_groups WHERE id = ?", groupId);
        if (grpRows.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Group not found."));
        }
        String groupName = String.valueOf(grpRows.get(0).get("name"));

        if (line != null) {
            jdbcTemplate.update(
                    "INSERT INTO sector_group_rules (group_id, line) VALUES (?, ?) " +
                            "ON DUPLICATE KEY UPDATE line = VALUES(line)",
                    groupId, line
            );
        } else {
            jdbcTemplate.update("DELETE FROM sector_group_rules WHERE group_id = ?", groupId);
        }

        auditLogService.logAction("update", "sector_group_rule", String.valueOf(groupId), null,
                Map.of("group", groupName, "line", line != null ? line : "(removed)"), request);

        String msg = line != null
                ? "Every ledger in \"" + groupName + "\" now reports under \"" + SectorMappingUtility.LINES.get(line).getLabel() + "\" (unless it has its own line)."
                : "Rule for \"" + groupName + "\" removed.";

        return ResponseEntity.ok(Map.of("ok", true, "message", msg));
    }

    // =========================================================================
    // 23. POST /api/v1/accounts/sector-mapping/accept-all -> acceptAllAccount
    // =========================================================================
    @PostMapping("/sector-mapping/accept-all")
    @PreAuthorize("hasAuthority('accounts_manage')")
    public ResponseEntity<?> acceptAllAccount(HttpServletRequest request) {
        String tenantIdStr = TenantContext.getTenantId();
        Long entityId = null;
        if (tenantIdStr != null && !"control".equalsIgnoreCase(tenantIdStr)) {
            try { entityId = Long.parseLong(tenantIdStr); } catch (Exception ignored) {}
        }
        String entitySector = "";
        String businessType = "";
        if (entityId != null) {
            List<Map<String, Object>> eRows = controlJdbcTemplate.queryForList("SELECT sector, business_type FROM entities WHERE id = ?", entityId);
            if (!eRows.isEmpty()) {
                entitySector = String.valueOf(eRows.get(0).getOrDefault("sector", ""));
                businessType = String.valueOf(eRows.get(0).getOrDefault("business_type", ""));
            }
        }
        String templateKey = SectorMappingUtility.pickTemplate(entitySector, businessType);
        List<Map<String, Object>> allSectorRows = getSectorMappingEvaluatedRows(templateKey);

        int count = 0;
        for (Map<String, Object> r : allSectorRows) {
            if ("suggested".equals(r.get("status"))) {
                String sug = String.valueOf(r.get("suggested"));
                if (!sug.isEmpty()) {
                    jdbcTemplate.update("UPDATE accounts SET sector_line = ? WHERE id = ?", sug, r.get("id"));
                    count++;
                }
            }
        }

        auditLogService.logAction("update", "sector_mapping", null, null,
                Map.of("accepted_suggestions", count), request);

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "confirmedCount", count,
                "message", count + " suggested mapping(s) confirmed."
        ));
    }

    // =========================================================================
    // 24. GET /api/v1/accounts/coa-mapping -> getCoaMapping
    // =========================================================================
    @GetMapping("/coa-mapping")
    @PreAuthorize("hasAuthority('reports_view')")
    public ResponseEntity<?> getCoaMapping(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String g1,
            @RequestParam(required = false) String g2,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page) {

        List<Map<String, Object>> allRows = getCoaMappingEvaluatedRows();

        Map<String, Integer> counts = new HashMap<>(Map.of(
                "all", allRows.size(),
                "ok", 0,
                "catchall", 0,
                "unclassified", 0
        ));
        for (Map<String, Object> r : allRows) {
            String s = String.valueOf(r.get("status"));
            counts.put(s, counts.getOrDefault(s, 0) + 1);
        }

        List<Map<String, Object>> filtered = filterCoaRows(allRows, q, status, g1, g2, type);
        int total = filtered.size();
        int pages = Math.max(1, (int) Math.ceil((double) total / CM_PAGE_SIZE));
        int currentPage = Math.min(pages, Math.max(1, page));
        int fromIdx = (currentPage - 1) * CM_PAGE_SIZE;
        int toIdx = Math.min(fromIdx + CM_PAGE_SIZE, total);
        List<Map<String, Object>> pageRows = fromIdx < total ? filtered.subList(fromIdx, toIdx) : List.of();

        List<Map<String, Object>> groups = jdbcTemplate.queryForList("SELECT id, name, parent_id, nature, affects_gross_profit FROM account_groups ORDER BY sort_order, name");
        List<Map<String, Object>> primaries = new ArrayList<>();
        for (Map<String, Object> g : groups) {
            if (g.get("parent_id") == null) {
                Map<String, Object> p = new HashMap<>(g);
                List<Map<String, Object>> children = new ArrayList<>();
                for (Map<String, Object> c : groups) {
                    if (g.get("id").equals(c.get("parent_id"))) {
                        children.add(Map.of("id", c.get("id"), "name", c.get("name")));
                    }
                }
                p.put("children", children);
                primaries.add(p);
            }
        }

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "rows", pageRows,
                "counts", counts,
                "primaries", primaries,
                "page", currentPage,
                "pages", pages,
                "matchedCount", total,
                "pageSize", CM_PAGE_SIZE
        ));
    }

    // =========================================================================
    // 25. POST /api/v1/accounts/coa-mapping/save -> saveAccount (COA Mapping)
    // =========================================================================
    @PostMapping("/coa-mapping/save")
    @PreAuthorize("hasAuthority('accounts_manage')")
    public ResponseEntity<?> saveAccountCoaMapping(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long primaryId = null;
        try {
            primaryId = Long.parseLong(String.valueOf(body.get("primary_id")));
        } catch (Exception ignored) {}

        Long secondaryId = null;
        Object sObj = body.get("secondary_id");
        if (sObj != null && !sObj.toString().isBlank()) {
            try { secondaryId = Long.parseLong(sObj.toString()); } catch (Exception ignored) {}
        }

        if (primaryId == null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Choose a Ledger Group 1, and a Ledger Group 2 that belongs to it."));
        }

        List<Map<String, Object>> pRows = jdbcTemplate.queryForList("SELECT * FROM account_groups WHERE id = ?", primaryId);
        if (pRows.isEmpty() || pRows.get(0).get("parent_id") != null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Choose a valid Ledger Group 1."));
        }
        Map<String, Object> primary = pRows.get(0);

        Map<String, Object> secondary = null;
        if (secondaryId != null) {
            List<Map<String, Object>> sRows = jdbcTemplate.queryForList("SELECT * FROM account_groups WHERE id = ?", secondaryId);
            if (sRows.isEmpty() || !primaryId.equals(sRows.get(0).get("parent_id"))) {
                return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Ledger Group 2 must belong to the chosen Ledger Group 1."));
            }
            secondary = sRows.get(0);
        }

        Map<String, Object> target = secondary != null ? secondary : primary;
        Long targetId = ((Number) target.get("id")).longValue();
        String targetNature = String.valueOf(target.get("nature"));

        List<Long> ids = new ArrayList<>();
        String scope = String.valueOf(body.getOrDefault("scope", "selected"));
        if ("filtered".equalsIgnoreCase(scope)) {
            List<Map<String, Object>> allRows = getCoaMappingEvaluatedRows();
            List<Map<String, Object>> matched = filterCoaRows(allRows,
                    (String) body.get("q"), (String) body.get("status"), (String) body.get("g1"),
                    (String) body.get("g2"), (String) body.get("type"));
            for (Map<String, Object> m : matched) {
                ids.add((Long) m.get("id"));
            }
        } else {
            Object idsObj = body.get("ids");
            if (idsObj instanceof List<?> list) {
                for (Object item : list) {
                    try { ids.add(Long.parseLong(item.toString())); } catch (Exception ignored) {}
                }
            }
        }

        List<Map<String, Object>> stockGroupList = jdbcTemplate.queryForList("SELECT id FROM account_groups WHERE name = 'Stock-in-Hand'");
        Long stockId = !stockGroupList.isEmpty() ? ((Number) stockGroupList.get(0).get("id")).longValue() : -1L;

        int changed = 0;
        int skipped = 0;
        int stockSkipped = 0;

        for (Long id : ids) {
            List<Map<String, Object>> aRows = jdbcTemplate.queryForList("SELECT id, type, group_id, opening_balance FROM accounts WHERE id = ?", id);
            if (aRows.isEmpty()) { skipped++; continue; }
            Map<String, Object> a = aRows.get(0);

            Long currentGid = a.get("group_id") != null ? ((Number) a.get("group_id")).longValue() : -1L;
            if (targetId.equals(stockId) || currentGid.equals(stockId)) {
                stockSkipped++;
                continue;
            }

            String currentType = String.valueOf(a.get("type"));
            BigDecimal op = a.get("opening_balance") != null ? (BigDecimal) a.get("opening_balance") : BigDecimal.ZERO;
            boolean hasOpening = op.compareTo(BigDecimal.ZERO) != 0;

            Integer postCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM voucher_lines WHERE account_id = ?", Integer.class, id);
            boolean hasPost = postCount != null && postCount > 0;

            if (!currentType.equalsIgnoreCase(targetNature) && (hasOpening || hasPost)) {
                skipped++;
                continue;
            }

            jdbcTemplate.update("UPDATE accounts SET group_id = ?, type = ? WHERE id = ?", targetId, targetNature, id);
            changed++;
        }

        String targetName = secondary != null ? primary.get("name") + " > " + secondary.get("name") : String.valueOf(primary.get("name"));
        auditLogService.logAction("update", "coa_mapping", null, null,
                Map.of("group", targetName, "ledgers", changed, "scope", scope), request);

        StringBuilder msg = new StringBuilder();
        msg.append(changed).append(" ledger(s) mapped to ").append(secondary != null ? primary.get("name") + " › " + secondary.get("name") : primary.get("name")).append(".");
        if (skipped > 0) {
            msg.append(" ").append(skipped).append(" skipped: they already have postings or an opening balance and that group is of a different nature (asset / liability / income / expense).");
        }
        if (stockSkipped > 0) {
            msg.append(" ").append(stockSkipped).append(" skipped: the Stock-in-Hand group is reserved for the stock-valuation ledger and cannot be moved into or out of.");
        }

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "changed", changed,
                "skipped", skipped,
                "stockSkipped", stockSkipped,
                "message", msg.toString()
        ));
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private String getSetting(String key, String defaultValue) {
        String tenantId = com.finvane.model.common.TenantContext.getTenantId();
        try {
            List<String> list = jdbcTemplate.query(
                    "SELECT setting_value FROM app_settings WHERE tenant_id = ? AND setting_key = ? LIMIT 1",
                    (rs, rowNum) -> rs.getString("setting_value"), tenantId, key);
            return list.isEmpty() || list.get(0) == null ? defaultValue : list.get(0);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private void setSetting(String key, String value) {
        String tenantId = com.finvane.model.common.TenantContext.getTenantId();
        jdbcTemplate.update(
                "INSERT INTO app_settings (tenant_id, setting_key, setting_value) VALUES (?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)",
                tenantId, key, value
        );
    }

    private String getDeactivationBlockReason(Map<String, Object> account) {
        Long id = toLong(account.get("id"));
        BigDecimal op = account.get("opening_balance") != null ? (BigDecimal) account.get("opening_balance") : BigDecimal.ZERO;
        if (op.compareTo(BigDecimal.ZERO) != 0) {
            return "This ledger has a non-zero opening balance (₹" + op.setScale(2, RoundingMode.HALF_UP) + "). Clear it to 0 before deactivating.";
        }

        Integer vc = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM voucher_lines WHERE account_id = ?", Integer.class, id);
        if (vc != null && vc > 0) {
            return "This ledger has " + vc + " posted transaction line(s) against it and cannot be deactivated.";
        }

        Integer bc = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bank_accounts WHERE account_id = ?", Integer.class, id);
        if (bc != null && bc > 0) {
            return "This ledger is linked to a bank account and cannot be deactivated.";
        }

        Integer bgc = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM budgets WHERE account_id = ?", Integer.class, id);
        if (bgc != null && bgc > 0) {
            return "This ledger has budget entries against it and cannot be deactivated.";
        }

        return null;
    }

    private List<Map<String, Object>> getGroupOptions() {
        List<Map<String, Object>> rawGroups = jdbcTemplate.queryForList("SELECT * FROM account_groups ORDER BY sort_order, name, id");
        // Deduplicate groups by lowercased name and parent_id
        Map<String, Map<String, Object>> uniqueGroupMap = new LinkedHashMap<>();
        for (Map<String, Object> g : rawGroups) {
            Long pId = toLong(g.get("parent_id"));
            if (pId != null && pId == 0L) pId = null;
            String key = (pId == null ? "root:" : pId + ":") + String.valueOf(g.get("name")).trim().toLowerCase();
            uniqueGroupMap.putIfAbsent(key, g);
        }
        List<Map<String, Object>> groups = new ArrayList<>(uniqueGroupMap.values());

        List<Map<String, Object>> primaries = new ArrayList<>();
        for (Map<String, Object> g : groups) {
            Long pId = toLong(g.get("parent_id"));
            if (pId == null || pId == 0L) primaries.add(g);
        }

        List<Map<String, Object>> options = new ArrayList<>();
        for (Map<String, Object> p : primaries) {
            options.add(Map.of("id", p.get("id"), "label", p.get("name"), "depth", 0));
            for (Map<String, Object> s : groups) {
                Long spId = toLong(s.get("parent_id"));
                Long pId = toLong(p.get("id"));
                if (pId != null && pId.equals(spId)) {
                    options.add(Map.of("id", s.get("id"), "label", p.get("name") + " > " + s.get("name"), "depth", 1));
                }
            }
        }
        return options;
    }

    private Map<String, Object> getGroupPickerData() {
        List<Map<String, Object>> rawGroups = jdbcTemplate.queryForList("SELECT id, name, parent_id, nature FROM account_groups ORDER BY sort_order, name, id");
        // Deduplicate groups by lowercased name and normalized parent_id
        Map<String, Map<String, Object>> uniqueGroupMap = new LinkedHashMap<>();
        for (Map<String, Object> g : rawGroups) {
            Long pId = toLong(g.get("parent_id"));
            if (pId != null && pId == 0L) pId = null;
            String key = (pId == null ? "root:" : pId + ":") + String.valueOf(g.get("name")).trim().toLowerCase();
            uniqueGroupMap.putIfAbsent(key, g);
        }
        List<Map<String, Object>> groups = new ArrayList<>(uniqueGroupMap.values());

        List<Map<String, Object>> lg3 = jdbcTemplate.queryForList("SELECT DISTINCT group_id, ledger_group_3 AS v FROM accounts WHERE ledger_group_3 IS NOT NULL AND ledger_group_3 != '' ORDER BY ledger_group_3");
        List<Map<String, Object>> lg4 = jdbcTemplate.queryForList("SELECT DISTINCT group_id, ledger_group_3 AS p, ledger_group_4 AS v FROM accounts WHERE ledger_group_4 IS NOT NULL AND ledger_group_4 != '' ORDER BY ledger_group_4");
        return Map.of("groups", groups, "lg3", lg3, "lg4", lg4);
    }

    private Map<String, Object> pickGroupFromBody(Map<String, Object> body) {
        Long l2Id = parseLongOrNull(body.get("group_l2"));
        Long l1Id = parseLongOrNull(body.get("group_l1"));
        if (l1Id == null) l1Id = parseLongOrNull(body.get("group_id"));

        Map<String, Object> l2 = l2Id != null ? getGroupById(l2Id) : null;
        Map<String, Object> l1 = l1Id != null ? getGroupById(l1Id) : null;

        if (l2 != null && l1 != null && !l1Id.equals(l2.get("parent_id"))) {
            return null;
        }
        return l2 != null ? l2 : l1;
    }

    private Map<String, Object> getGroupById(Long id) {
        List<Map<String, Object>> list = jdbcTemplate.queryForList("SELECT * FROM account_groups WHERE id = ?", id);
        return list.isEmpty() ? null : list.get(0);
    }

    private Map<String, Object> resolveGroupByNames(String primaryName, String secondaryName) {
        if (secondaryName != null && !secondaryName.isBlank()) {
            List<Map<String, Object>> sec = jdbcTemplate.queryForList("SELECT * FROM account_groups WHERE LOWER(name) = LOWER(?)", secondaryName.trim());
            if (!sec.isEmpty() && sec.get(0).get("parent_id") != null) return sec.get(0);
            return null;
        }
        if (primaryName != null && !primaryName.isBlank()) {
            List<Map<String, Object>> pri = jdbcTemplate.queryForList("SELECT * FROM account_groups WHERE LOWER(name) = LOWER(?)", primaryName.trim());
            if (!pri.isEmpty() && pri.get(0).get("parent_id") == null) return pri.get(0);
            return null;
        }
        return null;
    }

    private List<Map<String, Object>> getSectorMappingEvaluatedRows(String templateKey) {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank() || "control".equalsIgnoreCase(tenantId)) {
            tenantId = "1";
        }
        String sql = "SELECT a.id, a.code, a.name, a.type, a.group_id, a.sector_line, " +
                "       COALESCE(rg.line, rp.line) AS group_line, " +
                "       g.name AS group_name, g.parent_id AS group_parent_id, g.affects_gross_profit, " +
                "       pg.name AS primary_group_name " +
                "FROM accounts a " +
                "LEFT JOIN account_groups g ON g.id = a.group_id " +
                "LEFT JOIN account_groups pg ON pg.id = COALESCE(g.parent_id, g.id) " +
                "LEFT JOIN sector_group_rules rg ON rg.group_id = g.id " +
                "LEFT JOIN sector_group_rules rp ON rp.group_id = pg.id " +
                "WHERE a.tenant_id = ? AND a.is_active = 1 AND a.type IN ('income', 'expense') " +
                "ORDER BY a.code";

        List<Map<String, Object>> all = jdbcTemplate.queryForList(sql, tenantId);

        String amtSql = "SELECT vl.account_id, SUM(vl.debit) AS dr, SUM(vl.credit) AS cr " +
                "FROM voucher_lines vl " +
                "JOIN vouchers v ON v.id = vl.voucher_id AND v.status = 'posted' AND v.tenant_id = ? " +
                "GROUP BY vl.account_id";

        Map<Long, Map<String, BigDecimal>> amounts = new HashMap<>();
        jdbcTemplate.query(amtSql, (rs) -> {
            Long aid = rs.getLong("account_id");
            BigDecimal dr = rs.getBigDecimal("dr");
            BigDecimal cr = rs.getBigDecimal("cr");
            amounts.put(aid, Map.of(
                    "dr", dr != null ? dr : BigDecimal.ZERO,
                    "cr", cr != null ? cr : BigDecimal.ZERO
            ));
        }, tenantId);

        List<Map<String, Object>> evaluated = new ArrayList<>();
        for (Map<String, Object> a : all) {
            Long aid = toLong(a.get("id"));
            String code = String.valueOf(a.get("code"));
            String name = String.valueOf(a.get("name"));
            String type = String.valueOf(a.get("type"));
            String pGroup = a.get("primary_group_name") != null ? String.valueOf(a.get("primary_group_name")) : "Ungrouped";
            String sGroup = a.get("group_parent_id") != null ? String.valueOf(a.get("group_name")) : "";
            boolean affectsGp = toBoolean(a.get("affects_gross_profit"));
            String sectorLine = a.get("sector_line") != null ? String.valueOf(a.get("sector_line")) : "";
            String groupLine = a.get("group_line") != null ? String.valueOf(a.get("group_line")) : "";

            Map.Entry<String, String> sug = SectorMappingUtility.suggestLine(type, pGroup, sGroup, affectsGp);

            BigDecimal balance = BigDecimal.ZERO;
            if (amounts.containsKey(aid)) {
                Map<String, BigDecimal> amt = amounts.get(aid);
                BigDecimal dr = amt.get("dr");
                BigDecimal cr = amt.get("cr");
                balance = "income".equalsIgnoreCase(type) ? cr.subtract(dr) : dr.subtract(cr);
            }
            balance = balance.setScale(2, RoundingMode.HALF_UP);

            String status = SectorMappingUtility.statusOf(sectorLine, groupLine, type);
            String source = SectorMappingUtility.sourceOf(sectorLine, groupLine, type, pGroup, sGroup, affectsGp);
            String effective = SectorMappingUtility.effectiveLine(sectorLine, groupLine, type, pGroup, sGroup, affectsGp, templateKey);

            evaluated.add(Map.ofEntries(
                    Map.entry("id", aid),
                    Map.entry("code", code),
                    Map.entry("name", name),
                    Map.entry("type", type),
                    Map.entry("g1", pGroup),
                    Map.entry("g2", sGroup),
                    Map.entry("groupId", a.get("group_id") != null ? ((Number) a.get("group_id")).longValue() : (Object) ""),
                    Map.entry("amount", balance),
                    Map.entry("saved", sectorLine),
                    Map.entry("suggested", sug.getKey()),
                    Map.entry("why", sug.getValue()),
                    Map.entry("status", status),
                    Map.entry("source", source),
                    Map.entry("effective", effective)
            ));
        }

        return evaluated;
    }

    private List<Map<String, Object>> filterSectorRows(List<Map<String, Object>> rows, String q, String status, String grp, String type, String active) {
        String qClean = q != null ? q.trim().toLowerCase() : "";
        List<Map<String, Object>> res = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (status != null && !status.isBlank() && !status.equals(r.get("status"))) continue;
            if (grp != null && !grp.isBlank() && !grp.equals(r.get("g1")) && !grp.equals(r.get("g2"))) continue;
            if (type != null && !type.isBlank() && !type.equals(r.get("type"))) continue;
            if ("1".equals(active) || "true".equalsIgnoreCase(active)) {
                BigDecimal amt = (BigDecimal) r.get("amount");
                if (amt.abs().compareTo(new BigDecimal("0.005")) < 0) continue;
            }
            if (!qClean.isEmpty()) {
                String name = String.valueOf(r.get("name")).toLowerCase();
                String code = String.valueOf(r.get("code")).toLowerCase();
                if (!name.contains(qClean) && !code.contains(qClean)) continue;
            }
            res.add(r);
        }
        return res;
    }

    private List<Map<String, Object>> getCoaMappingEvaluatedRows() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank() || "control".equalsIgnoreCase(tenantId)) {
            tenantId = "1";
        }
        String sql = "SELECT a.id, a.code, a.name, a.type, a.group_id, a.sector_line, " +
                "       COALESCE(rg.line, rp.line) AS group_line, " +
                "       g.name AS group_name, g.parent_id AS group_parent_id, g.affects_gross_profit, " +
                "       pg.name AS primary_group_name " +
                "FROM accounts a " +
                "LEFT JOIN account_groups g ON g.id = a.group_id " +
                "LEFT JOIN account_groups pg ON pg.id = COALESCE(g.parent_id, g.id) " +
                "LEFT JOIN sector_group_rules rg ON rg.group_id = g.id " +
                "LEFT JOIN sector_group_rules rp ON rp.group_id = pg.id " +
                "WHERE a.tenant_id = ? AND a.is_active = 1 " +
                "ORDER BY a.code";

        List<Map<String, Object>> all = jdbcTemplate.queryForList(sql, tenantId);
        List<Map<String, Object>> evaluated = new ArrayList<>();

        for (Map<String, Object> a : all) {
            Long aid = toLong(a.get("id"));
            String code = String.valueOf(a.get("code"));
            String name = String.valueOf(a.get("name"));
            String type = String.valueOf(a.get("type"));
            String pGroup = a.get("primary_group_name") != null ? String.valueOf(a.get("primary_group_name")) : "Ungrouped";
            String sGroup = a.get("group_parent_id") != null ? String.valueOf(a.get("group_name")) : "";
            boolean affectsGp = toBoolean(a.get("affects_gross_profit"));
            String sectorLine = a.get("sector_line") != null ? String.valueOf(a.get("sector_line")) : "";
            String groupLine = a.get("group_line") != null ? String.valueOf(a.get("group_line")) : "";

            ScheduleIIIUtility.Placement place = ScheduleIIIUtility.scheduleIIIPlacement(type, pGroup, sGroup, affectsGp, sectorLine, groupLine, code);

            evaluated.add(Map.of(
                    "id", aid,
                    "code", code,
                    "name", name,
                    "type", type,
                    "g1", pGroup,
                    "g2", sGroup,
                    "place", place.getSection() + " › " + place.getLine(),
                    "status", place.getStatus()
            ));
        }

        return evaluated;
    }

    private List<Map<String, Object>> filterCoaRows(List<Map<String, Object>> rows, String q, String status, String g1, String g2, String type) {
        String qClean = q != null ? q.trim().toLowerCase() : "";
        List<Map<String, Object>> res = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (status != null && !status.isBlank() && !status.equals(r.get("status"))) continue;
            if (g1 != null && !g1.isBlank() && !g1.equals(r.get("g1"))) continue;
            if (g2 != null && !g2.isBlank()) {
                if ("-".equals(g2)) {
                    if (r.get("g2") != null && !r.get("g2").toString().isEmpty()) continue;
                } else {
                    if (!g2.equals(r.get("g2"))) continue;
                }
            }
            if (type != null && !type.isBlank() && !type.equals(r.get("type"))) continue;
            if (!qClean.isEmpty()) {
                String name = String.valueOf(r.get("name")).toLowerCase();
                String code = String.valueOf(r.get("code")).toLowerCase();
                if (!name.contains(qClean) && !code.contains(qClean)) continue;
            }
            res.add(r);
        }
        return res;
    }

    private static void addReferenceSheet(Workbook wb) {
        Sheet sheet = wb.createSheet("Reference Lists");
        List<String[]> rows = List.of(
                new String[]{"Column", "Accepted values"},
                new String[]{"GST Nature of Supply", "Taxable, Nil, Non-GST, Exempted"},
                new String[]{"GST Applicable / TDS Applicable / TCS Applicable / RCM Applicability (Y/N) / ITC Eligibility (Y/N)", "Y, N"},
                new String[]{"P&L Type", "Income, Expense, Expense-Purchases, Expense-Freight / Carriage, Expense-Power and Fuel Costs, Expense-Imported goods (Including received from SEZ), Expense-Rent and Insurance Expense, Expense-Goods lost, stolen, destroyed, written off or disposed of by way of gift or free samples, Expense-Royalties, Expense-Employee's Cost (Salaries, Wages, Bonus etc.), Expense-Conveyance charges, Expense-Bank Charges, Expense-Entertainment charges, Expense-Stationery Expenses (including postage etc.), Expense-Repair and Maintenance, Expense-Other Miscellaneous expenses, Expense-Capital goods"},
                new String[]{"Sector Report Line (income ledgers)", "Revenue from Operations, Other Income"},
                new String[]{"Sector Report Line (expense ledgers)", "Raw Materials & Purchases, Purchases of Stock-in-Trade, Direct Labour / Wages, Factory Overheads, Direct Expenses (freight inward etc.), Direct Cost of Services, Employee Cost, Selling & Distribution, Administrative & Other, Depreciation & Amortisation, Finance Cost. Leave blank to use the app's suggestion."}
        );
        for (int i = 0; i < rows.size(); i++) {
            Row r = sheet.createRow(i);
            r.createCell(0).setCellValue(rows.get(i)[0]);
            r.createCell(1).setCellValue(rows.get(i)[1]);
        }
        sheet.setColumnWidth(0, 30 * 256);
        sheet.setColumnWidth(1, 100 * 256);
    }

    private String getCell(Map<String, String> row, String key, String label) {
        String v = row.get(key);
        if (v == null || v.isBlank()) v = row.get(label);
        return v != null ? v.trim() : "";
    }

    private String parseYn(String val) {
        if (val == null || val.isBlank()) return null;
        String v = val.toLowerCase();
        if (List.of("y", "yes", "true", "1", "applicable").contains(v)) return "Y";
        if (List.of("n", "no", "false", "0", "not applicable").contains(v)) return "N";
        return val;
    }

    private String nullIfEmpty(String val) {
        return (val == null || val.isBlank()) ? null : val;
    }

    private BigDecimal parseNumOrNull(String val) {
        if (val == null || val.isBlank()) return null;
        try {
            return new BigDecimal(val.trim()).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            return null;
        }
    }

    private Long parseLongOrNull(Object val) {
        if (val == null || val.toString().isBlank()) return null;
        try {
            return Long.parseLong(val.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    private String[] parseCsvLine(String line) {
        List<String> tokens = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                tokens.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        tokens.add(sb.toString());
        return tokens.toArray(new String[0]);
    }

    private boolean toBoolean(Object obj) {
        if (obj == null) return false;
        if (obj instanceof Boolean) return (Boolean) obj;
        if (obj instanceof Number) return ((Number) obj).intValue() == 1;
        String s = String.valueOf(obj).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private Long toLong(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number) return ((Number) obj).longValue();
        try {
            return Long.parseLong(String.valueOf(obj).trim());
        } catch (Exception e) {
            return null;
        }
    }
}
