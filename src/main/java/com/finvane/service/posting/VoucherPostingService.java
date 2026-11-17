package com.finvane.service.posting;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;

@Service
public class VoucherPostingService {

    private final JdbcTemplate jdbcTemplate;
    private final FifoValuationService fifoValuationService;

    public VoucherPostingService(JdbcTemplate jdbcTemplate, FifoValuationService fifoValuationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.fifoValuationService = fifoValuationService;
    }

    public record VoucherLineEntry(
        Long accountId,
        BigDecimal debit,
        BigDecimal credit,
        String lineNarration,
        Long partyId,
        String taxType,
        String bankAccountNumber,
        String gstin
    ) {
        public VoucherLineEntry(Long accountId, BigDecimal debit, BigDecimal credit) {
            this(accountId, debit, credit, null, null, null, null, null);
        }
    }

    public record LineItem(
        long itemId,
        boolean isGoods,
        BigDecimal qty,
        BigDecimal rate,
        BigDecimal taxableValue,
        BigDecimal gstRate,
        BigDecimal cgst,
        BigDecimal sgst,
        BigDecimal igst,
        BigDecimal totalAmount
    ) {}

    public record Allocation(
        long invoiceId,
        BigDecimal amount
    ) {}

    // -------------------------------------------------------------
    // Helper Account Lookups
    // -------------------------------------------------------------
    public Long getAccountIdByCode(String code) {
        List<Long> ids = jdbcTemplate.query(
            "SELECT id FROM accounts WHERE code = ? LIMIT 1",
            (rs, rowNum) -> rs.getLong("id"),
            code
        );
        return ids.isEmpty() ? null : ids.get(0);
    }

    public Long getAccountIdByName(String name) {
        List<Long> ids = jdbcTemplate.query(
            "SELECT id FROM accounts WHERE name = ? LIMIT 1",
            (rs, rowNum) -> rs.getLong("id"),
            name
        );
        return ids.isEmpty() ? null : ids.get(0);
    }

    public Long getRcmAccountId(String head) {
        String name = switch (head.toLowerCase()) {
            case "cgst" -> "CGST Payable under RCM";
            case "sgst" -> "SGST Payable under RCM";
            case "igst" -> "IGST Payable under RCM / Import";
            default -> throw new IllegalArgumentException("Unknown RCM head: " + head);
        };
        Long id = getAccountIdByName(name);
        if (id == null) {
            // Fallback or ensure system ledger
            Long groupId = jdbcTemplate.query(
                "SELECT id FROM account_groups WHERE name = 'Duties and Taxes' LIMIT 1",
                (rs, rowNum) -> rs.getLong("id")
            ).stream().findFirst().orElse(null);

            jdbcTemplate.update("""
                INSERT INTO accounts (code, name, type, group_id, is_active)
                VALUES (?, ?, 'liability', ?, 1)
            """, "RCM-" + head.toUpperCase(), name, groupId);
            id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        }
        return id;
    }

    // -------------------------------------------------------------
    // Generic Balanced Voucher Creator
    // -------------------------------------------------------------
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Long createVoucher(String voucherNo,
                              LocalDate voucherDate,
                              String voucherType,
                              String narration,
                              String status,
                              String source,
                              String sourceRef,
                              Long createdBy,
                              Long approvedBy,
                              List<VoucherLineEntry> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("Voucher lines cannot be empty.");
        }

        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;

        for (VoucherLineEntry line : lines) {
            BigDecimal dr = line.debit() == null ? BigDecimal.ZERO : line.debit().setScale(2, RoundingMode.HALF_UP);
            BigDecimal cr = line.credit() == null ? BigDecimal.ZERO : line.credit().setScale(2, RoundingMode.HALF_UP);
            totalDebit = totalDebit.add(dr);
            totalCredit = totalCredit.add(cr);
        }

        if (totalDebit.compareTo(totalCredit) != 0) {
            throw new IllegalStateException(
                String.format("Unbalanced voucher: total debit (₹%s) does not equal total credit (₹%s)",
                    totalDebit.toPlainString(), totalCredit.toPlainString())
            );
        }

        String approvedAtSql = "posted".equals(status) ? "CURRENT_TIMESTAMP(3)" : "NULL";

        jdbcTemplate.update(String.format("""
            INSERT INTO vouchers (voucher_no, voucher_date, voucher_type, narration, status, source, source_ref, created_by, approved_by, approved_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, %s)
        """, approvedAtSql), voucherNo, voucherDate, voucherType, narration, status, source, sourceRef, createdBy, approvedBy);

        Long voucherId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        for (VoucherLineEntry line : lines) {
            BigDecimal dr = line.debit() == null ? BigDecimal.ZERO : line.debit().setScale(2, RoundingMode.HALF_UP);
            BigDecimal cr = line.credit() == null ? BigDecimal.ZERO : line.credit().setScale(2, RoundingMode.HALF_UP);

            if (dr.compareTo(BigDecimal.ZERO) == 0 && cr.compareTo(BigDecimal.ZERO) == 0) {
                continue; // skip zero-value lines
            }

            jdbcTemplate.update("""
                INSERT INTO voucher_lines (voucher_id, account_id, debit, credit, line_narration, party_id, tax_type, bank_account_number, gstin)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, voucherId, line.accountId(), dr, cr, line.lineNarration(), line.partyId(), line.taxType(), line.bankAccountNumber(), line.gstin());
        }

        return voucherId;
    }

    // -------------------------------------------------------------
    // Stock Ledger Helper
    // -------------------------------------------------------------
    private void recordStockMovement(long itemId, Long locationId, String txnType, BigDecimal deltaQty, BigDecimal rate, String refType, Long refId) {
        if (locationId == null) return;
        List<BigDecimal> balList = jdbcTemplate.query("""
            SELECT balance_qty FROM stock_ledger
            WHERE item_id = ? AND location_id = ?
            ORDER BY id DESC LIMIT 1
            FOR UPDATE
        """, (rs, rowNum) -> rs.getBigDecimal("balance_qty"), itemId, locationId);

        BigDecimal currentBal = balList.isEmpty() || balList.get(0) == null ? BigDecimal.ZERO : balList.get(0);
        BigDecimal newBal = currentBal.add(deltaQty).setScale(4, RoundingMode.HALF_UP);

        jdbcTemplate.update("""
            INSERT INTO stock_ledger (item_id, location_id, txn_type, qty, rate, ref_type, ref_id, balance_qty)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, itemId, locationId, txnType, deltaQty, rate, refType, refId, newBal);
    }

    private void repairStockLedgerBalances(long itemId, long locationId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT id, qty FROM stock_ledger
            WHERE item_id = ? AND location_id = ?
            ORDER BY id ASC
            FOR UPDATE
        """, itemId, locationId);

        BigDecimal running = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            long rowId = ((Number) r.get("id")).longValue();
            BigDecimal qty = (BigDecimal) r.get("qty");
            running = running.add(qty).setScale(4, RoundingMode.HALF_UP);
            jdbcTemplate.update("UPDATE stock_ledger SET balance_qty = ? WHERE id = ?", running, rowId);
        }
    }

    // -------------------------------------------------------------
    // 1. Sales Invoice Posting
    // Rule: Dr Accounts Receivable (1200) = total; Cr Sales Revenue (4000) = taxable; Cr Output CGST (2200) / SGST (2201) / IGST (2202)
    // Stock out + FIFO consume for goods lines
    // -------------------------------------------------------------
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Long postSalesInvoice(long invoiceId,
                                 String invoiceNo,
                                 LocalDate invoiceDate,
                                 long customerPartyId,
                                 Long locationId,
                                 BigDecimal taxableValue,
                                 BigDecimal cgst,
                                 BigDecimal sgst,
                                 BigDecimal igst,
                                 BigDecimal totalAmount,
                                 List<LineItem> lines,
                                 long userId) {
        Long debtors = getAccountIdByCode("1200");
        if (debtors == null) debtors = getAccountIdByCode("1000");
        Long salesAcc = getAccountIdByCode("4000");
        Long outCgst = getAccountIdByCode("2200");
        Long outSgst = getAccountIdByCode("2201");
        Long outIgst = getAccountIdByCode("2202");

        if (debtors == null || salesAcc == null) {
            throw new IllegalStateException("Required sales accounts (1200/4000) not found in Chart of Accounts.");
        }

        List<VoucherLineEntry> entries = new ArrayList<>();
        entries.add(new VoucherLineEntry(debtors, totalAmount, BigDecimal.ZERO, "Sales Invoice " + invoiceNo, customerPartyId, null, null, null));
        entries.add(new VoucherLineEntry(salesAcc, BigDecimal.ZERO, taxableValue));

        if (cgst != null && cgst.compareTo(BigDecimal.ZERO) > 0 && outCgst != null) {
            entries.add(new VoucherLineEntry(outCgst, BigDecimal.ZERO, cgst));
        }
        if (sgst != null && sgst.compareTo(BigDecimal.ZERO) > 0 && outSgst != null) {
            entries.add(new VoucherLineEntry(outSgst, BigDecimal.ZERO, sgst));
        }
        if (igst != null && igst.compareTo(BigDecimal.ZERO) > 0 && outIgst != null) {
            entries.add(new VoucherLineEntry(outIgst, BigDecimal.ZERO, igst));
        }

        String voucherNo = "SI-JV-" + invoiceNo;
        String narration = "Auto-posted from invoice " + invoiceNo;
        Long voucherId = createVoucher(voucherNo, invoiceDate, "invoice", narration, "posted", "invoice", invoiceNo, userId, userId, entries);

        jdbcTemplate.update("UPDATE invoices SET voucher_id = ?, status = 'posted' WHERE id = ?", voucherId, invoiceId);

        if (lines != null) {
            for (LineItem l : lines) {
                if (l.isGoods() && locationId != null) {
                    recordStockMovement(l.itemId(), locationId, "sale", l.qty().negate(), l.rate(), "invoice", invoiceId);
                    fifoValuationService.consumeFifo(l.itemId(), locationId, l.qty(), "sale", invoiceId, invoiceDate, l.rate(), customerPartyId);
                }
            }
        }

        return voucherId;
    }

    // -------------------------------------------------------------
    // 2. Purchase Invoice Posting
    // Rule: Dr Purchases (5000) = taxable; Dr Input CGST (1400) / SGST (1401) / IGST (1402); Cr Accounts Payable (2100) = total
    // Stock in + new FIFO lot for goods
    // -------------------------------------------------------------
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Long postPurchaseInvoice(long invoiceId,
                                    String invoiceNo,
                                    LocalDate invoiceDate,
                                    long vendorPartyId,
                                    Long locationId,
                                    BigDecimal taxableValue,
                                    BigDecimal cgst,
                                    BigDecimal sgst,
                                    BigDecimal igst,
                                    BigDecimal totalAmount,
                                    List<LineItem> lines,
                                    long userId) {
        Long creditors = getAccountIdByCode("2100");
        if (creditors == null) creditors = getAccountIdByCode("2000");
        Long purchaseAcc = getAccountIdByCode("5000");
        Long inCgst = getAccountIdByCode("1400");
        Long inSgst = getAccountIdByCode("1401");
        Long inIgst = getAccountIdByCode("1402");

        if (creditors == null || purchaseAcc == null) {
            throw new IllegalStateException("Required purchase accounts (2100/5000) not found in Chart of Accounts.");
        }

        List<VoucherLineEntry> entries = new ArrayList<>();
        entries.add(new VoucherLineEntry(purchaseAcc, taxableValue, BigDecimal.ZERO));

        if (cgst != null && cgst.compareTo(BigDecimal.ZERO) > 0 && inCgst != null) {
            entries.add(new VoucherLineEntry(inCgst, cgst, BigDecimal.ZERO));
        }
        if (sgst != null && sgst.compareTo(BigDecimal.ZERO) > 0 && inSgst != null) {
            entries.add(new VoucherLineEntry(inSgst, sgst, BigDecimal.ZERO));
        }
        if (igst != null && igst.compareTo(BigDecimal.ZERO) > 0 && inIgst != null) {
            entries.add(new VoucherLineEntry(inIgst, igst, BigDecimal.ZERO));
        }

        entries.add(new VoucherLineEntry(creditors, BigDecimal.ZERO, totalAmount, "Purchase Invoice " + invoiceNo, vendorPartyId, null, null, null));

        String voucherNo = "PI-JV-" + invoiceNo;
        String narration = "Auto-posted from invoice " + invoiceNo;
        Long voucherId = createVoucher(voucherNo, invoiceDate, "invoice", narration, "posted", "invoice", invoiceNo, userId, userId, entries);

        jdbcTemplate.update("UPDATE invoices SET voucher_id = ?, status = 'posted' WHERE id = ?", voucherId, invoiceId);

        if (lines != null) {
            for (LineItem l : lines) {
                if (l.isGoods() && locationId != null) {
                    recordStockMovement(l.itemId(), locationId, "purchase", l.qty(), l.rate(), "invoice", invoiceId);
                    fifoValuationService.createLot(l.itemId(), locationId, "purchase", invoiceId, vendorPartyId, invoiceDate, l.qty(), l.rate());
                }
            }
        }

        return voucherId;
    }

    // -------------------------------------------------------------
    // 3. Credit Note Posting
    // Rule: Dr Sales Revenue (4000) taxable; Dr Output GST ledgers; Cr Accounts Receivable (1200) total
    // Stock returned for goods lines (sales_return lot created)
    // -------------------------------------------------------------
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Long postCreditNote(long cnId,
                               String cnNo,
                               LocalDate cnDate,
                               long partyId,
                               Long locationId,
                               BigDecimal taxableValue,
                               BigDecimal cgst,
                               BigDecimal sgst,
                               BigDecimal igst,
                               BigDecimal totalAmount,
                               String reason,
                               String invoiceNo,
                               List<LineItem> lines,
                               long userId) {
        Long debtors = getAccountIdByCode("1200");
        if (debtors == null) debtors = getAccountIdByCode("1000");
        Long salesAcc = getAccountIdByCode("4000");
        Long outCgst = getAccountIdByCode("2200");
        Long outSgst = getAccountIdByCode("2201");
        Long outIgst = getAccountIdByCode("2202");

        List<VoucherLineEntry> entries = new ArrayList<>();
        entries.add(new VoucherLineEntry(salesAcc, taxableValue, BigDecimal.ZERO));

        if (cgst != null && cgst.compareTo(BigDecimal.ZERO) > 0 && outCgst != null) {
            entries.add(new VoucherLineEntry(outCgst, cgst, BigDecimal.ZERO));
        }
        if (sgst != null && sgst.compareTo(BigDecimal.ZERO) > 0 && outSgst != null) {
            entries.add(new VoucherLineEntry(outSgst, sgst, BigDecimal.ZERO));
        }
        if (igst != null && igst.compareTo(BigDecimal.ZERO) > 0 && outIgst != null) {
            entries.add(new VoucherLineEntry(outIgst, igst, BigDecimal.ZERO));
        }

        entries.add(new VoucherLineEntry(debtors, BigDecimal.ZERO, totalAmount, "Credit Note " + cnNo, partyId, null, null, null));

        String voucherNo = "CN-JV-" + cnNo;
        String narration = "Credit note " + cnNo + (invoiceNo != null ? " against invoice " + invoiceNo : "") + (reason != null ? " — " + reason : "");
        Long voucherId = createVoucher(voucherNo, cnDate, "credit_note", narration, "posted", "credit_note", cnNo, userId, userId, entries);

        jdbcTemplate.update("UPDATE credit_notes SET voucher_id = ? WHERE id = ?", voucherId, cnId);

        if (lines != null && locationId != null) {
            for (LineItem l : lines) {
                if (l.isGoods()) {
                    recordStockMovement(l.itemId(), locationId, "adjustment", l.qty(), l.rate(), "credit_note", cnId);
                    fifoValuationService.createLot(l.itemId(), locationId, "sales_return", cnId, partyId, cnDate, l.qty(), l.rate());
                }
            }
        }

        return voucherId;
    }

    // -------------------------------------------------------------
    // 4. Debit Note Posting
    // Rule: Dr Accounts Payable (2100) total; Cr Purchases (5000) taxable; Cr Input GST ledgers
    // Stock out for goods lines (purchase_return FIFO consume)
    // -------------------------------------------------------------
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Long postDebitNote(long dnId,
                              String dnNo,
                              LocalDate dnDate,
                              long partyId,
                              Long locationId,
                              BigDecimal taxableValue,
                              BigDecimal cgst,
                              BigDecimal sgst,
                              BigDecimal igst,
                              BigDecimal totalAmount,
                              String reason,
                              String invoiceNo,
                              List<LineItem> lines,
                              long userId) {
        Long creditors = getAccountIdByCode("2100");
        if (creditors == null) creditors = getAccountIdByCode("2000");
        Long purchaseAcc = getAccountIdByCode("5000");
        Long inCgst = getAccountIdByCode("1400");
        Long inSgst = getAccountIdByCode("1401");
        Long inIgst = getAccountIdByCode("1402");

        List<VoucherLineEntry> entries = new ArrayList<>();
        entries.add(new VoucherLineEntry(creditors, totalAmount, BigDecimal.ZERO, "Debit Note " + dnNo, partyId, null, null, null));
        entries.add(new VoucherLineEntry(purchaseAcc, BigDecimal.ZERO, taxableValue));

        if (cgst != null && cgst.compareTo(BigDecimal.ZERO) > 0 && inCgst != null) {
            entries.add(new VoucherLineEntry(inCgst, BigDecimal.ZERO, cgst));
        }
        if (sgst != null && sgst.compareTo(BigDecimal.ZERO) > 0 && inSgst != null) {
            entries.add(new VoucherLineEntry(inSgst, BigDecimal.ZERO, sgst));
        }
        if (igst != null && igst.compareTo(BigDecimal.ZERO) > 0 && inIgst != null) {
            entries.add(new VoucherLineEntry(inIgst, BigDecimal.ZERO, igst));
        }

        String voucherNo = "DN-JV-" + dnNo;
        String narration = "Debit note " + dnNo + (invoiceNo != null ? " against invoice " + invoiceNo : "") + (reason != null ? " — " + reason : "");
        Long voucherId = createVoucher(voucherNo, dnDate, "debit_note", narration, "posted", "debit_note", dnNo, userId, userId, entries);

        jdbcTemplate.update("UPDATE debit_notes SET voucher_id = ? WHERE id = ?", voucherId, dnId);

        if (lines != null && locationId != null) {
            for (LineItem l : lines) {
                if (l.isGoods()) {
                    recordStockMovement(l.itemId(), locationId, "adjustment", l.qty().negate(), l.rate(), "debit_note", dnId);
                    fifoValuationService.consumeFifo(l.itemId(), locationId, l.qty(), "purchase_return", dnId, dnDate, l.rate(), partyId);
                }
            }
        }

        return voucherId;
    }

    // -------------------------------------------------------------
    // 5. Customer Receipt & Vendor Payment (Settlement)
    // Rule: Customer receipt: Dr Bank / Cash, Cr Accounts Receivable (1200)
    // Rule: Vendor payment: Dr Accounts Payable (2100), Cr Bank / Cash
    // Allocations in payment_allocations, update invoices paid_amount
    // -------------------------------------------------------------
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Long postSettlement(boolean isVendor,
                               long partyId,
                               String partyName,
                               long bankOrCashAccountId,
                               LocalDate txnDate,
                               BigDecimal totalAmount,
                               String narration,
                               List<Allocation> allocations,
                               long userId) {
        Long controlAcc = isVendor ? (getAccountIdByCode("2100") != null ? getAccountIdByCode("2100") : getAccountIdByCode("2000"))
                                   : (getAccountIdByCode("1200") != null ? getAccountIdByCode("1200") : getAccountIdByCode("1000"));

        if (controlAcc == null) {
            throw new IllegalStateException("Control account (1200/2100) not found.");
        }

        List<VoucherLineEntry> entries = new ArrayList<>();
        if (isVendor) {
            entries.add(new VoucherLineEntry(controlAcc, totalAmount, BigDecimal.ZERO, narration, partyId, null, null, null));
            entries.add(new VoucherLineEntry(bankOrCashAccountId, BigDecimal.ZERO, totalAmount, narration, null, null, null, null));
        } else {
            entries.add(new VoucherLineEntry(bankOrCashAccountId, totalAmount, BigDecimal.ZERO, narration, null, null, null, null));
            entries.add(new VoucherLineEntry(controlAcc, BigDecimal.ZERO, totalAmount, narration, partyId, null, null, null));
        }

        String prefix = isVendor ? "PAY" : "RCT";
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM vouchers WHERE source = 'settlement'", Long.class);
        long n = (count == null ? 0 : count) + 1;
        String voucherNo;
        do {
            voucherNo = String.format("%s-%05d", prefix, n++);
        } while (Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT EXISTS(SELECT 1 FROM vouchers WHERE voucher_no = ?)", Boolean.class, voucherNo)));

        Long voucherId = createVoucher(voucherNo, txnDate, isVendor ? "payment" : "receipt", narration, "posted", "settlement", String.valueOf(partyId), userId, userId, entries);

        if (allocations != null) {
            for (Allocation a : allocations) {
                jdbcTemplate.update("""
                    INSERT INTO payment_allocations (voucher_id, invoice_id, party_id, amount)
                    VALUES (?, ?, ?, ?)
                """, voucherId, a.invoiceId(), partyId, a.amount());

                jdbcTemplate.update("""
                    UPDATE invoices SET paid_amount = ROUND(paid_amount + ?, 2) WHERE id = ?
                """, a.amount(), a.invoiceId());
            }
        }

        return voucherId;
    }

    // -------------------------------------------------------------
    // 6. Payroll Salary Run Post
    // Rule: Dr Salary Expense (5100) = total gross; Cr PF Payable (2300) = EPF; Cr ESI Payable (2303) = ESI; Cr TDS Payable (2301); Cr Salary Payable (2302) = net
    // -------------------------------------------------------------
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Long postPayrollRun(long runId,
                               int periodYear,
                               int periodMonth,
                               BigDecimal totalGross,
                               BigDecimal totalPf,
                               BigDecimal totalEsi,
                               BigDecimal totalTds,
                               BigDecimal totalNet,
                               long userId) {
        Long salExp = getAccountIdByCode("5100");
        Long pfPay = getAccountIdByCode("2300");
        Long tdsPay = getAccountIdByCode("2301");
        Long salPay = getAccountIdByCode("2302");
        Long esiPay = totalEsi != null && totalEsi.compareTo(BigDecimal.ZERO) > 0 ? getAccountIdByCode("2303") : null;

        if (salExp == null || pfPay == null || tdsPay == null || salPay == null) {
            throw new IllegalStateException("Payroll ledger accounts (5100/2300/2301/2302) are not set up.");
        }

        List<VoucherLineEntry> entries = new ArrayList<>();
        entries.add(new VoucherLineEntry(salExp, totalGross, BigDecimal.ZERO));
        entries.add(new VoucherLineEntry(pfPay, BigDecimal.ZERO, totalPf));
        if (totalEsi != null && totalEsi.compareTo(BigDecimal.ZERO) > 0 && esiPay != null) {
            entries.add(new VoucherLineEntry(esiPay, BigDecimal.ZERO, totalEsi));
        }
        entries.add(new VoucherLineEntry(tdsPay, BigDecimal.ZERO, totalTds));
        entries.add(new VoucherLineEntry(salPay, BigDecimal.ZERO, totalNet));

        String voucherNo = String.format("PR-%d%02d", periodYear, periodMonth);
        String narration = String.format("Payroll for %02d/%d", periodMonth, periodYear);
        LocalDate voucherDate = LocalDate.now();

        Long voucherId = createVoucher(voucherNo, voucherDate, "payroll", narration, "posted", "payroll", String.valueOf(runId), userId, userId, entries);

        jdbcTemplate.update("UPDATE payroll_runs SET voucher_id = ?, status = 'processed' WHERE id = ?", voucherId, runId);
        return voucherId;
    }

    // -------------------------------------------------------------
    // 7. Payroll Statutory (Employer EPF & ESI)
    // Rule: Dr Employer PF Contribution (5101); Dr EPF Admin & EDLI (5103); Dr Employer ESI Contribution (5102); Cr PF Payable (2300); Cr ESI Payable (2303)
    // -------------------------------------------------------------
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Long postPayrollStatutory(long runId,
                                     int periodYear,
                                     int periodMonth,
                                     LocalDate periodEnd,
                                     BigDecimal erPf,
                                     BigDecimal epfAdminAndEdli,
                                     BigDecimal esiEr,
                                     long userId) {
        Long erPfExp = getAccountIdByCode("5101");
        Long pfAdminExp = getAccountIdByCode("5103");
        Long erEsiExp = getAccountIdByCode("5102");
        Long pfPay = getAccountIdByCode("2300");
        Long esiPay = getAccountIdByCode("2303");

        List<VoucherLineEntry> entries = new ArrayList<>();
        BigDecimal totalPfPayable = BigDecimal.ZERO;

        if (erPf != null && erPf.compareTo(BigDecimal.ZERO) > 0) {
            entries.add(new VoucherLineEntry(erPfExp, erPf, BigDecimal.ZERO));
            totalPfPayable = totalPfPayable.add(erPf);
        }
        if (epfAdminAndEdli != null && epfAdminAndEdli.compareTo(BigDecimal.ZERO) > 0) {
            entries.add(new VoucherLineEntry(pfAdminExp, epfAdminAndEdli, BigDecimal.ZERO));
            totalPfPayable = totalPfPayable.add(epfAdminAndEdli);
        }
        if (totalPfPayable.compareTo(BigDecimal.ZERO) > 0) {
            entries.add(new VoucherLineEntry(pfPay, BigDecimal.ZERO, totalPfPayable));
        }

        if (esiEr != null && esiEr.compareTo(BigDecimal.ZERO) > 0) {
            entries.add(new VoucherLineEntry(erEsiExp, esiEr, BigDecimal.ZERO));
            entries.add(new VoucherLineEntry(esiPay, BigDecimal.ZERO, esiEr));
        }

        String voucherNo = String.format("PRS-%d%02d", periodYear, periodMonth);
        String narration = String.format("Employer EPF / ESI for %02d/%d", periodMonth, periodYear);

        Long voucherId = createVoucher(voucherNo, periodEnd, "journal", narration, "posted", "payroll_statutory", "run:" + runId, userId, userId, entries);

        jdbcTemplate.update("UPDATE payroll_runs SET statutory_voucher_id = ? WHERE id = ?", voucherId, runId);
        return voucherId;
    }

    // -------------------------------------------------------------
    // 8. Fixed Asset Acquisition
    // Rule: Dr Fixed Assets - Gross Block, Cr Funding source ledger (bank / payable)
    // -------------------------------------------------------------
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Long postFixedAssetAcquisition(long assetId,
                                          String assetName,
                                          long grossBlockAccountId,
                                          long fundingSourceAccountId,
                                          BigDecimal cost,
                                          LocalDate acquisitionDate,
                                          long userId) {
        List<VoucherLineEntry> entries = List.of(
            new VoucherLineEntry(grossBlockAccountId, cost, BigDecimal.ZERO),
            new VoucherLineEntry(fundingSourceAccountId, BigDecimal.ZERO, cost)
        );

        Long seq = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM vouchers WHERE voucher_type = 'asset_acquisition'", Long.class);
        long n = (seq == null ? 0 : seq) + 1;
        String voucherNo = String.format("AST-ACQ-%04d", n);
        String narration = "Acquisition of asset: " + assetName;

        Long voucherId = createVoucher(voucherNo, acquisitionDate, "asset_acquisition", narration, "posted", "fixed_asset", String.valueOf(assetId), userId, userId, entries);

        jdbcTemplate.update("UPDATE fixed_assets SET acquisition_voucher_id = ? WHERE id = ?", voucherId, assetId);
        return voucherId;
    }

    // -------------------------------------------------------------
    // 9. Depreciation Run (Monthly)
    // Rule: Dr Depreciation Expense, Cr Accumulated Depreciation
    // -------------------------------------------------------------
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Long postDepreciationRun(int year,
                                    int month,
                                    LocalDate runDate,
                                    long depreciationExpenseAccountId,
                                    long accumulatedDepreciationAccountId,
                                    BigDecimal totalDepreciation,
                                    long userId) {
        List<VoucherLineEntry> entries = List.of(
            new VoucherLineEntry(depreciationExpenseAccountId, totalDepreciation, BigDecimal.ZERO),
            new VoucherLineEntry(accumulatedDepreciationAccountId, BigDecimal.ZERO, totalDepreciation)
        );

        String voucherNo = String.format("DEP-%d%02d", year, month);
        String narration = String.format("Depreciation run for %02d/%d", month, year);

        return createVoucher(voucherNo, runDate, "depreciation", narration, "posted", "depreciation", String.format("%d-%02d", year, month), userId, userId, entries);
    }

    // -------------------------------------------------------------
    // 10. Inter-State Stock Transfer
    // Rule: Dr Stock Transfer In (6001) taxable; Dr Input IGST (1402); Cr Stock Transfer Out (6000) taxable; Cr Output IGST (2202)
    // -------------------------------------------------------------
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Long postInterStateTransfer(long transferId,
                                       String transferNo,
                                       LocalDate transferDate,
                                       String fromLocCode,
                                       String toLocCode,
                                       BigDecimal taxableValue,
                                       BigDecimal igst,
                                       long userId) {
        Long transferOut = getAccountIdByCode("6000");
        Long transferIn = getAccountIdByCode("6001");
        Long outputIgst = getAccountIdByCode("2202");
        Long inputIgst = getAccountIdByCode("1402");

        List<VoucherLineEntry> entries = new ArrayList<>();
        entries.add(new VoucherLineEntry(transferIn, taxableValue, BigDecimal.ZERO));
        if (igst != null && igst.compareTo(BigDecimal.ZERO) > 0 && inputIgst != null) {
            entries.add(new VoucherLineEntry(inputIgst, igst, BigDecimal.ZERO));
        }
        entries.add(new VoucherLineEntry(transferOut, BigDecimal.ZERO, taxableValue));
        if (igst != null && igst.compareTo(BigDecimal.ZERO) > 0 && outputIgst != null) {
            entries.add(new VoucherLineEntry(outputIgst, BigDecimal.ZERO, igst));
        }

        String voucherNo = "STV-" + transferNo;
        String narration = String.format("Inter-state stock transfer %s: %s -> %s", transferNo, fromLocCode, toLocCode);

        Long voucherId = createVoucher(voucherNo, transferDate, "stock_transfer", narration, "posted", "stock_transfer", transferNo, userId, userId, entries);

        jdbcTemplate.update("UPDATE stock_transfers SET voucher_id = ? WHERE id = ?", voucherId, transferId);
        return voucherId;
    }

    // -------------------------------------------------------------
    // 11. ITC-Ineligible Journal
    // Rule: Dr Expense / asset ledger, Cr Input CGST (1400) / SGST (1401) / IGST (1402)
    // -------------------------------------------------------------
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Long postItcIneligibleJournal(long invoiceId,
                                         String invoiceNo,
                                         long expenseOrAssetAccountId,
                                         BigDecimal cgst,
                                         BigDecimal sgst,
                                         BigDecimal igst,
                                         LocalDate invoiceDate,
                                         long userId) {
        Long inCgst = getAccountIdByCode("1400");
        Long inSgst = getAccountIdByCode("1401");
        Long inIgst = getAccountIdByCode("1402");

        BigDecimal totalItc = BigDecimal.ZERO;
        List<VoucherLineEntry> entries = new ArrayList<>();

        if (cgst != null && cgst.compareTo(BigDecimal.ZERO) > 0) {
            entries.add(new VoucherLineEntry(inCgst, BigDecimal.ZERO, cgst));
            totalItc = totalItc.add(cgst);
        }
        if (sgst != null && sgst.compareTo(BigDecimal.ZERO) > 0) {
            entries.add(new VoucherLineEntry(inSgst, BigDecimal.ZERO, sgst));
            totalItc = totalItc.add(sgst);
        }
        if (igst != null && igst.compareTo(BigDecimal.ZERO) > 0) {
            entries.add(new VoucherLineEntry(inIgst, BigDecimal.ZERO, igst));
            totalItc = totalItc.add(igst);
        }

        entries.add(0, new VoucherLineEntry(expenseOrAssetAccountId, totalItc, BigDecimal.ZERO));

        String voucherNo = "ITC-REV-" + invoiceNo;
        String narration = "Ineligible ITC reversed to cost for " + invoiceNo;

        Long voucherId = createVoucher(voucherNo, invoiceDate, "journal", narration, "posted", "itc_reversal", invoiceNo, userId, userId, entries);

        jdbcTemplate.update("UPDATE invoices SET itc_voucher_id = ? WHERE id = ?", voucherId, invoiceId);
        return voucherId;
    }

    // -------------------------------------------------------------
    // 12. RCM Journal (Eligible / Ineligible)
    // Rule: Eligible: Dr Input CGST/SGST/IGST, Cr RCM Payable ledgers
    //       Ineligible: Dr Expense/Asset ledger, Cr RCM Payable ledgers
    // -------------------------------------------------------------
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Long postRcmJournal(long invoiceId,
                               String invoiceNo,
                               boolean isEligible,
                               Long expenseAccountId,
                               BigDecimal cgst,
                               BigDecimal sgst,
                               BigDecimal igst,
                               LocalDate invoiceDate,
                               long userId) {
        List<VoucherLineEntry> entries = new ArrayList<>();
        BigDecimal totalRcm = BigDecimal.ZERO;

        if (cgst != null && cgst.compareTo(BigDecimal.ZERO) > 0) {
            Long rcmCgst = getRcmAccountId("cgst");
            entries.add(new VoucherLineEntry(rcmCgst, BigDecimal.ZERO, cgst));
            totalRcm = totalRcm.add(cgst);
            if (isEligible) {
                entries.add(new VoucherLineEntry(getAccountIdByCode("1400"), cgst, BigDecimal.ZERO));
            }
        }
        if (sgst != null && sgst.compareTo(BigDecimal.ZERO) > 0) {
            Long rcmSgst = getRcmAccountId("sgst");
            entries.add(new VoucherLineEntry(rcmSgst, BigDecimal.ZERO, sgst));
            totalRcm = totalRcm.add(sgst);
            if (isEligible) {
                entries.add(new VoucherLineEntry(getAccountIdByCode("1401"), sgst, BigDecimal.ZERO));
            }
        }
        if (igst != null && igst.compareTo(BigDecimal.ZERO) > 0) {
            Long rcmIgst = getRcmAccountId("igst");
            entries.add(new VoucherLineEntry(rcmIgst, BigDecimal.ZERO, igst));
            totalRcm = totalRcm.add(igst);
            if (isEligible) {
                entries.add(new VoucherLineEntry(getAccountIdByCode("1402"), igst, BigDecimal.ZERO));
            }
        }

        if (!isEligible && expenseAccountId != null) {
            entries.add(0, new VoucherLineEntry(expenseAccountId, totalRcm, BigDecimal.ZERO));
        }

        String voucherNo = "RCM-JV-" + invoiceNo;
        String narration = "RCM liability recognized for " + invoiceNo;

        Long voucherId = createVoucher(voucherNo, invoiceDate, "journal", narration, "posted", "rcm_journal", invoiceNo, userId, userId, entries);

        jdbcTemplate.update("UPDATE invoices SET rcm_voucher_id = ? WHERE id = ?", voucherId, invoiceId);
        return voucherId;
    }

    // -------------------------------------------------------------
    // 13. RCM / Customs Payment
    // Rule: Dr RCM Payable ledgers, Cr Bank
    // -------------------------------------------------------------
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Long postRcmPayment(LocalDate txnDate,
                               long bankAccountId,
                               BigDecimal cgst,
                               BigDecimal sgst,
                               BigDecimal igst,
                               String reference,
                               long userId) {
        List<VoucherLineEntry> entries = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        if (cgst != null && cgst.compareTo(BigDecimal.ZERO) > 0) {
            entries.add(new VoucherLineEntry(getRcmAccountId("cgst"), cgst, BigDecimal.ZERO));
            total = total.add(cgst);
        }
        if (sgst != null && sgst.compareTo(BigDecimal.ZERO) > 0) {
            entries.add(new VoucherLineEntry(getRcmAccountId("sgst"), sgst, BigDecimal.ZERO));
            total = total.add(sgst);
        }
        if (igst != null && igst.compareTo(BigDecimal.ZERO) > 0) {
            entries.add(new VoucherLineEntry(getRcmAccountId("igst"), igst, BigDecimal.ZERO));
            total = total.add(igst);
        }

        entries.add(new VoucherLineEntry(bankAccountId, BigDecimal.ZERO, total));

        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM vouchers WHERE source = 'rcm_payment'", Long.class);
        long n = (count == null ? 0 : count) + 1;
        String voucherNo;
        do {
            voucherNo = String.format("RCMPAY-%05d", n++);
        } while (Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT EXISTS(SELECT 1 FROM vouchers WHERE voucher_no = ?)", Boolean.class, voucherNo)));

        String narration = "RCM tax payment to government" + (reference != null ? " · " + reference : "");

        return createVoucher(voucherNo, txnDate, "payment", narration, "posted", "rcm_payment", voucherNo, userId, userId, entries);
    }

    // -------------------------------------------------------------
    // 14. Manual Voucher (Maker-Checker)
    // Rule: pending_approval -> posted by another user; must balance
    // -------------------------------------------------------------
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Long createManualVoucher(String voucherNo,
                                    LocalDate voucherDate,
                                    String voucherType,
                                    String narration,
                                    List<VoucherLineEntry> lines,
                                    long createdByUserId) {
        return createVoucher(voucherNo, voucherDate, voucherType, narration, "pending_approval", "manual", null, createdByUserId, null, lines);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void approveVoucher(long voucherId, long approverUserId) {
        Map<String, Object> v = jdbcTemplate.queryForMap("SELECT created_by, status FROM vouchers WHERE id = ? FOR UPDATE", voucherId);
        Long createdBy = v.get("created_by") == null ? null : ((Number) v.get("created_by")).longValue();
        String status = (String) v.get("status");

        if (!"pending_approval".equals(status)) {
            throw new IllegalStateException("Only vouchers with pending_approval status can be approved.");
        }
        if (createdBy != null && createdBy.equals(approverUserId)) {
            throw new IllegalArgumentException("Segregation of duties: You cannot approve a voucher you created yourself.");
        }

        jdbcTemplate.update("""
            UPDATE vouchers SET status = 'posted', approved_by = ?, approved_at = CURRENT_TIMESTAMP(3) WHERE id = ?
        """, approverUserId, voucherId);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void rejectVoucher(long voucherId, long approverUserId) {
        Map<String, Object> v = jdbcTemplate.queryForMap("SELECT status FROM vouchers WHERE id = ? FOR UPDATE", voucherId);
        String status = (String) v.get("status");

        if (!"pending_approval".equals(status)) {
            throw new IllegalStateException("Only vouchers with pending_approval status can be rejected.");
        }

        jdbcTemplate.update("""
            UPDATE vouchers SET status = 'rejected', approved_by = ?, approved_at = CURRENT_TIMESTAMP(3) WHERE id = ?
        """, approverUserId, voucherId);
    }

    // -------------------------------------------------------------
    // 15. Cascade Delete & Undo Unwinds (deleteCascade.js)
    // -------------------------------------------------------------
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void deleteVoucherRow(Long voucherId) {
        if (voucherId == null) return;
        jdbcTemplate.update("DELETE FROM voucher_lines WHERE voucher_id = ?", voucherId);
        jdbcTemplate.update("DELETE FROM vouchers WHERE id = ?", voucherId);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void deleteSalesInvoiceStock(long invoiceId, Long locationId, List<LineItem> lines) {
        fifoValuationService.unconsumeFifo("sale", invoiceId);
        jdbcTemplate.update("DELETE FROM stock_ledger WHERE ref_type = 'invoice' AND ref_id = ?", invoiceId);

        if (lines != null && locationId != null) {
            for (LineItem l : lines) {
                if (l.isGoods()) {
                    repairStockLedgerBalances(l.itemId(), locationId);
                }
            }
        }
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void deletePurchaseInvoiceStock(long invoiceId, Long locationId, List<LineItem> lines) {
        List<Map<String, Object>> lots = jdbcTemplate.queryForList("""
            SELECT id, item_id, qty_in, qty_remaining FROM stock_lots
            WHERE source_type = 'purchase' AND source_ref_id = ?
            FOR UPDATE
        """, invoiceId);

        for (Map<String, Object> lot : lots) {
            BigDecimal qtyIn = (BigDecimal) lot.get("qty_in");
            BigDecimal qtyRem = (BigDecimal) lot.get("qty_remaining");
            if (qtyRem.compareTo(qtyIn) < 0) {
                throw new IllegalStateException("Cannot delete: units from this purchase have already been consumed onward.");
            }
        }

        jdbcTemplate.update("DELETE FROM stock_lots WHERE source_type = 'purchase' AND source_ref_id = ?", invoiceId);
        jdbcTemplate.update("DELETE FROM stock_ledger WHERE ref_type = 'invoice' AND ref_id = ?", invoiceId);

        if (lines != null && locationId != null) {
            for (LineItem l : lines) {
                if (l.isGoods()) {
                    repairStockLedgerBalances(l.itemId(), locationId);
                }
            }
        }
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void deleteSettlement(long voucherId) {
        List<Map<String, Object>> allocs = jdbcTemplate.queryForList("""
            SELECT invoice_id, amount FROM payment_allocations WHERE voucher_id = ?
        """, voucherId);

        for (Map<String, Object> a : allocs) {
            long invId = ((Number) a.get("invoice_id")).longValue();
            BigDecimal amt = (BigDecimal) a.get("amount");
            jdbcTemplate.update("UPDATE invoices SET paid_amount = GREATEST(0, ROUND(paid_amount - ?, 2)) WHERE id = ?", amt, invId);
        }

        jdbcTemplate.update("DELETE FROM payment_allocations WHERE voucher_id = ?", voucherId);
        deleteVoucherRow(voucherId);
    }

    // -------------------------------------------------------------
    // 16. Invoice Edit (invoiceEdit.js): Undo and Repost under Same Number
    // -------------------------------------------------------------
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Long reinvoice(long oldInvoiceId, Supplier<Long> repostSupplier) {
        Map<String, Object> old = jdbcTemplate.queryForMap("SELECT * FROM invoices WHERE id = ? FOR UPDATE", oldInvoiceId);
        BigDecimal paid = (BigDecimal) old.get("paid_amount");
        if (paid != null && paid.compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalStateException("Payments are recorded against this invoice. Remove them first, then edit.");
        }

        Long voucherId = old.get("voucher_id") == null ? null : ((Number) old.get("voucher_id")).longValue();
        Long itcVoucherId = old.get("itc_voucher_id") == null ? null : ((Number) old.get("itc_voucher_id")).longValue();
        Long rcmVoucherId = old.get("rcm_voucher_id") == null ? null : ((Number) old.get("rcm_voucher_id")).longValue();
        String invType = (String) old.get("invoice_type");
        Long locId = old.get("location_id") == null ? null : ((Number) old.get("location_id")).longValue();

        // 1. Unwind stock
        if ("sales".equals(invType)) {
            fifoValuationService.unconsumeFifo("sale", oldInvoiceId);
        } else {
            jdbcTemplate.update("DELETE FROM stock_lots WHERE source_type = 'purchase' AND source_ref_id = ?", oldInvoiceId);
        }
        jdbcTemplate.update("DELETE FROM stock_ledger WHERE ref_type = 'invoice' AND ref_id = ?", oldInvoiceId);

        // 2. Unwind vouchers
        deleteVoucherRow(voucherId);
        if (itcVoucherId != null) deleteVoucherRow(itcVoucherId);
        if (rcmVoucherId != null) deleteVoucherRow(rcmVoucherId);

        // 3. Remove lines and old invoice record
        jdbcTemplate.update("DELETE FROM invoice_lines WHERE invoice_id = ?", oldInvoiceId);
        jdbcTemplate.update("DELETE FROM invoices WHERE id = ?", oldInvoiceId);

        // 4. Repost new invoice with same invoice number
        return repostSupplier.get();
    }
}

