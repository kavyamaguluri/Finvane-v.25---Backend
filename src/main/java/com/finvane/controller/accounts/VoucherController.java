package com.finvane.controller.accounts;

import com.finvane.model.common.ApiResponse;
import com.finvane.model.common.TenantContext;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Voucher / Day Book controller.
 *
 * Routes (Routes.csv rows 331–342 + legacy DELETE + delete-all):
 *   GET    /vouchers                    → getDayBook       (reports_view)
 *   GET    /vouchers/new                → getNewVoucherForm (vouchers_create)
 *   POST   /vouchers/new                → createVoucher     (vouchers_create)
 *   GET    /vouchers/export/template    → downloadTemplate  (vouchers_create)
 *   GET    /vouchers/export             → exportExcel       (reports_view)
 *   GET    /vouchers/export-pdf         → exportPdf         (reports_view)
 *   POST   /vouchers/import             → importVouchers    (vouchers_create)
 *   POST   /vouchers/delete-all         → deleteAll         (vouchers_create)
 *   GET    /vouchers/{id}/edit          → getVoucherForEdit (vouchers_create)
 *   POST   /vouchers/{id}/edit          → updateVoucher     (vouchers_create)
 *   GET    /vouchers/{id}               → getVoucher        (reports_view)
 *   POST   /vouchers/{id}/approve       → approveVoucher    (vouchers_approve)
 *   POST   /vouchers/{id}/reject        → rejectVoucher     (vouchers_approve)
 *   DELETE /vouchers/{id}               → deleteVoucher     (vouchers_create)
 *
 * Manual-only voucher types: payment, receipt, contra, journal.
 * All other types (invoice, credit_note, payroll, etc.) are auto-posted by their own modules.
 *
 * Table names: vouchers, voucher_lines, account_ledgers (alias accounts), account_groups.
 * Cross-database user names joined via finvane_control.users through the routing datasource.
 */
@Slf4j
@RestController
@RequestMapping({"/api/v1/vouchers", "/vouchers"})
@RequiredArgsConstructor
public class VoucherController {

    private final JdbcTemplate jdbcTemplate;

    // ── Voucher-type constants ───────────────────────────────────────────────

    private static final Map<String, String> PREFIX_BY_TYPE;
    static {
        PREFIX_BY_TYPE = new LinkedHashMap<>();
        PREFIX_BY_TYPE.put("payment",     "PMT");
        PREFIX_BY_TYPE.put("receipt",     "RCT");
        PREFIX_BY_TYPE.put("contra",      "CTR");
        PREFIX_BY_TYPE.put("journal",     "JV");
        PREFIX_BY_TYPE.put("sales",       "SDB");
        PREFIX_BY_TYPE.put("purchase",    "PDB");
        PREFIX_BY_TYPE.put("credit_note", "CDB");
        PREFIX_BY_TYPE.put("debit_note",  "DDB");
    }

    private static final Set<String> MANUAL_TYPES =
            new LinkedHashSet<>(Arrays.asList("payment", "receipt", "contra", "journal"));

    private static final Map<String, String> TYPE_LABEL;
    static {
        TYPE_LABEL = new LinkedHashMap<>();
        TYPE_LABEL.put("payment",          "Payment");
        TYPE_LABEL.put("receipt",          "Receipt");
        TYPE_LABEL.put("contra",           "Contra");
        TYPE_LABEL.put("journal",          "Journal");
        TYPE_LABEL.put("invoice",          "Invoice");
        TYPE_LABEL.put("sales",            "Sales");
        TYPE_LABEL.put("purchase",         "Purchase");
        TYPE_LABEL.put("credit_note",      "Credit Note");
        TYPE_LABEL.put("debit_note",       "Debit Note");
        TYPE_LABEL.put("payroll",          "Payroll");
        TYPE_LABEL.put("depreciation",     "Depreciation");
        TYPE_LABEL.put("stock_transfer",   "Stock Transfer");
        TYPE_LABEL.put("asset_acquisition","Asset Acquisition");
    }

    private static final String[] DAY_BOOK_HEADERS = {
        "Entity GSTN*", "Branch Name*", "Date", "Voucher Type*", "Type of Voucher",
        "Voucher ID*", "Voucher No.", "Line ID", "Ledger ID*", "Ledger Name",
        "Dr Amount", "Cr Amount", "Type of Ledger", "Type of Tax", "Tax Type",
        "Bank Account Number", "GSTIN"
    };

    // ── DTOs ─────────────────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DayBookLineDTO {
        private Long   voucherId;
        private String entityGstn;
        private String branchName;
        private String date;
        private String voucherType;
        private String rawVoucherType;
        private String voucherNo;
        private int    lineId;
        private String ledgerId;
        private String ledgerName;
        private BigDecimal drAmount;
        private BigDecimal crAmount;
        private String typeOfLedger;
        private String typeOfTax;
        private String taxType;
        private String bankAccountNumber;
        private String gstin;
        private String narration;
        private String status;
        private String createdBy;
        private String approvedBy;
        private String createdAt;
        private boolean editable;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class VoucherDetailDTO {
        private Long   id;
        private String voucherNo;
        private String voucherDate;
        private String voucherType;
        private String voucherTypeLabel;
        private String narration;
        private String status;
        private String source;
        private String createdBy;
        private String approvedBy;
        private String createdAt;
        private String approvedAt;
        private boolean editable;
        private List<VoucherLineDTO> lines;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class VoucherLineDTO {
        private Long   id;
        private Long   accountId;
        private String accountCode;
        private String accountName;
        private BigDecimal debit;
        private BigDecimal credit;
        private String taxType;
        private String gstin;
        private String bankAccountNumber;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class VoucherFormOptionsDTO {
        private List<Map<String, Object>> accounts;
        private List<Map<String, String>> manualVoucherTypes;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String labelFor(String type) {
        if (type == null) return "Journal";
        return TYPE_LABEL.getOrDefault(type.toLowerCase(), type);
    }

    /** Next voucher number for a given type (count-based, same as legacy). */
    private String nextVoucherNo(String type) {
        String t = PREFIX_BY_TYPE.containsKey(type) ? type : "journal";
        String prefix = PREFIX_BY_TYPE.get(t);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vouchers WHERE voucher_type = ?", Integer.class, t);
        int n = (count == null ? 0 : count) + 1;
        return prefix + "-" + String.format("%05d", n);
    }

    /** Whether a voucher row is editable here (manual only). */
    private boolean isEditable(Map<String, Object> row) {
        Object src = row.get("source");
        return src == null || "manual".equals(src.toString());
    }

    /** Split "GST - CGST" into two parts; first element = typeOfTax, second = taxType. */
    private String[] splitTaxDetail(String combined) {
        if (combined == null || combined.isBlank()) return new String[]{"", ""};
        int idx = combined.indexOf(" - ");
        if (idx == -1) return new String[]{combined, ""};
        return new String[]{combined.substring(0, idx), combined.substring(idx + 3)};
    }

    private String combineTaxDetail(String typeOfTax, String taxType) {
        String a = typeOfTax == null ? "" : typeOfTax.trim();
        String b = taxType   == null ? "" : taxType.trim();
        if (!a.isBlank() && !b.isBlank()) return a + " - " + b;
        return a.isBlank() ? b : a;
    }

    /** Check books start date from company_settings. Returns error message or null. */
    private String checkBooksStartDate(String voucherDate) {
        try {
            String startStr = jdbcTemplate.queryForObject(
                    "SELECT setting_value FROM company_settings WHERE setting_key = 'books_start_date' LIMIT 1",
                    String.class);
            if (startStr != null && !startStr.isBlank()) {
                LocalDate start = LocalDate.parse(startStr.trim());
                LocalDate vd    = LocalDate.parse(voucherDate);
                if (vd.isBefore(start)) {
                    return "Voucher date " + voucherDate + " is before the books start date " + startStr.trim() + ".";
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Look up entity name and GSTIN from finvane_control. */
    private String[] entityInfo() {
        try {
            String tenantId = TenantContext.getTenantId();
            if (tenantId == null) return new String[]{"", ""};
            // Query finvane_control.entities (available via JdbcTemplate pointing to control DB
            // when no tenant is set, or directly via fully-qualified table if on same server).
            // The TenantRoutingDataSource routes to business DB when tenantId is set, so we
            // use a plain query on the accounts table that every business DB has, and fall back.
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT setting_value FROM company_settings WHERE setting_key IN ('company_name','gstin') ORDER BY setting_key");
            String name = "", gstin = "";
            for (Map<String, Object> r : rows) {
                // We can't do key-based lookup since ordering by key gives 'company_name' first, 'gstin' second
            }
            // Simpler: query individually
            try { name  = jdbcTemplate.queryForObject("SELECT setting_value FROM company_settings WHERE setting_key='company_name' LIMIT 1", String.class); } catch (Exception ignore) {}
            try { gstin = jdbcTemplate.queryForObject("SELECT setting_value FROM company_settings WHERE setting_key='gstin' LIMIT 1",        String.class); } catch (Exception ignore) {}
            return new String[]{name  == null ? "" : name, gstin == null ? "" : gstin};
        } catch (Exception e) {
            return new String[]{"", ""};
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /vouchers  — Day Book (line-by-line)
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping({"", "/daybook"})
    public ApiResponse<List<DayBookLineDTO>> getDayBook(
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String fNo,
            @RequestParam(required = false) String fType,
            @RequestParam(required = false) String fNarration,
            @RequestParam(required = false) String fStatus,
            @RequestParam(required = false) String fCreatedBy,
            @RequestParam(required = false) String fApprovedBy,
            @RequestParam(required = false) Long   ledgerId,
            @RequestParam(defaultValue = "desc") String dateSort) {

        String[] ei = entityInfo();
        String entityName  = ei[0];
        String entityGstin = ei[1];

        StringBuilder sql = new StringBuilder("""
            SELECT
              v.id           AS voucher_id,
              v.voucher_no,
              v.voucher_date,
              v.voucher_type,
              v.narration,
              v.status,
              v.source,
              v.created_at,
              v.approved_at,
              u1.name        AS created_by_name,
              u2.name        AS approved_by_name,
              vl.id          AS line_pk,
              vl.debit,
              vl.credit,
              vl.tax_type,
              vl.gstin       AS line_gstin,
              vl.bank_account_number,
              al.code        AS ledger_code,
              al.name        AS ledger_name,
              ag.nature      AS account_nature
            FROM vouchers v
            JOIN voucher_lines vl ON vl.voucher_id = v.id
            JOIN account_ledgers al ON al.id = vl.account_id
            JOIN account_groups  ag ON ag.id = al.group_id
            LEFT JOIN users u1 ON u1.id = v.created_by
            LEFT JOIN users u2 ON u2.id = v.approved_by
            WHERE v.tenant_id = ?
            """);

        List<Object> params = new ArrayList<>();
        String currentTenant = com.finvane.model.common.TenantContext.getTenantId();
        params.add(currentTenant);

        if (fromDate != null && !fromDate.isBlank()) {
            sql.append(" AND v.voucher_date >= ?"); params.add(fromDate);
        }
        if (toDate != null && !toDate.isBlank()) {
            sql.append(" AND v.voucher_date <= ?"); params.add(toDate);
        }
        if (ledgerId != null) {
            sql.append(" AND vl.account_id = ?"); params.add(ledgerId);
        }
        if (fType != null && !fType.isBlank()) {
            sql.append(" AND v.voucher_type = ?"); params.add(fType.toLowerCase());
        }
        if (fNo != null && !fNo.isBlank()) {
            sql.append(" AND v.voucher_no LIKE ?"); params.add("%" + fNo + "%");
        }
        if (fNarration != null && !fNarration.isBlank()) {
            sql.append(" AND v.narration LIKE ?"); params.add("%" + fNarration + "%");
        }
        if (fStatus != null && !fStatus.isBlank()) {
            sql.append(" AND v.status = ?"); params.add(fStatus);
        }
        if (fCreatedBy != null && !fCreatedBy.isBlank()) {
            sql.append(" AND u1.name LIKE ?"); params.add("%" + fCreatedBy + "%");
        }
        if (fApprovedBy != null && !fApprovedBy.isBlank()) {
            sql.append(" AND u2.name LIKE ?"); params.add("%" + fApprovedBy + "%");
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + q + "%";
            sql.append(" AND (v.voucher_no LIKE ? OR v.voucher_type LIKE ? OR v.narration LIKE ? OR v.status LIKE ? OR u1.name LIKE ? OR u2.name LIKE ?)");
            params.add(like); params.add(like); params.add(like);
            params.add(like); params.add(like); params.add(like);
        }

        String order = "asc".equalsIgnoreCase(dateSort) ? "ASC" : "DESC";
        sql.append(" ORDER BY v.voucher_date ").append(order)
           .append(", v.id ").append(order)
           .append(", vl.id ASC LIMIT 2000");

        final String finalName  = entityName;
        final String finalGstin = entityGstin;
        final Map<Long, Integer> lineCounters = new HashMap<>();

        List<DayBookLineDTO> rows = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            long vId   = rs.getLong("voucher_id");
            int lineId = lineCounters.getOrDefault(vId, 0) + 1;
            lineCounters.put(vId, lineId);

            String vType   = rs.getString("voucher_type");
            BigDecimal dr  = rs.getBigDecimal("debit");
            BigDecimal cr  = rs.getBigDecimal("credit");
            if (dr != null && dr.compareTo(BigDecimal.ZERO) == 0) dr = null;
            if (cr != null && cr.compareTo(BigDecimal.ZERO) == 0) cr = null;

            String combined = rs.getString("tax_type");
            String[] taxParts = splitTaxDetail(combined);

            String nature = rs.getString("account_nature");
            String natureDisplay = nature != null && !nature.isBlank()
                    ? nature.substring(0, 1).toUpperCase() + nature.substring(1).toLowerCase() : "";

            String src = rs.getString("source");
            boolean editable = (src == null || "manual".equals(src));

            String createdBy  = rs.getString("created_by_name");
            String approvedBy = rs.getString("approved_by_name");

            return DayBookLineDTO.builder()
                    .voucherId(vId)
                    .entityGstn(finalGstin)
                    .branchName(finalName)
                    .date(rs.getString("voucher_date"))
                    .voucherType(labelFor(vType))
                    .rawVoucherType(vType)
                    .voucherNo(rs.getString("voucher_no"))
                    .lineId(lineId)
                    .ledgerId(rs.getString("ledger_code"))
                    .ledgerName(rs.getString("ledger_name"))
                    .drAmount(dr)
                    .crAmount(cr)
                    .typeOfLedger(natureDisplay)
                    .typeOfTax(taxParts[0])
                    .taxType(taxParts[1])
                    .bankAccountNumber(rs.getString("bank_account_number"))
                    .gstin(rs.getString("line_gstin"))
                    .narration(rs.getString("narration"))
                    .status(rs.getString("status"))
                    .createdBy(createdBy  != null ? createdBy  : "")
                    .approvedBy(approvedBy != null ? approvedBy : "")
                    .createdAt(rs.getString("created_at") != null ? rs.getString("created_at") : "")
                    .editable(editable)
                    .build();
        }, params.toArray());

        return ApiResponse.ok(rows);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /vouchers/form-options  — accounts + manual types for the form
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/form-options")
    public ApiResponse<VoucherFormOptionsDTO> getFormOptions() {
        List<Map<String, Object>> accounts = jdbcTemplate.queryForList(
                "SELECT id, code, name FROM account_ledgers WHERE is_active = 1 ORDER BY code");

        List<Map<String, String>> manualTypes = new ArrayList<>();
        for (String t : MANUAL_TYPES) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("value", t);
            m.put("label", labelFor(t));
            manualTypes.add(m);
        }

        return ApiResponse.ok(VoucherFormOptionsDTO.builder()
                .accounts(accounts)
                .manualVoucherTypes(manualTypes)
                .build());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /vouchers/new  — form options (same as above, alias for SPA)
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/new")
    public ApiResponse<VoucherFormOptionsDTO> getNewVoucherForm() {
        return getFormOptions();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /vouchers/new  — create voucher (manual types only)
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping({"/new", ""})
    @Transactional
    public ApiResponse<Map<String, Object>> createVoucher(@RequestBody Map<String, Object> body) {
        String voucherDate  = str(body, "voucherDate",  str(body, "voucher_date", ""));
        String narration    = str(body, "narration",    "");
        String voucherType  = str(body, "voucherType",  str(body, "voucher_type", "journal")).toLowerCase();
        if (!MANUAL_TYPES.contains(voucherType)) voucherType = "journal";

        List<Map<String, Object>> items = getLines(body);
        if (items.size() < 2) {
            return ApiResponse.error("Please enter at least two accounting lines.");
        }

        BigDecimal totalDr = BigDecimal.ZERO, totalCr = BigDecimal.ZERO;
        for (Map<String, Object> ln : items) {
            BigDecimal dr = bd(ln, "debit");
            BigDecimal cr = bd(ln, "credit");
            totalDr = totalDr.add(dr);
            totalCr = totalCr.add(cr);
        }
        if (totalDr.compareTo(BigDecimal.ZERO) == 0 ||
                totalDr.subtract(totalCr).abs().compareTo(new BigDecimal("0.01")) > 0) {
            return ApiResponse.error(
                    "Voucher is not balanced. Total Debit ₹" + totalDr.toPlainString()
                    + " must equal Total Credit ₹" + totalCr.toPlainString() + ", with at least 2 lines.");
        }

        String booksErr = checkBooksStartDate(voucherDate);
        if (booksErr != null) return ApiResponse.error(booksErr);

        final String effectiveType = voucherType;
        // Allocate voucher number with row lock to prevent concurrent duplicates
        String voucherNo = jdbcTemplate.execute((java.sql.Connection con) -> {
            try (java.sql.PreparedStatement ps = con.prepareStatement(
                    "SELECT COUNT(*) FROM vouchers WHERE voucher_type = ? FOR UPDATE")) {
                ps.setString(1, effectiveType);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    int cnt = rs.getInt(1);
                    String pfx = PREFIX_BY_TYPE.getOrDefault(effectiveType, "JV");
                    return pfx + "-" + String.format("%05d", cnt + 1);
                }
            }
        });

        final String finalVoucherType = effectiveType;
        final String finalVoucherNo   = voucherNo;

        String currentTenant = com.finvane.config.TenantContext.getTenantId();
        if (currentTenant == null || currentTenant.isBlank() || "control".equalsIgnoreCase(currentTenant)) {
            currentTenant = "1";
        }

        jdbcTemplate.update(
                "INSERT INTO vouchers (voucher_no, voucher_number, tenant_id, voucher_date, voucher_type, narration, status, total_amount, source, created_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'posted', ?, 'manual', 1)",
                finalVoucherNo, finalVoucherNo, currentTenant, voucherDate, finalVoucherType, narration, totalDr);
        Long voucherId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        for (Map<String, Object> ln : items) {
            Long accId = longVal(ln, "accountId", "account_id", "ledgerId");
            BigDecimal dr = bd(ln, "debit");
            BigDecimal cr = bd(ln, "credit");

            jdbcTemplate.update(
                    "INSERT INTO voucher_lines (voucher_id, account_id, debit, credit, tax_type, gstin, bank_account_number) VALUES (?,?,?,?,?,?,?)",
                    voucherId, accId, dr, cr,
                    strOrNull(ln, "taxType", "tax_type"),
                    strOrNull(ln, "gstin"),
                    strOrNull(ln, "bankAccountNumber", "bank_account_number"));

            if (dr.compareTo(BigDecimal.ZERO) > 0) {
                try {
                    jdbcTemplate.update(
                        "INSERT INTO voucher_items (voucher_id, ledger_id, entry_type, amount, line_narration) VALUES (?, ?, 'DEBIT', ?, ?)",
                        voucherId, accId, dr, strOrNull(ln, "lineNarration", "narration")
                    );
                } catch (Exception ignored) {}
            }
            if (cr.compareTo(BigDecimal.ZERO) > 0) {
                try {
                    jdbcTemplate.update(
                        "INSERT INTO voucher_items (voucher_id, ledger_id, entry_type, amount, line_narration) VALUES (?, ?, 'CREDIT', ?, ?)",
                        voucherId, accId, cr, strOrNull(ln, "lineNarration", "narration")
                    );
                } catch (Exception ignored) {}
            }
        }

        // Audit log
        jdbcTemplate.update(
                "INSERT INTO audit_log (entity_type, entity_id, action, changed_by, detail) VALUES ('voucher',?,?,1,?)",
                voucherId, "create:voucher",
                "voucher_no=" + finalVoucherNo + " type=" + finalVoucherType + " dr=" + totalDr);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", voucherId);
        resp.put("voucherNo", finalVoucherNo);
        resp.put("status", "pending_approval");
        return ApiResponse.ok("Voucher submitted for approval", resp);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /vouchers/{id}  — voucher detail
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/{id}")
    public ApiResponse<VoucherDetailDTO> getVoucher(@PathVariable Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT v.*, u1.name AS created_by_name, u2.name AS approved_by_name " +
                "FROM vouchers v " +
                "LEFT JOIN users u1 ON u1.id = v.created_by " +
                "LEFT JOIN users u2 ON u2.id = v.approved_by " +
                "WHERE v.id = ? LIMIT 1", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Voucher not found with ID: " + id);

        Map<String, Object> v = rows.get(0);
        List<VoucherLineDTO> lines = jdbcTemplate.query(
                "SELECT vl.*, al.code, al.name AS account_name " +
                "FROM voucher_lines vl JOIN account_ledgers al ON al.id = vl.account_id " +
                "WHERE vl.voucher_id = ? ORDER BY vl.id",
                (rs, rn) -> {
                    String combined = rs.getString("tax_type");
                    String[] tp = splitTaxDetail(combined);
                    return VoucherLineDTO.builder()
                            .id(rs.getLong("id"))
                            .accountId(rs.getLong("account_id"))
                            .accountCode(rs.getString("code"))
                            .accountName(rs.getString("account_name"))
                            .debit(rs.getBigDecimal("debit"))
                            .credit(rs.getBigDecimal("credit"))
                            .taxType(combined)
                            .gstin(rs.getString("gstin"))
                            .bankAccountNumber(rs.getString("bank_account_number"))
                            .build();
                }, id);

        String vType = str(v, "voucher_type", "journal");
        return ApiResponse.ok(VoucherDetailDTO.builder()
                .id(id)
                .voucherNo(str(v, "voucher_no", ""))
                .voucherDate(str(v, "voucher_date", ""))
                .voucherType(vType)
                .voucherTypeLabel(labelFor(vType))
                .narration(str(v, "narration", ""))
                .status(str(v, "status", ""))
                .source(str(v, "source", "manual"))
                .createdBy(str(v, "created_by_name", ""))
                .approvedBy(str(v, "approved_by_name", ""))
                .createdAt(str(v, "created_at", ""))
                .approvedAt(str(v, "approved_at", ""))
                .editable(isEditable(v))
                .lines(lines)
                .build());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /vouchers/{id}/edit  — form options pre-filled for editing
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/{id}/edit")
    public ApiResponse<Map<String, Object>> getVoucherForEdit(@PathVariable Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM vouchers WHERE id = ? LIMIT 1", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Voucher not found with ID: " + id);

        Map<String, Object> v = rows.get(0);
        if (!isEditable(v)) {
            throw new IllegalArgumentException(
                    "This voucher was created automatically from another record (invoice, payroll, asset...). Edit that record instead.");
        }

        List<Map<String, Object>> lines = jdbcTemplate.queryForList(
                "SELECT vl.account_id, vl.debit, vl.credit, al.code, al.name " +
                "FROM voucher_lines vl JOIN account_ledgers al ON al.id = vl.account_id " +
                "WHERE vl.voucher_id = ? ORDER BY vl.id", id);

        List<Map<String, Object>> accounts = jdbcTemplate.queryForList(
                "SELECT id, code, name FROM account_ledgers WHERE is_active = 1 ORDER BY code");

        List<Map<String, String>> manualTypes = new ArrayList<>();
        for (String t : MANUAL_TYPES) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("value", t); m.put("label", labelFor(t));
            manualTypes.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("voucher", v);
        result.put("lines", lines);
        result.put("accounts", accounts);
        result.put("manualVoucherTypes", manualTypes);
        return ApiResponse.ok(result);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /vouchers/{id}/edit  — update voucher; resets to pending_approval
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping("/{id}/edit")
    @Transactional
    public ApiResponse<Map<String, Object>> updateVoucher(@PathVariable Long id,
                                                          @RequestBody Map<String, Object> body) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM vouchers WHERE id = ? LIMIT 1", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Voucher not found with ID: " + id);

        Map<String, Object> old = rows.get(0);
        if (!isEditable(old)) {
            throw new IllegalArgumentException(
                    "This voucher was created automatically from another record. Edit that record instead.");
        }

        String voucherDate = str(body, "voucherDate",  str(body, "voucher_date", str(old, "voucher_date", "")));
        String narration   = str(body, "narration",    str(old, "narration", ""));
        String voucherType = str(body, "voucherType",  str(body, "voucher_type", str(old, "voucher_type", "journal"))).toLowerCase();
        if (!MANUAL_TYPES.contains(voucherType)) voucherType = str(old, "voucher_type", "journal");

        List<Map<String, Object>> items = getLines(body);
        if (items.size() < 2) return ApiResponse.error("Please enter at least two accounting lines.");

        BigDecimal totalDr = BigDecimal.ZERO, totalCr = BigDecimal.ZERO;
        for (Map<String, Object> ln : items) {
            totalDr = totalDr.add(bd(ln, "debit"));
            totalCr = totalCr.add(bd(ln, "credit"));
        }
        if (totalDr.compareTo(BigDecimal.ZERO) == 0 ||
                totalDr.subtract(totalCr).abs().compareTo(new BigDecimal("0.01")) > 0) {
            return ApiResponse.error("Voucher is not balanced. Total Debit ₹" + totalDr.toPlainString()
                    + " must equal Total Credit ₹" + totalCr.toPlainString() + ", with at least 2 lines.");
        }

        String booksErr = checkBooksStartDate(voucherDate);
        if (booksErr != null) return ApiResponse.error(booksErr);

        final String finalVoucherType = voucherType;
        jdbcTemplate.update(
                "UPDATE vouchers SET voucher_date=?, voucher_type=?, narration=?, status='pending_approval', approved_by=NULL, approved_at=NULL WHERE id=?",
                voucherDate, finalVoucherType, narration, id);
        jdbcTemplate.update("DELETE FROM voucher_lines WHERE voucher_id=?", id);

        for (Map<String, Object> ln : items) {
            jdbcTemplate.update(
                    "INSERT INTO voucher_lines (voucher_id, account_id, debit, credit, tax_type, gstin, bank_account_number) VALUES (?,?,?,?,?,?,?)",
                    id,
                    longVal(ln, "accountId", "account_id", "ledgerId"),
                    bd(ln, "debit"),
                    bd(ln, "credit"),
                    strOrNull(ln, "taxType", "tax_type"),
                    strOrNull(ln, "gstin"),
                    strOrNull(ln, "bankAccountNumber", "bank_account_number"));
        }

        jdbcTemplate.update(
                "INSERT INTO audit_log (entity_type, entity_id, action, changed_by, detail) VALUES ('voucher',?,?,1,?)",
                id, "update:voucher",
                "date=" + voucherDate + " dr=" + totalDr + " prev_status=" + old.get("status"));

        boolean wasPosted = "posted".equals(str(old, "status", ""));
        String msg = wasPosted
                ? "Voucher updated and sent back for approval (it is out of the ledger until approved again)."
                : "Voucher updated.";

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", id);
        resp.put("status", "pending_approval");
        return ApiResponse.ok(msg, resp);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /vouchers/{id}/approve  — maker-checker: segregation of duties
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping("/{id}/approve")
    @Transactional
    public ApiResponse<Map<String, Object>> approveVoucher(@PathVariable Long id,
                                                            @RequestBody(required = false) Map<String, Object> body) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM vouchers WHERE id = ? LIMIT 1", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Voucher not found with ID: " + id);

        Map<String, Object> v = rows.get(0);
        if (!"pending_approval".equals(str(v, "status", ""))) {
            throw new IllegalArgumentException("Voucher is not pending approval (status: " + v.get("status") + ").");
        }

        // Segregation of duties: get current user from SecurityContext
        Long currentUserId = currentUserId();
        Object createdBy = v.get("created_by");
        if (createdBy != null && currentUserId != null && currentUserId.equals(toLong(createdBy))) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "You cannot approve a voucher you created yourself. Have another authorized user approve it.");
        }

        jdbcTemplate.update(
                "UPDATE vouchers SET status='posted', approved_by=?, approved_at=NOW() WHERE id=?",
                currentUserId, id);
        jdbcTemplate.update(
                "INSERT INTO audit_log (entity_type, entity_id, action, changed_by, detail) VALUES ('voucher',?,?,?,?)",
                id, "approve:voucher", currentUserId, "voucher_no=" + v.get("voucher_no"));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", id);
        resp.put("status", "posted");
        return ApiResponse.ok("Voucher approved and posted to ledger.", resp);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /vouchers/{id}/reject
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping("/{id}/reject")
    @Transactional
    public ApiResponse<Map<String, Object>> rejectVoucher(@PathVariable Long id,
                                                           @RequestBody(required = false) Map<String, Object> body) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM vouchers WHERE id = ? LIMIT 1", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Voucher not found with ID: " + id);

        Map<String, Object> v = rows.get(0);
        if (!"pending_approval".equals(str(v, "status", ""))) {
            throw new IllegalArgumentException("Voucher is not pending approval.");
        }

        Long currentUserId = currentUserId();
        jdbcTemplate.update(
                "UPDATE vouchers SET status='rejected', approved_by=?, approved_at=NOW() WHERE id=?",
                currentUserId, id);
        jdbcTemplate.update(
                "INSERT INTO audit_log (entity_type, entity_id, action, changed_by, detail) VALUES ('voucher',?,?,?,?)",
                id, "reject:voucher", currentUserId, "voucher_no=" + v.get("voucher_no"));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", id); resp.put("status", "rejected");
        return ApiResponse.ok("Voucher rejected.", resp);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DELETE /vouchers/{id}  — cascade delete lines
    // ─────────────────────────────────────────────────────────────────────────
    @DeleteMapping("/{id}")
    @Transactional
    public ApiResponse<String> deleteVoucher(@PathVariable Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM vouchers WHERE id = ? LIMIT 1", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Voucher not found with ID: " + id);

        jdbcTemplate.update("DELETE FROM voucher_lines WHERE voucher_id=?", id);
        jdbcTemplate.update("DELETE FROM vouchers WHERE id=?", id);

        jdbcTemplate.update(
                "INSERT INTO audit_log (entity_type, entity_id, action, changed_by, detail) VALUES ('voucher',?,?,1,?)",
                id, "delete:voucher", "id=" + id);

        return ApiResponse.ok("Voucher deleted successfully.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /vouchers/delete-all  — delete all matching the current view filter
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping("/delete-all")
    @Transactional
    public ApiResponse<Map<String, Object>> deleteAll(@RequestBody(required = false) Map<String, Object> body) {
        String fromDate = body != null ? str(body, "fromDate", str(body, "from_date", "")) : "";
        String toDate   = body != null ? str(body, "toDate",   str(body, "to_date", ""))   : "";
        String fType    = body != null ? str(body, "fType",    "") : "";
        String fStatus  = body != null ? str(body, "fStatus",  "") : "";

        StringBuilder whereSql = new StringBuilder("WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (!fromDate.isBlank()) { whereSql.append(" AND v.voucher_date >= ?"); params.add(fromDate); }
        if (!toDate.isBlank())   { whereSql.append(" AND v.voucher_date <= ?"); params.add(toDate); }
        if (!fType.isBlank())    { whereSql.append(" AND v.voucher_type = ?"); params.add(fType); }
        if (!fStatus.isBlank())  { whereSql.append(" AND v.status = ?"); params.add(fStatus); }

        // Limit to manual vouchers only — auto-posted vouchers (invoices, payroll) deleted through their own module
        whereSql.append(" AND (v.source IS NULL OR v.source = 'manual')");

        List<Map<String, Object>> toDelete = jdbcTemplate.queryForList(
                "SELECT v.id FROM vouchers v " + whereSql + " LIMIT 1000", params.toArray());

        int deleted = 0;
        List<String> errors = new ArrayList<>();
        for (Map<String, Object> row : toDelete) {
            Long vid = toLong(row.get("id"));
            try {
                jdbcTemplate.update("DELETE FROM voucher_lines WHERE voucher_id=?", vid);
                jdbcTemplate.update("DELETE FROM vouchers WHERE id=?", vid);
                deleted++;
            } catch (Exception e) {
                errors.add("#" + vid + ": " + e.getMessage());
            }
        }

        jdbcTemplate.update(
                "INSERT INTO audit_log (entity_type, entity_id, action, changed_by, detail) VALUES ('voucher',NULL,?,1,?)",
                "delete_all:voucher",
                "deleted=" + deleted + " attempted=" + toDelete.size());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("deleted", deleted);
        resp.put("attempted", toDelete.size());
        if (!errors.isEmpty()) resp.put("errors", errors.subList(0, Math.min(5, errors.size())));
        return ApiResponse.ok("Deleted " + deleted + " of " + toDelete.size() + " vouchers.", resp);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /vouchers/export/template  — blank Day Book Excel template
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/export/template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"day-book-template.xlsx\"");

        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Day Book Template");
            Row headerRow = sheet.createRow(0);
            CellStyle bold = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(true);
            bold.setFont(font);

            for (int i = 0; i < DAY_BOOK_HEADERS.length; i++) {
                Cell c = headerRow.createCell(i);
                c.setCellValue(DAY_BOOK_HEADERS[i]);
                c.setCellStyle(bold);
                sheet.setColumnWidth(i, 4000);
            }
            wb.write(response.getOutputStream());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /vouchers/export  — full Day Book Excel export (same column layout)
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/export")
    public void exportExcel(HttpServletResponse response,
                             @RequestParam(required = false) String fromDate,
                             @RequestParam(required = false) String toDate) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"day-book.xlsx\"");

        String[] ei = entityInfo();
        String entityName  = ei[0];
        String entityGstin = ei[1];

        StringBuilder sql = new StringBuilder("""
            SELECT v.id AS voucher_id, v.voucher_no, v.voucher_date, v.voucher_type,
                   al.code AS ledger_code,
                   al.name AS ledger_name,
                   ag.nature AS account_nature,
                   vl.debit, vl.credit, vl.tax_type, vl.gstin, vl.bank_account_number
            FROM voucher_lines vl
            JOIN vouchers v ON v.id = vl.voucher_id
            JOIN account_ledgers al ON al.id = vl.account_id
            JOIN account_groups  ag ON ag.id = al.group_id
            WHERE 1=1
            """);
        List<Object> params = new ArrayList<>();
        if (fromDate != null && !fromDate.isBlank()) { sql.append(" AND v.voucher_date >= ?"); params.add(fromDate); }
        if (toDate   != null && !toDate.isBlank())   { sql.append(" AND v.voucher_date <= ?"); params.add(toDate); }
        sql.append(" ORDER BY v.id, vl.id");

        List<Map<String, Object>> raw = jdbcTemplate.queryForList(sql.toString(), params.toArray());

        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Day Book");
            CellStyle bold = wb.createCellStyle();
            Font font = wb.createFont(); font.setBold(true); bold.setFont(font);

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < DAY_BOOK_HEADERS.length; i++) {
                Cell c = headerRow.createCell(i);
                c.setCellValue(DAY_BOOK_HEADERS[i]);
                c.setCellStyle(bold);
                sheet.setColumnWidth(i, 4500);
            }

            Map<Long, Integer> lineCounters = new HashMap<>();
            int rowNum = 1;
            for (Map<String, Object> r : raw) {
                Long vid = toLong(r.get("voucher_id"));
                int lineId = lineCounters.getOrDefault(vid, 0) + 1;
                lineCounters.put(vid, lineId);

                String[] tp = splitTaxDetail((String) r.get("tax_type"));
                String typeLabel = labelFor((String) r.get("voucher_type"));
                Object drObj = r.get("debit");
                Object crObj = r.get("credit");
                double dr = drObj != null ? ((Number) drObj).doubleValue() : 0;
                double cr = crObj != null ? ((Number) crObj).doubleValue() : 0;

                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(entityGstin);
                row.createCell(1).setCellValue(entityName);
                row.createCell(2).setCellValue(str(r, "voucher_date", ""));
                row.createCell(3).setCellValue(typeLabel);
                row.createCell(4).setCellValue(typeLabel);
                row.createCell(5).setCellValue(vid);
                row.createCell(6).setCellValue(str(r, "voucher_no", ""));
                row.createCell(7).setCellValue(lineId);
                row.createCell(8).setCellValue(str(r, "ledger_code", ""));
                row.createCell(9).setCellValue(str(r, "ledger_name", ""));
                if (dr > 0) row.createCell(10).setCellValue(dr);
                if (cr > 0) row.createCell(11).setCellValue(cr);
                row.createCell(12).setCellValue(str(r, "account_nature", ""));
                row.createCell(13).setCellValue(tp[0]);
                row.createCell(14).setCellValue(tp[1]);
                row.createCell(15).setCellValue(r.get("bank_account_number") != null ? r.get("bank_account_number").toString() : "");
                row.createCell(16).setCellValue(r.get("gstin") != null ? r.get("gstin").toString() : "");
            }

            wb.write(response.getOutputStream());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /vouchers/export-pdf  — PDF summary (OpenPDF)
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/export-pdf")
    public ApiResponse<List<Map<String, Object>>> exportPdf(
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {
        // Return data for client-side PDF or forward to a PDF utility.
        // The Angular component can call window.print() as the legacy does,
        // or POST to a future PDF endpoint.  Return the voucher list JSON for now.
        StringBuilder sql = new StringBuilder(
                "SELECT v.id, v.voucher_no, v.voucher_date, v.voucher_type, v.narration, v.status, " +
                "u1.name AS created_by_name " +
                "FROM vouchers v LEFT JOIN users u1 ON u1.id = v.created_by WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (fromDate != null && !fromDate.isBlank()) { sql.append(" AND v.voucher_date >= ?"); params.add(fromDate); }
        if (toDate   != null && !toDate.isBlank())   { sql.append(" AND v.voucher_date <= ?"); params.add(toDate); }
        sql.append(" ORDER BY v.id DESC LIMIT 500");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), params.toArray());
        rows.forEach(r -> r.put("voucherTypeLabel", labelFor((String) r.get("voucher_type"))));
        return ApiResponse.ok(rows);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /vouchers/import  — Day Book bulk import
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping("/import")
    @Transactional
    public ApiResponse<Map<String, Object>> importVouchers(
            @RequestParam("file") MultipartFile file) throws IOException {

        List<Map<String, Object>> errors = new ArrayList<>();
        int created = 0;

        // Parse Excel/CSV
        List<Map<String, String>> rows = parseUploadedFile(file);
        if (rows.isEmpty()) {
            return ApiResponse.error("Could not read the uploaded file or file is empty.");
        }

        // Group by "Voucher ID*"
        Map<String, List<Map<String, String>>> groups = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> row = rows.get(i);
            String vid = cell(row, "Voucher ID*", "Voucher ID");
            if (vid.isBlank()) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("row", i + 2);
                err.put("message", "Voucher ID is required to group lines into a voucher");
                errors.add(err);
                continue;
            }
            groups.computeIfAbsent(vid, k -> new ArrayList<>()).add(row);
        }

        for (Map.Entry<String, List<Map<String, String>>> entry : groups.entrySet()) {
            String voucherIdKey = entry.getKey();
            List<Map<String, String>> entries = entry.getValue();
            int firstRowNum = rows.indexOf(entries.get(0)) + 2;

            Map<String, String> first = entries.get(0);
            String date = normalizeDate(first.get("Date"));
            if (date.isBlank()) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("row", firstRowNum);
                err.put("message", "Voucher ID \"" + voucherIdKey + "\": Date \"" + first.get("Date") + "\" is missing or not a recognisable date");
                errors.add(err);
                continue;
            }

            String voucherType = normalizeVoucherType(
                    cell(first, "Voucher Type*", "Voucher Type"),
                    first.getOrDefault("Type of Voucher", ""));

            // Check/generate voucher number
            String voucherNo = entries.stream()
                    .map(r -> r.getOrDefault("Voucher No.", "").trim())
                    .filter(s -> !s.isBlank())
                    .findFirst().orElse("");
            if (!voucherNo.isBlank()) {
                Integer exists = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM vouchers WHERE voucher_no = ?", Integer.class, voucherNo);
                if (exists != null && exists > 0) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("row", firstRowNum);
                    err.put("message", "Voucher ID \"" + voucherIdKey + "\": Voucher No. \"" + voucherNo + "\" already exists — leave the column blank to auto-generate one.");
                    errors.add(err);
                    continue;
                }
            } else {
                voucherNo = nextVoucherNo(voucherType);
            }

            // Build lines
            BigDecimal totalDr = BigDecimal.ZERO, totalCr = BigDecimal.ZERO;
            List<Map<String, Object>> lineData = new ArrayList<>();
            String lineError = null;

            for (Map<String, String> row : entries) {
                String ledgerCode = cell(row, "Ledger ID*", "Ledger ID");
                String ledgerName = cell(row, "Ledger Name");
                if (ledgerCode.isBlank() && ledgerName.isBlank()) {
                    lineError = "Voucher ID \"" + voucherIdKey + "\": Ledger ID or Ledger Name is required";
                    break;
                }

                // Resolve ledger by code first, then name
                Long accountId = null;
                if (!ledgerCode.isBlank()) {
                    List<Long> ids = jdbcTemplate.queryForList(
                            "SELECT id FROM account_ledgers WHERE code = ? LIMIT 1", Long.class, ledgerCode);
                    if (!ids.isEmpty()) accountId = ids.get(0);
                }
                if (accountId == null && !ledgerName.isBlank()) {
                    List<Long> ids = jdbcTemplate.queryForList(
                            "SELECT id FROM account_ledgers WHERE LOWER(TRIM(name)) = LOWER(?) LIMIT 1",
                            Long.class, ledgerName.trim());
                    if (!ids.isEmpty()) accountId = ids.get(0);
                }
                if (accountId == null) {
                    // Auto-create under unclassified group based on Type of Ledger
                    String nature = normalizeLedgerType(row.getOrDefault("Type of Ledger", ""));
                    if (nature == null) {
                        lineError = "Voucher ID \"" + voucherIdKey + "\": unknown ledger (" +
                                (ledgerCode.isBlank() ? ledgerName : ledgerCode) +
                                ") — add a valid \"Type of Ledger\" (Asset/Liability/Equity/Income/Expense) to auto-create it.";
                        break;
                    }
                    accountId = getOrCreateLedger(ledgerCode, ledgerName, nature);
                }

                double dr = parseAmount(row.getOrDefault("Dr Amount", ""));
                double cr = parseAmount(row.getOrDefault("Cr Amount", ""));
                String taxType = combineTaxDetail(
                        row.getOrDefault("Type of Tax", ""),
                        row.getOrDefault("Tax Type", ""));

                totalDr = totalDr.add(BigDecimal.valueOf(dr));
                totalCr = totalCr.add(BigDecimal.valueOf(cr));

                Map<String, Object> ln = new LinkedHashMap<>();
                ln.put("accountId", accountId);
                ln.put("debit",  BigDecimal.valueOf(dr));
                ln.put("credit", BigDecimal.valueOf(cr));
                ln.put("taxType", taxType.isBlank() ? null : taxType);
                ln.put("gstin",  row.getOrDefault("GSTIN", "").isBlank() ? null : row.get("GSTIN"));
                ln.put("bankAccountNumber", row.getOrDefault("Bank Account Number", "").isBlank() ? null : row.get("Bank Account Number"));
                lineData.add(ln);
            }

            if (lineError != null) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("row", firstRowNum); err.put("message", lineError);
                errors.add(err); continue;
            }
            if (totalDr.compareTo(BigDecimal.ZERO) == 0 ||
                    totalDr.subtract(totalCr).abs().compareTo(new BigDecimal("0.01")) > 0) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("row", firstRowNum);
                err.put("message", "Voucher ID \"" + voucherIdKey + "\": Dr total ₹" + totalDr.toPlainString()
                        + " does not equal Cr total ₹" + totalCr.toPlainString());
                errors.add(err); continue;
            }

            // Insert voucher as 'posted' (matches legacy import behavior)
            final String finalVoucherNo   = voucherNo;
            final String finalVoucherType = voucherType;
            String impTenant = com.finvane.config.TenantContext.getTenantId();
            if (impTenant == null || impTenant.isBlank() || "control".equalsIgnoreCase(impTenant)) impTenant = "1";

            jdbcTemplate.update(
                    "INSERT INTO vouchers (voucher_no, voucher_number, tenant_id, voucher_date, voucher_type, narration, status, total_amount, source, source_ref, created_by, approved_by, approved_at) " +
                    "VALUES (?, ?, ?, ?, ?, '', 'posted', ?, 'day_book_import', ?, 1, 1, NOW())",
                    finalVoucherNo, finalVoucherNo, impTenant, date, finalVoucherType, totalDr, voucherIdKey);

            Long newId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            for (Map<String, Object> ln : lineData) {
                jdbcTemplate.update(
                        "INSERT INTO voucher_lines (voucher_id, account_id, debit, credit, tax_type, gstin, bank_account_number) VALUES (?,?,?,?,?,?,?)",
                        newId, ln.get("accountId"), ln.get("debit"), ln.get("credit"),
                        ln.get("taxType"), ln.get("gstin"), ln.get("bankAccountNumber"));

                BigDecimal dr = bd(ln, "debit");
                BigDecimal cr = bd(ln, "credit");
                Long accId = longVal(ln, "accountId", "account_id");
                if (dr.compareTo(BigDecimal.ZERO) > 0) {
                    try {
                        jdbcTemplate.update(
                            "INSERT INTO voucher_items (voucher_id, ledger_id, entry_type, amount, line_narration) VALUES (?, ?, 'DEBIT', ?, ?)",
                            newId, accId, dr, strOrNull(ln, "lineNarration", "narration")
                        );
                    } catch (Exception ignored) {}
                }
                if (cr.compareTo(BigDecimal.ZERO) > 0) {
                    try {
                        jdbcTemplate.update(
                            "INSERT INTO voucher_items (voucher_id, ledger_id, entry_type, amount, line_narration) VALUES (?, ?, 'CREDIT', ?, ?)",
                            newId, accId, cr, strOrNull(ln, "lineNarration", "narration")
                        );
                    } catch (Exception ignored) {}
                }
            }
            jdbcTemplate.update(
                    "INSERT INTO audit_log (entity_type, entity_id, action, changed_by, detail) VALUES ('voucher',?,?,1,?)",
                    newId, "create:voucher", "import source_ref=" + voucherIdKey + " type=" + finalVoucherType);
            created++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total",   groups.size());
        result.put("created", created);
        result.put("errors",  errors);
        String msg = created + " of " + groups.size() + " vouchers imported.";
        if (!errors.isEmpty()) msg += " " + errors.size() + " row(s) skipped.";
        return ApiResponse.ok(msg, result);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Import helpers
    // ─────────────────────────────────────────────────────────────────────────

    private List<Map<String, String>> parseUploadedFile(MultipartFile file) throws IOException {
        List<Map<String, String>> result = new ArrayList<>();
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        if (filename.endsWith(".xlsx") || filename.endsWith(".xls")) {
            try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
                Sheet sheet = wb.getSheetAt(0);
                Row headerRow = sheet.getRow(0);
                if (headerRow == null) return result;
                List<String> headers = new ArrayList<>();
                for (Cell c : headerRow) headers.add(c.getStringCellValue().trim());

                for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    Map<String, String> map = new LinkedHashMap<>();
                    for (int c = 0; c < headers.size(); c++) {
                        Cell cell = row.getCell(c);
                        String val = cell == null ? "" : switch (cell.getCellType()) {
                            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                                    ? cell.getLocalDateTimeCellValue().toLocalDate().toString()
                                    : String.valueOf((long) cell.getNumericCellValue());
                            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                            default -> cell.getStringCellValue().trim();
                        };
                        map.put(headers.get(c), val);
                    }
                    if (!map.values().stream().allMatch(String::isBlank)) result.add(map);
                }
            }
        } else {
            // CSV fallback
            String content = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String[] lines = content.split("\n");
            if (lines.length < 2) return result;
            String[] headers = lines[0].split(",");
            for (int i = 0; i < headers.length; i++) headers[i] = headers[i].trim().replace("\"", "");
            for (int r = 1; r < lines.length; r++) {
                String[] vals = lines[r].split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
                Map<String, String> map = new LinkedHashMap<>();
                for (int c = 0; c < headers.length; c++) {
                    map.put(headers[c], c < vals.length ? vals[c].trim().replace("\"", "") : "");
                }
                if (!map.values().stream().allMatch(String::isBlank)) result.add(map);
            }
        }
        return result;
    }

    private String cell(Map<String, String> row, String... keys) {
        for (String k : keys) {
            String v = row.get(k);
            if (v != null && !v.isBlank()) return v.trim();
            // Also try without asterisk
            v = row.get(k.replace("*", ""));
            if (v != null && !v.isBlank()) return v.trim();
        }
        return "";
    }

    private String normalizeDate(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String t = raw.trim();
        // yyyy-mm-dd
        if (t.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) return t;
        // dd-Mon-yy or dd/mm/yyyy
        String[] MONTHS = {"jan","feb","mar","apr","may","jun","jul","aug","sep","oct","nov","dec"};
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(\\d{1,2})[-/ .]([A-Za-z]{3})[A-Za-z]*[-/ .,]*(\\d{2,4})$").matcher(t);
        if (m.matches()) {
            int day = Integer.parseInt(m.group(1));
            String monStr = m.group(2).toLowerCase();
            int mon = java.util.Arrays.asList(MONTHS).indexOf(monStr) + 1;
            int year = Integer.parseInt(m.group(3));
            if (year < 100) year += year < 70 ? 2000 : 1900;
            return String.format("%04d-%02d-%02d", year, mon, day);
        }
        // dd/mm/yyyy
        java.util.regex.Matcher m2 = java.util.regex.Pattern
                .compile("^(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{2,4})$").matcher(t);
        if (m2.matches()) {
            int day = Integer.parseInt(m2.group(1)), mon = Integer.parseInt(m2.group(2));
            int year = Integer.parseInt(m2.group(3));
            if (year < 100) year += year < 70 ? 2000 : 1900;
            return String.format("%04d-%02d-%02d", year, mon, day);
        }
        // Excel serial
        if (t.matches("\\d{5}")) {
            long serial = Long.parseLong(t);
            java.time.LocalDate epoch = java.time.LocalDate.of(1899, 12, 30);
            return epoch.plusDays(serial).toString();
        }
        return "";
    }

    private String normalizeVoucherType(String... raws) {
        for (String raw : raws) {
            String v = (raw == null ? "" : raw).trim().toLowerCase();
            if (v.equals("payment") || v.equals("pmt")) return "payment";
            if (v.equals("receipt") || v.equals("rct")) return "receipt";
            if (v.equals("contra")  || v.equals("ctr")) return "contra";
            if (v.equals("journal") || v.equals("jv"))  return "journal";
            if (v.startsWith("sales") || v.equals("si")) return "sales";
            if (v.startsWith("purchase") || v.equals("pi")) return "purchase";
            if (v.contains("credit")) return "credit_note";
            if (v.contains("debit"))  return "debit_note";
        }
        return "journal";
    }

    private String normalizeLedgerType(String raw) {
        String v = (raw == null ? "" : raw).trim().toLowerCase();
        if (v.startsWith("asset"))   return "asset";
        if (v.startsWith("liab"))    return "liability";
        if (v.equals("equity"))      return "equity";
        if (v.startsWith("income") || v.equals("revenue") || v.equals("revenues")) return "income";
        if (v.startsWith("expense")) return "expense";
        return null;
    }

    private Long getOrCreateLedger(String code, String name, String nature) {
        // Find or create an "Unclassified (Imported)" group for this nature
        String groupName = "Unclassified " + capitalize(nature) + " (Imported)";
        List<Long> gids = jdbcTemplate.queryForList(
                "SELECT id FROM account_groups WHERE name = ? LIMIT 1", Long.class, groupName);
        Long groupId;
        if (gids.isEmpty()) {
            jdbcTemplate.update(
                    "INSERT INTO account_groups (name, nature, is_system, sort_order) VALUES (?, ?, 1, 995)",
                    groupName, nature);
            groupId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        } else {
            groupId = gids.get(0);
        }

        String useCode = code.isBlank() ? nextAutoCode(nature) : code;
        jdbcTemplate.update(
                "INSERT INTO account_ledgers (code, name, type, group_id, is_active) VALUES (?, ?, ?, ?, 1)",
                useCode, name.isBlank() ? code : name, nature, groupId);
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private String nextAutoCode(String nature) {
        Map<String, String> pfx = Map.of(
                "asset","IMP-A","liability","IMP-L","equity","IMP-E","income","IMP-I","expense","IMP-X");
        String prefix = pfx.getOrDefault(nature, "IMP-X");
        for (int n = 1; n < 10000; n++) {
            String code = prefix + "-" + String.format("%04d", n);
            Integer cnt = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account_ledgers WHERE code = ?", Integer.class, code);
            if (cnt == null || cnt == 0) return code;
        }
        return prefix + "-" + System.currentTimeMillis();
    }

    private double parseAmount(String raw) {
        try { return Double.parseDouble(raw.replaceAll("[^0-9.]", "")); } catch (Exception e) { return 0; }
    }

    private String capitalize(String s) {
        if (s == null || s.isBlank()) return "";
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Generic map helpers
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getLines(Map<String, Object> body) {
        Object raw = body.get("items");
        if (raw == null) raw = body.get("lines");
        if (raw instanceof List) {
            return ((List<Map<String, Object>>) raw).stream()
                    .filter(ln -> {
                        Long aid = longVal(ln, "accountId", "account_id", "ledgerId");
                        BigDecimal dr = bd(ln, "debit");
                        BigDecimal cr = bd(ln, "credit");
                        return aid != null && aid > 0 && (dr.compareTo(BigDecimal.ZERO) > 0 || cr.compareTo(BigDecimal.ZERO) > 0);
                    })
                    .collect(java.util.stream.Collectors.toList());
        }
        return Collections.emptyList();
    }

    private String str(Map<?, ?> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : v.toString();
    }

    private String strOrNull(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && !v.toString().isBlank()) return v.toString();
        }
        return null;
    }

    private BigDecimal bd(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal) return (BigDecimal) v;
        if (v instanceof Number) return BigDecimal.valueOf(((Number) v).doubleValue());
        try { return new BigDecimal(v.toString()); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    private Long longVal(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null) return toLong(v);
        }
        return null;
    }

    private Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Long) return (Long) v;
        if (v instanceof Number) return ((Number) v).longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return null; }
    }

    private Long currentUserId() {
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
            if (auth == null) return 1L;
            Object principal = auth.getPrincipal();
            if (principal instanceof org.springframework.security.core.userdetails.UserDetails) {
                // Extract id from username if it's stored as id
                String username = ((org.springframework.security.core.userdetails.UserDetails) principal).getUsername();
                try { return Long.parseLong(username); } catch (Exception ignore) {}
            }
            return 1L;
        } catch (Exception e) {
            return 1L;
        }
    }
}
