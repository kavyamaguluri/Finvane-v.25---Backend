package com.finvane.controller.invoices;

import com.finvane.model.common.ApiResponse;
import com.finvane.model.common.TenantContext;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Slf4j
@RestController
@RequestMapping({"/api/v1/settlements", "/settlements"})
@RequiredArgsConstructor
public class SettlementController {

    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/new")
    public ApiResponse<Map<String, Object>> getNewSettlementForm(
            @RequestParam(required = false) Long party,
            @RequestParam(required = false) String invoice) {

        String tenantId = TenantContext.getTenantId();
        List<Map<String, Object>> parties = jdbcTemplate.queryForList(
                "SELECT id, name, party_type FROM parties WHERE tenant_id = ? AND is_active = 1 ORDER BY LOWER(party_type), LOWER(name)",
                tenantId);

        List<Map<String, Object>> accounts = jdbcTemplate.queryForList(
                "SELECT a.id, a.code, a.name, g.name AS group_name " +
                "FROM account_ledgers a " +
                "JOIN account_groups g ON g.id = a.group_id " +
                "WHERE a.tenant_id = ? AND UPPER(g.nature) = 'ASSET' AND a.is_active = 1 " +
                "  AND g.name IN ('Bank Accounts', 'Cash-in-Hand') " +
                "ORDER BY g.name DESC, LOWER(a.name)",
                tenantId);

        Map<String, Object> partyData = null;
        List<Map<String, Object>> openInvoices = new ArrayList<>();

        if (party != null) {
            List<Map<String, Object>> pl = jdbcTemplate.queryForList(
                    "SELECT * FROM parties WHERE tenant_id = ? AND id = ? LIMIT 1", tenantId, party);
            if (!pl.isEmpty()) {
                partyData = pl.get(0);
                String pType = (String) partyData.get("party_type");
                String invType = "customer".equalsIgnoreCase(pType) ? "sales" : "purchase";
                String noteTable = "customer".equalsIgnoreCase(pType) ? "credit_notes" : "debit_notes";

                List<Map<String, Object>> invList = jdbcTemplate.queryForList(
                        "SELECT id, invoice_no, vendor_bill_no, invoice_date, total_amount, paid_amount " +
                        "FROM invoices WHERE party_id = ? AND invoice_type = ? AND status = 'posted' " +
                        "ORDER BY invoice_date, id", party, invType);

                for (Map<String, Object> i : invList) {
                    Long iid = ((Number) i.get("id")).longValue();
                    BigDecimal notes = BigDecimal.ZERO;
                    try {
                        notes = jdbcTemplate.queryForObject(
                                "SELECT COALESCE(SUM(total_amount), 0) FROM " + noteTable + " WHERE invoice_id = ? AND status = 'posted'",
                                BigDecimal.class, iid);
                    } catch (Exception ignored) {}
                    if (notes == null) notes = BigDecimal.ZERO;

                    BigDecimal tot = (BigDecimal) i.get("total_amount");
                    BigDecimal paid = (BigDecimal) i.get("paid_amount");
                    if (tot == null) tot = BigDecimal.ZERO;
                    if (paid == null) paid = BigDecimal.ZERO;

                    BigDecimal out = tot.subtract(notes).subtract(paid).setScale(2, RoundingMode.HALF_UP);
                    if (out.compareTo(new BigDecimal("0.004")) > 0) {
                        Map<String, Object> openInv = new LinkedHashMap<>(i);
                        openInv.put("notes", notes);
                        openInv.put("outstanding", out);
                        openInvoices.add(openInv);
                    }
                }
            }
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("parties", parties);
        res.put("accounts", accounts);
        res.put("party", partyData);
        res.put("invoices", openInvoices);
        res.put("initialInvoice", invoice != null ? invoice : "");
        return ApiResponse.ok(res);
    }

    @PostMapping("/new")
    @Transactional
    public ApiResponse<Map<String, Object>> createSettlement(@RequestBody Map<String, Object> body) {
        Long partyId = toLong(body.get("party_id"));
        if (partyId == null) return ApiResponse.error("Choose the vendor or customer.");

        String tenantId = TenantContext.getTenantId();
        List<Map<String, Object>> pList = jdbcTemplate.queryForList(
                "SELECT * FROM parties WHERE tenant_id = ? AND id = ? LIMIT 1", tenantId, partyId);
        if (pList.isEmpty()) return ApiResponse.error("Party not found.");
        Map<String, Object> party = pList.get(0);

        String txnDate = (String) body.get("txn_date");
        if (txnDate == null || txnDate.isBlank()) return ApiResponse.error("Enter the date.");

        Long accountId = toLong(body.get("account_id"));
        if (accountId == null) return ApiResponse.error("Choose the bank or cash account.");

        List<Map<String, Object>> bList = jdbcTemplate.queryForList(
                "SELECT * FROM account_ledgers WHERE id = ? LIMIT 1", accountId);
        if (bList.isEmpty()) return ApiResponse.error("Bank/cash account not found.");
        Map<String, Object> bank = bList.get(0);

        // Get allocations
        List<Long> invoiceIds = toLongList(body.get("invoice_id"));
        List<BigDecimal> amounts = toBigDecimalList(body.get("amount"));

        List<Map<String, Object>> allocs = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        for (int k = 0; k < invoiceIds.size(); k++) {
            Long invId = invoiceIds.get(k);
            BigDecimal amt = k < amounts.size() ? amounts.get(k) : BigDecimal.ZERO;
            if (amt == null || amt.compareTo(BigDecimal.ZERO) <= 0) continue;

            List<Map<String, Object>> iList = jdbcTemplate.queryForList(
                    "SELECT id, invoice_no, vendor_bill_no, total_amount, paid_amount FROM invoices WHERE id = ? LIMIT 1", invId);
            if (iList.isEmpty()) return ApiResponse.error("One of the bills is no longer open.");
            Map<String, Object> inv = iList.get(0);

            BigDecimal paid = (BigDecimal) inv.get("paid_amount");
            BigDecimal tot = (BigDecimal) inv.get("total_amount");
            BigDecimal out = tot.subtract(paid != null ? paid : BigDecimal.ZERO);

            if (amt.compareTo(out.add(new BigDecimal("0.004"))) > 0) {
                return ApiResponse.error(inv.get("invoice_no") + ": you can settle at most ₹" + out.setScale(2, RoundingMode.HALF_UP));
            }

            Map<String, Object> al = new LinkedHashMap<>();
            al.put("invId", invId);
            al.put("invNo", inv.get("invoice_no"));
            al.put("vendorBillNo", inv.get("vendor_bill_no"));
            al.put("amt", amt);
            allocs.add(al);
            total = total.add(amt);
        }

        if (allocs.isEmpty()) return ApiResponse.error("Enter the amount against at least one bill.");

        boolean isVendor = !"customer".equalsIgnoreCase((String) party.get("party_type"));
        String controlCode = isVendor ? "2100" : "1200";
        List<Long> cList = jdbcTemplate.queryForList(
                "SELECT id FROM account_ledgers WHERE tenant_id = ? AND LOWER(code) = LOWER(?) LIMIT 1", Long.class, tenantId, controlCode);
        if (cList.isEmpty()) {
            // fallback
            cList = jdbcTemplate.queryForList(
                    "SELECT id FROM account_ledgers WHERE tenant_id = ? AND LOWER(code) = LOWER(?) LIMIT 1", Long.class, tenantId, isVendor ? "2000" : "1000");
        }
        if (cList.isEmpty()) {
            return ApiResponse.error("The " + (isVendor ? "Accounts Payable" : "Accounts Receivable") + " ledger is missing.");
        }
        Long controlAccountId = cList.get(0);

        String prefix = isVendor ? "PAY" : "RCT";
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vouchers WHERE source = 'settlement'", Integer.class);
        String vNo = prefix + "-" + String.format("%05d", (cnt == null ? 0 : cnt) + 1);

        List<String> refs = new ArrayList<>();
        for (Map<String, Object> al : allocs) {
            String r = (String) al.get("invNo");
            if (al.get("vendorBillNo") != null && !al.get("vendorBillNo").toString().isBlank()) {
                r += " (" + al.get("vendorBillNo") + ")";
            }
            refs.add(r);
        }
        String refPart = body.get("reference") != null && !body.get("reference").toString().isBlank()
                ? " · " + body.get("reference").toString().trim() : "";
        String narration = (isVendor ? "Payment to " : "Receipt from ") + party.get("name") + " — " + String.join(", ", refs) + refPart;

        jdbcTemplate.update(
                "INSERT INTO vouchers (voucher_no, voucher_date, voucher_type, narration, status, source, source_ref, created_by, approved_by, approved_at) " +
                "VALUES (?, ?, ?, ?, 'posted', 'settlement', ?, 1, 1, NOW())",
                vNo, txnDate, isVendor ? "payment" : "receipt", narration, partyId.toString());

        Long voucherId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        if (isVendor) {
            // Dr Accounts Payable (with party_id), Cr Bank
            jdbcTemplate.update("INSERT INTO voucher_lines (voucher_id, account_id, debit, credit, line_narration, party_id) VALUES (?, ?, ?, 0, ?, ?)",
                    voucherId, controlAccountId, total, narration, partyId);
            jdbcTemplate.update("INSERT INTO voucher_lines (voucher_id, account_id, debit, credit, line_narration, party_id) VALUES (?, ?, 0, ?, ?, NULL)",
                    voucherId, accountId, total, narration);
        } else {
            // Dr Bank, Cr Accounts Receivable (with party_id)
            jdbcTemplate.update("INSERT INTO voucher_lines (voucher_id, account_id, debit, credit, line_narration, party_id) VALUES (?, ?, ?, 0, ?, NULL)",
                    voucherId, accountId, total, narration);
            jdbcTemplate.update("INSERT INTO voucher_lines (voucher_id, account_id, debit, credit, line_narration, party_id) VALUES (?, ?, 0, ?, ?, ?)",
                    voucherId, controlAccountId, total, narration, partyId);
        }

        for (Map<String, Object> al : allocs) {
            Long invId = (Long) al.get("invId");
            BigDecimal amt = (BigDecimal) al.get("amt");
            jdbcTemplate.update(
                    "INSERT INTO payment_allocations (voucher_id, invoice_id, party_id, amount) VALUES (?, ?, ?, ?)",
                    voucherId, invId, partyId, amt);
            jdbcTemplate.update(
                    "UPDATE invoices SET paid_amount = ROUND(paid_amount + ?, 2) WHERE id = ?",
                    amt, invId);
        }

        jdbcTemplate.update(
                "INSERT INTO audit_log (entity_type, entity_id, action, changed_by, detail) VALUES ('settlement', ?, 'create:settlement', 1, ?)",
                voucherId, "party=" + party.get("name") + " total=" + total + " bills=" + allocs.size());

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("voucherId", voucherId);
        res.put("voucherNo", vNo);
        res.put("total", total);
        res.put("billsSettled", allocs.size());
        return ApiResponse.ok((isVendor ? "Payment " : "Receipt ") + vNo + " of ₹" + total + " recorded successfully.", res);
    }

    @PostMapping("/{voucherId}/delete")
    @Transactional
    public ApiResponse<String> deleteSettlement(@PathVariable Long voucherId) {
        List<Map<String, Object>> vList = jdbcTemplate.queryForList(
                "SELECT * FROM vouchers WHERE id = ? AND source = 'settlement' LIMIT 1", voucherId);
        if (vList.isEmpty()) return ApiResponse.error("Payment entry not found.");
        Map<String, Object> v = vList.get(0);

        // Revert paid_amount on allocated invoices
        List<Map<String, Object>> allocs = jdbcTemplate.queryForList(
                "SELECT invoice_id, amount FROM payment_allocations WHERE voucher_id = ?", voucherId);
        for (Map<String, Object> al : allocs) {
            Long invId = toLong(al.get("invoice_id"));
            BigDecimal amt = (BigDecimal) al.get("amount");
            jdbcTemplate.update("UPDATE invoices SET paid_amount = GREATEST(0, ROUND(paid_amount - ?, 2)) WHERE id = ?", amt, invId);
        }

        jdbcTemplate.update("DELETE FROM payment_allocations WHERE voucher_id = ?", voucherId);
        jdbcTemplate.update("DELETE FROM voucher_lines WHERE voucher_id = ?", voucherId);
        jdbcTemplate.update("DELETE FROM vouchers WHERE id = ?", voucherId);

        jdbcTemplate.update(
                "INSERT INTO audit_log (entity_type, entity_id, action, changed_by, detail) VALUES ('settlement', ?, 'delete:settlement', 1, ?)",
                voucherId, "voucher_no=" + v.get("voucher_no"));

        return ApiResponse.ok("Settlement deleted; the bills are outstanding again.");
    }

    private Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private List<Long> toLongList(Object o) {
        List<Long> list = new ArrayList<>();
        if (o instanceof List) {
            for (Object item : (List<?>) o) {
                Long val = toLong(item);
                if (val != null) list.add(val);
            }
        } else if (o != null) {
            Long val = toLong(o);
            if (val != null) list.add(val);
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private List<BigDecimal> toBigDecimalList(Object o) {
        List<BigDecimal> list = new ArrayList<>();
        if (o instanceof List) {
            for (Object item : (List<?>) o) {
                if (item != null) {
                    try { list.add(new BigDecimal(item.toString())); } catch (Exception ignored) {}
                }
            }
        } else if (o != null) {
            try { list.add(new BigDecimal(o.toString())); } catch (Exception ignored) {}
        }
        return list;
    }
}

