package com.finvane.controller.common;

import com.finvane.model.common.TenantContext;
import com.finvane.service.audit.AuditLogService;
import com.finvane.utils.CryptoUtility;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@RestController
@RequestMapping({"/api/v1/parties", "/parties"})
@PreAuthorize("hasAuthority('invoices_manage')")
public class PartyController {

    private final JdbcTemplate jdbcTemplate;
    private final AuditLogService auditLogService;

    private static final String[] HEADERS = {
            "name", "party_type (customer/vendor)", "gstin", "pan", "state_code", "address", "email", "phone"
    };

    public PartyController(JdbcTemplate jdbcTemplate, AuditLogService auditLogService) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditLogService = auditLogService;
    }

    private Map<String, Object> findDuplicate(String name, String partyType, String gstin) {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank() || "control".equalsIgnoreCase(tenantId)) {
            tenantId = "1";
        }

        List<Map<String, Object>> byName = jdbcTemplate.queryForList(
                "SELECT * FROM parties WHERE is_active = 1 AND tenant_id = ? AND LOWER(TRIM(party_type)) = LOWER(TRIM(?)) AND LOWER(TRIM(name)) = LOWER(TRIM(?)) LIMIT 1",
                tenantId, partyType, name
        );
        if (!byName.isEmpty()) return byName.get(0);

        if (gstin != null && !gstin.trim().isEmpty()) {
            List<Map<String, Object>> byGstin = jdbcTemplate.queryForList(
                    "SELECT * FROM parties WHERE is_active = 1 AND tenant_id = ? AND LOWER(TRIM(party_type)) = LOWER(TRIM(?)) AND UPPER(TRIM(gstin)) = UPPER(TRIM(?)) LIMIT 1",
                    tenantId, partyType, gstin.trim()
            );
            if (!byGstin.isEmpty()) return byGstin.get(0);
        }
        return null;
    }

    // 1. GET /parties and GET /api/v1/parties
    @GetMapping
    public ResponseEntity<?> listParties(
            @RequestParam(value = "party_type", required = false) String partyType,
            @RequestParam(value = "type", required = false) String typeAlias) {
        String resolvedType = (partyType != null && !partyType.isBlank()) ? partyType : typeAlias;
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank() || "control".equalsIgnoreCase(tenantId)) {
            tenantId = "1";
        }

        String sql = "SELECT * FROM parties WHERE is_active = 1 AND tenant_id = ? " +
                (resolvedType != null && !resolvedType.isBlank() ? "AND LOWER(TRIM(party_type)) = LOWER(TRIM(?)) " : "") +
                "ORDER BY LOWER(name) ASC";
        List<Map<String, Object>> parties;
        if (resolvedType != null && !resolvedType.isBlank()) {
            parties = jdbcTemplate.queryForList(sql, tenantId, resolvedType.trim());
        } else {
            parties = jdbcTemplate.queryForList(sql, tenantId);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> p : parties) {
            Map<String, Object> copy = new LinkedHashMap<>(p);
            copy.put("partyType", p.get("party_type"));
            copy.put("stateCode", p.get("state_code"));
            String panEnc = (String) p.get("pan_enc");
            copy.put("pan_masked", panEnc != null && !panEnc.isBlank() ? CryptoUtility.mask("ON FILE") : "");
            result.add(copy);
        }
        return ResponseEntity.ok(Map.of("ok", true, "parties", result, "data", result));
    }

    // 2. GET /parties/new and GET /api/v1/parties/form-options
    @GetMapping({"/new", "/form-options"})
    public ResponseEntity<?> getPartyFormOptions() {
        List<Map<String, String>> types = List.of(
                Map.of("code", "customer", "label", "Customer"),
                Map.of("code", "vendor", "label", "Vendor")
        );
        return ResponseEntity.ok(Map.of("ok", true, "partyTypes", types));
    }

    // 3. POST /parties and POST /parties/new and POST /api/v1/parties
    @PostMapping({"", "/new"})
    public ResponseEntity<?> createParty(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String name = stringVal(body.get("name"));
        String partyType = stringVal(body.containsKey("party_type") ? body.get("party_type") : body.get("partyType")).toLowerCase();
        String gstin = stringVal(body.get("gstin"));
        String pan = stringVal(body.get("pan"));
        String stateCode = stringVal(body.containsKey("state_code") ? body.get("state_code") : body.get("stateCode"));
        String address = stringVal(body.get("address"));
        String email = stringVal(body.get("email"));
        String phone = stringVal(body.get("phone"));

        if (name.isBlank() || partyType.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Name and party_type are required."));
        }
        if (!"customer".equals(partyType) && !"vendor".equals(partyType)) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "party_type must be customer or vendor."));
        }

        Map<String, Object> dup = findDuplicate(name, partyType, gstin);
        if (dup != null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "ok", false,
                    "error", "\"" + dup.get("name") + "\" already exists as a " + dup.get("party_type") + ", so it was not added again."
            ));
        }

        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank() || "control".equalsIgnoreCase(tenantId)) {
            tenantId = "1";
        }
        String panEnc = pan.isBlank() ? null : CryptoUtility.encrypt(pan);

        jdbcTemplate.update("""
            INSERT INTO parties (tenant_id, name, party_type, gstin, pan, pan_enc, state_code, address, email, phone, opening_balance, current_balance, is_active)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0.00, 0.00, 1)
        """, tenantId, name, partyType, gstin.isBlank() ? null : gstin, pan.isBlank() ? null : pan, panEnc, stateCode.isBlank() ? "27" : stateCode, address.isBlank() ? null : address, email.isBlank() ? null : email, phone.isBlank() ? null : phone);

        Long newId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        syncPartyLedger(tenantId, newId, name, partyType, null);
        auditLogService.logAction("create", "party", String.valueOf(newId), null, Map.of("name", name, "party_type", partyType, "gstin", gstin, "state_code", stateCode), request);

        Map<String, Object> createdData = Map.of("id", newId, "name", name, "partyType", partyType, "stateCode", stateCode.isBlank() ? "27" : stateCode, "gstin", gstin);
        return ResponseEntity.ok(Map.of("ok", true, "id", newId, "data", createdData, "message", "Party created successfully."));
    }

    // 4. POST /parties/quick-add and POST /api/v1/parties/quick-add
    @PostMapping("/quick-add")
    public ResponseEntity<?> quickAddParty(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String name = stringVal(body.get("name"));
        String partyType = stringVal(body.containsKey("party_type") ? body.get("party_type") : body.get("partyType")).toLowerCase();
        String gstin = stringVal(body.get("gstin"));
        String pan = stringVal(body.get("pan"));
        String stateCode = stringVal(body.containsKey("state_code") ? body.get("state_code") : body.get("stateCode"));
        String address = stringVal(body.get("address"));
        String email = stringVal(body.get("email"));
        String phone = stringVal(body.get("phone"));

        if (name.isBlank() || partyType.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Name is required."));
        }
        if (!"customer".equals(partyType) && !"vendor".equals(partyType)) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "party_type must be customer or vendor."));
        }

        Map<String, Object> dup = findDuplicate(name, partyType, gstin);
        if (dup != null) {
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "id", dup.get("id"),
                    "name", dup.get("name"),
                    "state_code", dup.get("state_code") != null ? dup.get("state_code") : "",
                    "existing", true
            ));
        }

        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank() || "control".equalsIgnoreCase(tenantId)) {
            tenantId = "1";
        }
        String panEnc = pan.isBlank() ? null : CryptoUtility.encrypt(pan);

        jdbcTemplate.update("""
            INSERT INTO parties (tenant_id, name, party_type, gstin, pan, pan_enc, state_code, address, email, phone, opening_balance, current_balance, is_active)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0.00, 0.00, 1)
        """, tenantId, name, partyType, gstin.isBlank() ? null : gstin, pan.isBlank() ? null : pan, panEnc, stateCode.isBlank() ? "27" : stateCode, address.isBlank() ? null : address, email.isBlank() ? null : email, phone.isBlank() ? null : phone);

        Long newId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        syncPartyLedger(tenantId, newId, name, partyType, null);
        auditLogService.logAction("create", "party", String.valueOf(newId), null, Map.of("name", name, "party_type", partyType, "gstin", gstin, "state_code", stateCode), request);

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "id", newId,
                "name", name,
                "state_code", stateCode
        ));
    }

    // 5. GET /parties/{id} and GET /parties/{id}/edit
    @GetMapping({"/{id}", "/{id}/edit"})
    public ResponseEntity<?> getParty(@PathVariable("id") long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM parties WHERE id = ?", id);
        if (rows.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", "Party not found."));
        }
        Map<String, Object> p = new LinkedHashMap<>(rows.get(0));
        String panEnc = (String) p.get("pan_enc");
        p.put("pan_on_file", panEnc != null && !panEnc.isBlank());
        p.put("pan_masked", panEnc != null && !panEnc.isBlank() ? CryptoUtility.mask("ON FILE") : "");
        p.remove("pan_enc"); // Keep raw ciphertext private
        return ResponseEntity.ok(Map.of("ok", true, "party", p));
    }

    // 6. PUT /parties/{id} and POST /parties/{id} and POST /parties/{id}/edit
    @RequestMapping(value = {"/{id}", "/{id}/edit"}, method = {RequestMethod.PUT, RequestMethod.POST})
    public ResponseEntity<?> updateParty(@PathVariable("id") long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM parties WHERE id = ?", id);
        if (rows.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", "Party not found."));
        }
        Map<String, Object> old = rows.get(0);

        String name = stringVal(body.get("name"));
        String partyType = stringVal(body.containsKey("party_type") ? body.get("party_type") : body.get("partyType")).toLowerCase();
        String gstin = stringVal(body.get("gstin"));
        String pan = stringVal(body.get("pan"));
        String stateCode = stringVal(body.containsKey("state_code") ? body.get("state_code") : body.get("stateCode"));
        String address = stringVal(body.get("address"));
        String email = stringVal(body.get("email"));
        String phone = stringVal(body.get("phone"));

        if (name.isBlank() || partyType.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Name and party_type are required."));
        }

        String tenantId = old.get("tenant_id") != null ? String.valueOf(old.get("tenant_id")) : TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank() || "control".equalsIgnoreCase(tenantId)) {
            tenantId = "1";
        }

        List<Map<String, Object>> clash = jdbcTemplate.queryForList(
                "SELECT id FROM parties WHERE id <> ? AND tenant_id = ? AND is_active = 1 AND LOWER(TRIM(party_type)) = LOWER(TRIM(?)) AND LOWER(TRIM(name)) = LOWER(TRIM(?)) LIMIT 1",
                id, tenantId, partyType, name
        );
        if (!clash.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Another " + partyType + " is already named \"" + name + "\"."));
        }

        String panEnc = (pan != null && !pan.isBlank()) ? CryptoUtility.encrypt(pan) : (String) old.get("pan_enc");

        jdbcTemplate.update(
                "UPDATE parties SET name = ?, party_type = ?, gstin = ?, pan_enc = ?, state_code = ?, address = ?, email = ?, phone = ? WHERE id = ?",
                name, partyType, gstin.isBlank() ? null : gstin, panEnc, stateCode.isBlank() ? null : stateCode,
                address.isBlank() ? null : address, email.isBlank() ? null : email, phone.isBlank() ? null : phone, id
        );

        syncPartyLedger(tenantId, id, name, partyType, stringVal(old.get("name")));

        auditLogService.logAction("update", "party", String.valueOf(id),
                Map.of("name", old.get("name"), "gstin", String.valueOf(old.get("gstin"))),
                Map.of("name", name, "gstin", gstin), request);

        return ResponseEntity.ok(Map.of("ok", true, "message", "Saved. Past invoices keep showing this party with the updated details."));
    }

    // 7. DELETE /parties/{id} and POST /parties/{id}/delete
    @RequestMapping(value = {"/{id}", "/{id}/delete"}, method = {RequestMethod.DELETE, RequestMethod.POST})
    public ResponseEntity<?> deleteParty(@PathVariable("id") long id, HttpServletRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM parties WHERE id = ?", id);
        if (rows.isEmpty()) {
            return ResponseEntity.ok(Map.of("ok", true, "message", "Party already removed."));
        }
        Map<String, Object> party = rows.get(0);
        String name = (String) party.get("name");
        String partyType = stringVal(party.get("party_type")).toLowerCase();
        String tenantId = party.get("tenant_id") != null ? String.valueOf(party.get("tenant_id")) : TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank() || "control".equalsIgnoreCase(tenantId)) tenantId = "1";

        boolean used = false;
        try {
            Integer invCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoices WHERE party_id = ?", Integer.class, id);
            if (invCount != null && invCount > 0) used = true;
        } catch (Exception ignored) {}

        if (!used) {
            try {
                Integer vlCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM voucher_lines WHERE party_id = ?", Integer.class, id);
                if (vlCount != null && vlCount > 0) used = true;
            } catch (Exception ignored) {}
        }

        String ledgerCode = ("vendor".equals(partyType) ? "VEND-" : "CUST-") + id;
        if (used) {
            jdbcTemplate.update("UPDATE parties SET is_active = 0 WHERE id = ?", id);
            jdbcTemplate.update("UPDATE accounts SET is_active = 0 WHERE tenant_id = ? AND code = ?", tenantId, ledgerCode);
            auditLogService.logAction("deactivate", "party", String.valueOf(id), party, null, request);
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "message", "\"" + name + "\" has transactions, so it was made inactive instead of deleted. Its past records are unchanged."
            ));
        } else {
            jdbcTemplate.update("DELETE FROM parties WHERE id = ?", id);
            jdbcTemplate.update("DELETE FROM accounts WHERE tenant_id = ? AND code = ?", tenantId, ledgerCode);
            try {
                jdbcTemplate.update("DELETE FROM account_ledgers WHERE tenant_id = ? AND code = ?", tenantId, ledgerCode);
            } catch (Exception ignored) {}
            auditLogService.logAction("delete", "party", String.valueOf(id), party, null, request);
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "message", "\"" + name + "\" deleted."
            ));
        }
    }

    // 8. GET /parties/outstanding and GET /api/v1/parties/outstanding
    @GetMapping("/outstanding")
    public ResponseEntity<?> getOutstanding(@RequestParam(value = "type", defaultValue = "customer") String type) {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank() || "control".equalsIgnoreCase(tenantId)) tenantId = "1";
        String partyType = "vendor".equalsIgnoreCase(type) ? "vendor" : "customer";
        String invType = "vendor".equalsIgnoreCase(type) ? "purchase" : "sales";
        String ntTable = "vendor".equalsIgnoreCase(type) ? "debit_notes" : "credit_notes";
        LocalDate today = LocalDate.now();

        List<Map<String, Object>> parties = jdbcTemplate.queryForList(
                "SELECT * FROM parties WHERE tenant_id = ? AND is_active = 1 AND party_type = ? ORDER BY name", tenantId, partyType
        );

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal totalBilled = BigDecimal.ZERO;
        BigDecimal totalNotes = BigDecimal.ZERO;
        BigDecimal totalPaid = BigDecimal.ZERO;
        BigDecimal totalOutstanding = BigDecimal.ZERO;
        BigDecimal totalB0 = BigDecimal.ZERO;
        BigDecimal totalB30 = BigDecimal.ZERO;
        BigDecimal totalB60 = BigDecimal.ZERO;
        BigDecimal totalB90 = BigDecimal.ZERO;

        for (Map<String, Object> p : parties) {
            long partyId = ((Number) p.get("id")).longValue();
            String partyName = (String) p.get("name");
            String gstin = (String) p.get("gstin");

            BigDecimal billed = BigDecimal.ZERO;
            BigDecimal notes = BigDecimal.ZERO;
            BigDecimal paid = BigDecimal.ZERO;
            BigDecimal outstanding = BigDecimal.ZERO;
            BigDecimal b0 = BigDecimal.ZERO;
            BigDecimal b30 = BigDecimal.ZERO;
            BigDecimal b60 = BigDecimal.ZERO;
            BigDecimal b90 = BigDecimal.ZERO;

            List<Map<String, Object>> invoices = jdbcTemplate.queryForList(
                    "SELECT id, total_amount, paid_amount, due_date, invoice_date FROM invoices WHERE party_id = ? AND invoice_type = ? AND status = 'posted'",
                    partyId, invType
            );

            for (Map<String, Object> inv : invoices) {
                long invId = ((Number) inv.get("id")).longValue();
                BigDecimal invTotal = bigDecimalVal(inv.get("total_amount"));
                BigDecimal invPaid = bigDecimalVal(inv.get("paid_amount"));

                BigDecimal notesTotal = BigDecimal.ZERO;
                try {
                    notesTotal = jdbcTemplate.queryForObject(
                            "SELECT COALESCE(SUM(total_amount), 0) FROM " + ntTable + " WHERE invoice_id = ? AND status = 'posted'",
                            BigDecimal.class, invId
                    );
                    if (notesTotal == null) notesTotal = BigDecimal.ZERO;
                } catch (Exception ignored) {}

                BigDecimal out = invTotal.subtract(notesTotal).subtract(invPaid).setScale(2, RoundingMode.HALF_UP);
                billed = billed.add(invTotal);
                notes = notes.add(notesTotal);
                paid = paid.add(invPaid);
                outstanding = outstanding.add(out);

                if (out.compareTo(new BigDecimal("0.004")) > 0) {
                    Object dateObj = inv.get("due_date") != null ? inv.get("due_date") : inv.get("invoice_date");
                    LocalDate refDate;
                    if (dateObj instanceof java.sql.Date sd) {
                        refDate = sd.toLocalDate();
                    } else if (dateObj instanceof java.time.LocalDate ld) {
                        refDate = ld;
                    } else {
                        refDate = today;
                    }
                    long age = ChronoUnit.DAYS.between(refDate, today);
                    if (age <= 0) {
                        b0 = b0.add(out);
                    } else if (age <= 30) {
                        b30 = b30.add(out);
                    } else if (age <= 60) {
                        b60 = b60.add(out);
                    } else {
                        b90 = b90.add(out);
                    }
                }
            }

            if (outstanding.abs().compareTo(new BigDecimal("0.004")) > 0 || billed.compareTo(BigDecimal.ZERO) > 0) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", partyId);
                r.put("name", partyName);
                r.put("gstin", gstin);
                r.put("billed", billed.setScale(2, RoundingMode.HALF_UP));
                r.put("notes", notes.setScale(2, RoundingMode.HALF_UP));
                r.put("paid", paid.setScale(2, RoundingMode.HALF_UP));
                r.put("outstanding", outstanding.setScale(2, RoundingMode.HALF_UP));
                r.put("b0", b0.setScale(2, RoundingMode.HALF_UP));
                r.put("b30", b30.setScale(2, RoundingMode.HALF_UP));
                r.put("b60", b60.setScale(2, RoundingMode.HALF_UP));
                r.put("b90", b90.setScale(2, RoundingMode.HALF_UP));
                rows.add(r);

                totalBilled = totalBilled.add(billed);
                totalNotes = totalNotes.add(notes);
                totalPaid = totalPaid.add(paid);
                totalOutstanding = totalOutstanding.add(outstanding);
                totalB0 = totalB0.add(b0);
                totalB30 = totalB30.add(b30);
                totalB60 = totalB60.add(b60);
                totalB90 = totalB90.add(b90);
            }
        }

        // Control ledger balance
        String ctrlCode = "vendor".equalsIgnoreCase(partyType) ? "2100" : "1200";
        BigDecimal ledgerBalance = null;
        try {
            List<Map<String, Object>> actList = jdbcTemplate.queryForList("SELECT id FROM accounts WHERE code = ?", ctrlCode);
            if (!actList.isEmpty()) {
                long actId = ((Number) actList.get(0).get("id")).longValue();
                Map<String, Object> sums = jdbcTemplate.queryForMap(
                        "SELECT COALESCE(SUM(vl.debit), 0) d, COALESCE(SUM(vl.credit), 0) c " +
                                "FROM voucher_lines vl JOIN vouchers v ON v.id = vl.voucher_id " +
                                "WHERE vl.account_id = ? AND v.status = 'posted'", actId
                );
                BigDecimal d = bigDecimalVal(sums.get("d"));
                BigDecimal c = bigDecimalVal(sums.get("c"));
                ledgerBalance = "vendor".equalsIgnoreCase(partyType) ? c.subtract(d) : d.subtract(c);
                ledgerBalance = ledgerBalance.setScale(2, RoundingMode.HALF_UP);
            }
        } catch (Exception ignored) {}

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("billed", totalBilled.setScale(2, RoundingMode.HALF_UP));
        totals.put("notes", totalNotes.setScale(2, RoundingMode.HALF_UP));
        totals.put("paid", totalPaid.setScale(2, RoundingMode.HALF_UP));
        totals.put("outstanding", totalOutstanding.setScale(2, RoundingMode.HALF_UP));
        totals.put("b0", totalB0.setScale(2, RoundingMode.HALF_UP));
        totals.put("b30", totalB30.setScale(2, RoundingMode.HALF_UP));
        totals.put("b60", totalB60.setScale(2, RoundingMode.HALF_UP));
        totals.put("b90", totalB90.setScale(2, RoundingMode.HALF_UP));

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "title", "vendor".equalsIgnoreCase(partyType) ? "Vendor outstanding (Accounts Payable)" : "Customer outstanding (Accounts Receivable)",
                "type", partyType,
                "rows", rows,
                "totals", totals,
                "ledgerBalance", ledgerBalance != null ? ledgerBalance : BigDecimal.ZERO
        ));
    }

    // 9. GET /parties/{id}/statement and GET /api/v1/parties/{id}/statement
    @GetMapping({"/{id}/statement"})
    public ResponseEntity<?> getStatement(@PathVariable("id") long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM parties WHERE id = ?", id);
        if (rows.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", "Party not found."));
        }
        Map<String, Object> party = rows.get(0);
        String partyType = (String) party.get("party_type");
        String invType = "customer".equalsIgnoreCase(partyType) ? "sales" : "purchase";
        String ntTable = "customer".equalsIgnoreCase(partyType) ? "credit_notes" : "debit_notes";
        String dateCol = "customer".equalsIgnoreCase(partyType) ? "cn_date" : "dn_date";

        List<Map<String, Object>> invoices = jdbcTemplate.queryForList(
                "SELECT * FROM invoices WHERE party_id = ? AND invoice_type = ? ORDER BY invoice_date, id",
                id, invType
        );

        List<Map<String, Object>> notes = new ArrayList<>();
        try {
            notes = jdbcTemplate.queryForList(
                    "SELECT * FROM " + ntTable + " WHERE party_id = ? ORDER BY " + dateCol + ", id", id
            );
        } catch (Exception ignored) {}

        Map<Long, List<Map<String, Object>>> notesByInvoice = new HashMap<>();
        for (Map<String, Object> n : notes) {
            Number invIdNum = (Number) n.get("invoice_id");
            if (invIdNum != null) {
                notesByInvoice.computeIfAbsent(invIdNum.longValue(), k -> new ArrayList<>()).add(n);
            }
        }

        BigDecimal running = BigDecimal.ZERO;
        List<Map<String, Object>> statementRows = new ArrayList<>();

        for (Map<String, Object> inv : invoices) {
            long invId = ((Number) inv.get("id")).longValue();
            List<Map<String, Object>> invNotes = notesByInvoice.getOrDefault(invId, Collections.emptyList());

            BigDecimal notesTotal = BigDecimal.ZERO;
            for (Map<String, Object> n : invNotes) {
                notesTotal = notesTotal.add(bigDecimalVal(n.get("total_amount")));
            }
            notesTotal = notesTotal.setScale(2, RoundingMode.HALF_UP);

            BigDecimal totalAmount = bigDecimalVal(inv.get("total_amount")).setScale(2, RoundingMode.HALF_UP);
            BigDecimal paidAmount = bigDecimalVal(inv.get("paid_amount")).setScale(2, RoundingMode.HALF_UP);
            BigDecimal netAmount = totalAmount.subtract(notesTotal).setScale(2, RoundingMode.HALF_UP);
            BigDecimal outstanding = netAmount.subtract(paidAmount).setScale(2, RoundingMode.HALF_UP);
            running = running.add(outstanding).setScale(2, RoundingMode.HALF_UP);

            Map<String, Object> r = new LinkedHashMap<>(inv);
            r.put("notesTotal", notesTotal);
            r.put("netAmount", netAmount);
            r.put("outstanding", outstanding);
            r.put("runningBalance", running);
            r.put("notes", invNotes);
            statementRows.add(r);
        }

        BigDecimal totalOutstanding = BigDecimal.ZERO;
        for (Map<String, Object> r : statementRows) {
            totalOutstanding = totalOutstanding.add((BigDecimal) r.get("outstanding"));
        }
        totalOutstanding = totalOutstanding.setScale(2, RoundingMode.HALF_UP);

        // Payments recorded
        List<Map<String, Object>> payments = new ArrayList<>();
        try {
            payments = jdbcTemplate.queryForList(
                    "SELECT v.id, v.voucher_no, v.voucher_date, v.narration, SUM(pa.amount) amount " +
                            "FROM payment_allocations pa JOIN vouchers v ON v.id = pa.voucher_id " +
                            "WHERE pa.party_id = ? GROUP BY v.id ORDER BY v.voucher_date, v.id", id
            );
            for (Map<String, Object> p : payments) {
                long voucherId = ((Number) p.get("id")).longValue();
                List<Map<String, Object>> bills = jdbcTemplate.queryForList(
                        "SELECT i.invoice_no, pa.amount FROM payment_allocations pa JOIN invoices i ON i.id = pa.invoice_id WHERE pa.voucher_id = ?",
                        voucherId
                );
                p.put("bills", bills);
            }
        } catch (Exception ignored) {}

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "title", "Statement — " + party.get("name"),
                "party", party,
                "rows", statementRows,
                "totalOutstanding", totalOutstanding,
                "payments", payments
        ));
    }

    // 10. GET /parties/export/template and GET /parties/template
    @GetMapping({"/export/template", "/template"})
    public ResponseEntity<byte[]> downloadPartyTemplate() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Parties Template");
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(HEADERS[i]);
            }

            Row exampleRow = sheet.createRow(1);
            exampleRow.createCell(0).setCellValue("Acme Technologies Pvt Ltd");
            exampleRow.createCell(1).setCellValue("customer");
            exampleRow.createCell(2).setCellValue("27AAACA1234A1Z5");
            exampleRow.createCell(3).setCellValue("AAACA1234A");
            exampleRow.createCell(4).setCellValue("27");
            exampleRow.createCell(5).setCellValue("101 Tech Park, Mumbai");
            exampleRow.createCell(6).setCellValue("accounts@acme.com");
            exampleRow.createCell(7).setCellValue("+91 9876543210");

            for (int i = 0; i < HEADERS.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(out);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=parties-template.xlsx")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // 11. GET /parties/export
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportPartyExcel() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank() || "control".equalsIgnoreCase(tenantId)) tenantId = "1";
        List<Map<String, Object>> parties = jdbcTemplate.queryForList("SELECT * FROM parties WHERE tenant_id = ? AND is_active = 1 ORDER BY name", tenantId);
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Customers & Vendors");
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(HEADERS[i]);
            }

            int rowIdx = 1;
            for (Map<String, Object> p : parties) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(stringVal(p.get("name")));
                row.createCell(1).setCellValue(stringVal(p.get("party_type")));
                row.createCell(2).setCellValue(stringVal(p.get("gstin")));
                row.createCell(3).setCellValue("(encrypted, not exported)");
                row.createCell(4).setCellValue(stringVal(p.get("state_code")));
                row.createCell(5).setCellValue(stringVal(p.get("address")));
                row.createCell(6).setCellValue(stringVal(p.get("email")));
                row.createCell(7).setCellValue(stringVal(p.get("phone")));
            }

            for (int i = 0; i < HEADERS.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(out);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=parties.xlsx")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // 12. POST /parties/import
    @PostMapping("/import")
    public ResponseEntity<?> importParties(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Please upload a valid file."));
        }

        List<Map<String, Object>> errors = new ArrayList<>();
        int created = 0;
        int total = 0;

        try (InputStream is = file.getInputStream(); Workbook workbook = WorkbookFactory.create(is)) {
            Sheet sheet = workbook.getSheetAt(0);
            int lastRowNum = sheet.getLastRowNum();

            for (int r = 1; r <= lastRowNum; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                total++;

                String name = getCellValue(row.getCell(0)).trim();
                String partyType = getCellValue(row.getCell(1)).trim().toLowerCase();
                String gstin = getCellValue(row.getCell(2)).trim();
                String pan = getCellValue(row.getCell(3)).trim();
                String stateCode = getCellValue(row.getCell(4)).trim();
                String address = getCellValue(row.getCell(5)).trim();
                String email = getCellValue(row.getCell(6)).trim();
                String phone = getCellValue(row.getCell(7)).trim();

                int rowNum = r + 1;
                if (name.isBlank()) {
                    errors.add(Map.of("row", rowNum, "message", "name is required"));
                    continue;
                }
                if (!"customer".equals(partyType) && !"vendor".equals(partyType)) {
                    errors.add(Map.of("row", rowNum, "message", "party_type must be customer or vendor"));
                    continue;
                }

                Map<String, Object> dup = findDuplicate(name, partyType, gstin);
                if (dup != null) {
                    errors.add(Map.of("row", rowNum, "message", "\"" + name + "\" already exists — skipped"));
                    continue;
                }

                String tenantId = TenantContext.getTenantId();
                if (tenantId == null || tenantId.isBlank() || "control".equalsIgnoreCase(tenantId)) {
                    tenantId = "1";
                }
                final String finalTenantId = tenantId;
                try {
                    String panEnc = pan.isBlank() ? null : CryptoUtility.encrypt(pan);
                    KeyHolder keyHolder = new GeneratedKeyHolder();
                    jdbcTemplate.update(connection -> {
                        PreparedStatement ps = connection.prepareStatement(
                                "INSERT INTO parties (tenant_id, name, party_type, gstin, pan_enc, state_code, address, email, phone, is_active) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
                                Statement.RETURN_GENERATED_KEYS
                        );
                        ps.setString(1, finalTenantId);
                        ps.setString(2, name);
                        ps.setString(3, partyType);
                        ps.setString(4, gstin.isBlank() ? null : gstin);
                        ps.setString(5, panEnc);
                        ps.setString(6, stateCode.isBlank() ? "27" : stateCode);
                        ps.setString(7, address.isBlank() ? null : address);
                        ps.setString(8, email.isBlank() ? null : email);
                        ps.setString(9, phone.isBlank() ? null : phone);
                        return ps;
                    }, keyHolder);

                    long newId = Objects.requireNonNull(keyHolder.getKey()).longValue();
                    syncPartyLedger(finalTenantId, newId, name, partyType, null);
                    auditLogService.logAction("create", "party", String.valueOf(newId), null, Map.of("name", name, "party_type", partyType), request);
                    created++;
                } catch (Exception ex) {
                    errors.add(Map.of("row", rowNum, "message", "could not import this row (" + ex.getMessage() + ")"));
                }
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Could not read the uploaded file: " + e.getMessage()));
        }

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "total", total,
                "created", created,
                "errors", errors,
                "moduleName", "Customers & Vendors"
        ));
    }

    private void syncPartyLedger(String tenantId, Long partyId, String name, String partyType, String oldName) {
        if (partyId == null || name == null || name.isBlank()) return;
        boolean isVendor = "vendor".equalsIgnoreCase(partyType);
        String code = (isVendor ? "VEND-" : "CUST-") + partyId;
        String type = isVendor ? "liability" : "asset";
        String groupName = isVendor ? "Sundry Creditors" : "Sundry Debtors";

        Long groupId = null;
        try {
            List<Map<String, Object>> grpRows = jdbcTemplate.queryForList(
                    "SELECT id FROM account_groups WHERE name = ? LIMIT 1", groupName
            );
            if (!grpRows.isEmpty() && grpRows.get(0).get("id") != null) {
                groupId = ((Number) grpRows.get(0).get("id")).longValue();
            }
        } catch (Exception ignored) {}
        if (groupId == null) {
            groupId = isVendor ? 21L : 29L;
        }

        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "SELECT id FROM accounts WHERE tenant_id = ? AND (code = ? OR code = ?) LIMIT 1",
                tenantId, code, (isVendor ? "CUST-" : "VEND-") + partyId
        );
        if (existing.isEmpty() && oldName != null && !oldName.isBlank()) {
            existing = jdbcTemplate.queryForList(
                    "SELECT id FROM accounts WHERE tenant_id = ? AND LOWER(TRIM(name)) = LOWER(TRIM(?)) LIMIT 1",
                    tenantId, oldName
            );
        }

        if (existing.isEmpty()) {
            jdbcTemplate.update(
                    "INSERT INTO accounts (tenant_id, code, name, type, group_id, opening_balance, is_active) VALUES (?, ?, ?, ?, ?, 0.00, 1)",
                    tenantId, code, name, type, groupId
            );
            Long accId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            try {
                jdbcTemplate.update("""
                    INSERT INTO account_ledgers (id, code, name, group_id, opening_balance, current_balance, is_active, tenant_id, created_at)
                    VALUES (?, ?, ?, ?, 0.00, 0.00, 1, ?, NOW())
                    ON DUPLICATE KEY UPDATE name = VALUES(name), group_id = VALUES(group_id)
                """, accId, code, name, groupId, tenantId);
            } catch (Exception ignored) {}
        } else {
            Long accId = ((Number) existing.get(0).get("id")).longValue();
            jdbcTemplate.update(
                    "UPDATE accounts SET code = ?, name = ?, type = ?, group_id = ? WHERE id = ?",
                    code, name, type, groupId, accId
            );
            try {
                jdbcTemplate.update(
                        "UPDATE account_ledgers SET code = ?, name = ?, group_id = ? WHERE id = ?",
                        code, name, groupId, accId
                );
            } catch (Exception ignored) {}
        }
    }

    private static String stringVal(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static BigDecimal bigDecimalVal(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal bd) return bd;
        try {
            return new BigDecimal(o.toString());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private static String getCellValue(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                double val = cell.getNumericCellValue();
                if (val == (long) val) yield String.valueOf((long) val);
                yield String.valueOf(val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }
}
