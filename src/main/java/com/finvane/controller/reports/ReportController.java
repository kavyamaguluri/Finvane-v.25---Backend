package com.finvane.controller.reports;

import com.finvane.dao.accounts.IAccountGroupJpaAccess;
import com.finvane.dao.accounts.IAccountLedgerJpaAccess;
import com.finvane.dao.accounts.IVoucherJpaAccess;
import com.finvane.dao.common.IEntityJpaAccess;
import com.finvane.entity.accounts.AccountGroupEntity;
import com.finvane.entity.accounts.AccountLedgerEntity;
import com.finvane.entity.accounts.VoucherEntity;
import com.finvane.entity.accounts.VoucherItemEntity;
import com.finvane.entity.common.EntityEntity;
import com.finvane.model.common.ApiResponse;
import com.finvane.model.common.TenantContext;
import com.finvane.model.reports.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping({"/api/v1/reports", "/api/v2/reports"})
@RequiredArgsConstructor
public class ReportController {

    private final IAccountLedgerJpaAccess ledgerDao;
    private final IAccountGroupJpaAccess groupDao;
    private final IVoucherJpaAccess voucherDao;
    private final IEntityJpaAccess entityDao;
    private final JdbcTemplate jdbcTemplate;

    private static class LedgerBalanceRecord {
        Long id;
        String code;
        String name;
        String nature;
        String primaryGroupName;
        String secondaryGroupName;
        BigDecimal openingBalance;
        BigDecimal totalDebit;
        BigDecimal totalCredit;
        BigDecimal balance; // totalDebit - totalCredit
    }

    private Map<Long, LedgerBalanceRecord> fetchPostedBalances(String tenantId, String startDate, String endDate) {
        String sql = """
            SELECT 
                al.id, al.code, al.name, ag.nature,
                COALESCE(pg.name, ag.name) AS primary_group_name,
                CASE WHEN ag.parent_id IS NOT NULL THEN ag.name ELSE NULL END AS secondary_group_name,
                COALESCE(al.opening_balance, 0) AS opening_balance,
                COALESCE(SUM(CASE WHEN v.id IS NOT NULL AND vi.entry_type = 'DEBIT' THEN vi.amount ELSE 0 END), 0) AS total_debit,
                COALESCE(SUM(CASE WHEN v.id IS NOT NULL AND vi.entry_type = 'CREDIT' THEN vi.amount ELSE 0 END), 0) AS total_credit
            FROM account_ledgers al
            JOIN account_groups ag ON ag.id = al.group_id
            LEFT JOIN account_groups pg ON pg.id = ag.parent_id
            LEFT JOIN voucher_items vi ON vi.ledger_id = al.id
            LEFT JOIN vouchers v ON v.id = vi.voucher_id AND v.tenant_id = ? AND v.voucher_date BETWEEN ? AND ?
            WHERE al.tenant_id = ? AND al.is_active = 1
            GROUP BY al.id, al.code, al.name, ag.nature, pg.name, ag.parent_id, ag.name, al.opening_balance
            ORDER BY al.code
        """;

        Map<Long, LedgerBalanceRecord> map = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            LedgerBalanceRecord rec = new LedgerBalanceRecord();
            rec.id = rs.getLong("id");
            rec.code = rs.getString("code");
            rec.name = rs.getString("name");
            rec.nature = rs.getString("nature");
            rec.primaryGroupName = rs.getString("primary_group_name");
            rec.secondaryGroupName = rs.getString("secondary_group_name");
            rec.openingBalance = rs.getBigDecimal("opening_balance");
            rec.totalDebit = rs.getBigDecimal("total_debit");
            rec.totalCredit = rs.getBigDecimal("total_credit");
            rec.balance = rec.totalDebit.subtract(rec.totalCredit);
            map.put(rec.id, rec);
        }, tenantId, startDate, endDate, tenantId);

        return map;
    }

    @GetMapping("/trial-balance")
    public ApiResponse<TrialBalanceDTO> getTrialBalance(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String granularity) {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) tenantId = "1";
        if (granularity == null || granularity.isBlank()) granularity = "custom";

        boolean dateRangeCorrected = false;
        if (to == null || to.isBlank()) to = LocalDate.now().toString();
        if (from == null || from.isBlank()) {
            try {
                LocalDate incDate = jdbcTemplate.queryForObject("SELECT MIN(voucher_date) FROM vouchers WHERE tenant_id = ? AND status = 'posted'", LocalDate.class, tenantId);
                if (incDate != null) from = incDate.toString();
            } catch (Exception ignored) {}
            if (from == null || from.isBlank()) {
                from = to;
            }
        }

        if (from.compareTo(to) > 0) {
            to = from;
            dateRangeCorrected = true;
        }

        // 1. Determine inception date
        String inceptionSql = "SELECT MIN(voucher_date) FROM vouchers WHERE tenant_id = ?";
        String inceptionDate = null;
        try {
            LocalDate incDate = jdbcTemplate.queryForObject(inceptionSql, LocalDate.class, tenantId);
            if (incDate != null) inceptionDate = incDate.toString();
        } catch (Exception ignored) {}
        if (inceptionDate == null) inceptionDate = "1900-01-01";

        // 2. Fetch period balances
        Map<Long, LedgerBalanceRecord> periodBalances = fetchPostedBalances(tenantId, from, to);

        // 3. Fetch prior balances if from > inception
        Map<Long, LedgerBalanceRecord> priorBalances = Collections.emptyMap();
        if (from.compareTo(inceptionDate) > 0) {
            LocalDate dayBefore = LocalDate.parse(from).minusDays(1);
            priorBalances = fetchPostedBalances(tenantId, inceptionDate, dayBefore.toString());
        }

        // 4. Sum manual opening balances on P&L accounts (Income/Expense) to fold into ledger 3900
        BigDecimal plOpeningAdjustment = BigDecimal.ZERO;
        String plSql = """
            SELECT COALESCE(SUM(al.opening_balance), 0)
            FROM account_ledgers al
            JOIN account_groups ag ON ag.id = al.group_id
            WHERE al.tenant_id = ? AND ag.nature IN ('INCOME', 'EXPENSE')
        """;
        try {
            BigDecimal plAdj = jdbcTemplate.queryForObject(plSql, BigDecimal.class, tenantId);
            if (plAdj != null) plOpeningAdjustment = plAdj;
        } catch (Exception ignored) {}

        List<TrialBalanceDTO.TrialBalanceRow> rows = new ArrayList<>();
        BigDecimal totOpDr = BigDecimal.ZERO;
        BigDecimal totOpCr = BigDecimal.ZERO;
        BigDecimal totDr = BigDecimal.ZERO;
        BigDecimal totCr = BigDecimal.ZERO;
        BigDecimal totClDr = BigDecimal.ZERO;
        BigDecimal totClCr = BigDecimal.ZERO;

        for (LedgerBalanceRecord r : periodBalances.values()) {
            boolean isPL = "INCOME".equalsIgnoreCase(r.nature) || "EXPENSE".equalsIgnoreCase(r.nature);
            BigDecimal priorBal = BigDecimal.ZERO;
            if (priorBalances.containsKey(r.id)) {
                priorBal = priorBalances.get(r.id).balance;
            }

            BigDecimal opBal = r.openingBalance != null ? r.openingBalance : BigDecimal.ZERO;
            BigDecimal opening = isPL ? BigDecimal.ZERO : opBal.add(priorBal);

            if ("3900".equals(r.code)) {
                opening = opening.add(plOpeningAdjustment);
            }

            BigDecimal periodBal = r.balance != null ? r.balance : BigDecimal.ZERO;
            BigDecimal closing = opening.add(periodBal);

            BigDecimal openingDr = opening.compareTo(BigDecimal.ZERO) > 0 ? opening : BigDecimal.ZERO;
            BigDecimal openingCr = opening.compareTo(BigDecimal.ZERO) < 0 ? opening.abs() : BigDecimal.ZERO;

            BigDecimal debit = periodBal.compareTo(BigDecimal.ZERO) > 0 ? periodBal : BigDecimal.ZERO;
            BigDecimal credit = periodBal.compareTo(BigDecimal.ZERO) < 0 ? periodBal.abs() : BigDecimal.ZERO;

            BigDecimal closingDr = closing.compareTo(BigDecimal.ZERO) > 0 ? closing : BigDecimal.ZERO;
            BigDecimal closingCr = closing.compareTo(BigDecimal.ZERO) < 0 ? closing.abs() : BigDecimal.ZERO;

            totOpDr = totOpDr.add(openingDr);
            totOpCr = totOpCr.add(openingCr);
            totDr = totDr.add(debit);
            totCr = totCr.add(credit);
            totClDr = totClDr.add(closingDr);
            totClCr = totClCr.add(closingCr);

            rows.add(TrialBalanceDTO.TrialBalanceRow.builder()
                    .id(r.id)
                    .code(r.code)
                    .name(r.name)
                    .primaryGroupName(r.primaryGroupName != null ? r.primaryGroupName : "General")
                    .secondaryGroupName(r.secondaryGroupName)
                    .nature(r.nature)
                    .type(r.nature != null ? r.nature.toLowerCase() : "")
                    .openingDr(openingDr.setScale(2, RoundingMode.HALF_UP))
                    .openingCr(openingCr.setScale(2, RoundingMode.HALF_UP))
                    .debit(debit.setScale(2, RoundingMode.HALF_UP))
                    .credit(credit.setScale(2, RoundingMode.HALF_UP))
                    .closingDr(closingDr.setScale(2, RoundingMode.HALF_UP))
                    .closingCr(closingCr.setScale(2, RoundingMode.HALF_UP))
                    .netBalance(closing.setScale(2, RoundingMode.HALF_UP))
                    .build());
        }

        TrialBalanceDTO.TrialBalanceTotals totals = TrialBalanceDTO.TrialBalanceTotals.builder()
                .openingDr(totOpDr.setScale(2, RoundingMode.HALF_UP))
                .openingCr(totOpCr.setScale(2, RoundingMode.HALF_UP))
                .debit(totDr.setScale(2, RoundingMode.HALF_UP))
                .credit(totCr.setScale(2, RoundingMode.HALF_UP))
                .closingDr(totClDr.setScale(2, RoundingMode.HALF_UP))
                .closingCr(totClCr.setScale(2, RoundingMode.HALF_UP))
                .build();

        // Also build groups for legacy compatibility
        Map<String, List<TrialBalanceDTO.TrialBalanceRow>> rowsByGroup = rows.stream()
                .collect(Collectors.groupingBy(TrialBalanceDTO.TrialBalanceRow::getPrimaryGroupName, LinkedHashMap::new, Collectors.toList()));

        List<TrialBalanceDTO.TrialBalanceGroup> groupDTOs = new ArrayList<>();
        for (Map.Entry<String, List<TrialBalanceDTO.TrialBalanceRow>> e : rowsByGroup.entrySet()) {
            BigDecimal grpD = e.getValue().stream().map(TrialBalanceDTO.TrialBalanceRow::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal grpC = e.getValue().stream().map(TrialBalanceDTO.TrialBalanceRow::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
            groupDTOs.add(TrialBalanceDTO.TrialBalanceGroup.builder()
                    .groupName(e.getKey())
                    .nature(e.getValue().isEmpty() ? "" : e.getValue().get(0).getNature())
                    .totalDebit(grpD)
                    .totalCredit(grpC)
                    .accounts(e.getValue())
                    .build());
        }

        TrialBalanceDTO dto = TrialBalanceDTO.builder()
                .fromDate(from)
                .toDate(to)
                .granularity(granularity)
                .dateRangeCorrected(dateRangeCorrected)
                .totalDebit(totDr.setScale(2, RoundingMode.HALF_UP))
                .totalCredit(totCr.setScale(2, RoundingMode.HALF_UP))
                .isBalanced(totClDr.compareTo(totClCr) == 0)
                .rows(rows)
                .totals(totals)
                .groups(groupDTOs)
                .build();

        return ApiResponse.ok(dto);
    }

    @GetMapping("/profit-loss")
    public ApiResponse<ProfitLossDTO> getProfitLoss(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) tenantId = "1";
        if (to == null || to.isBlank()) to = LocalDate.now().toString();
        if (from == null || from.isBlank()) {
            try {
                LocalDate incDate = jdbcTemplate.queryForObject("SELECT MIN(voucher_date) FROM vouchers WHERE tenant_id = ? AND status = 'posted'", LocalDate.class, tenantId);
                if (incDate != null) from = incDate.toString();
            } catch (Exception ignored) {}
            if (from == null || from.isBlank()) from = to;
        }

        Map<Long, LedgerBalanceRecord> balances = fetchPostedBalances(tenantId, from, to);

        List<ProfitLossDTO.ReportLine> revLines = new ArrayList<>();
        List<ProfitLossDTO.ReportLine> purchaseLines = new ArrayList<>();
        List<ProfitLossDTO.ReportLine> indirectIncomeLines = new ArrayList<>();
        List<ProfitLossDTO.ReportLine> expLines = new ArrayList<>();

        BigDecimal revOps = BigDecimal.ZERO;
        BigDecimal otherInc = BigDecimal.ZERO;
        BigDecimal purchases = BigDecimal.ZERO;
        BigDecimal empExpense = BigDecimal.ZERO;
        BigDecimal finCosts = BigDecimal.ZERO;
        BigDecimal depExpense = BigDecimal.ZERO;
        BigDecimal otherExp = BigDecimal.ZERO;

        for (LedgerBalanceRecord r : balances.values()) {
            String nature = r.nature != null ? r.nature.toUpperCase(Locale.ROOT) : "";
            String lname = r.name != null ? r.name.toLowerCase(Locale.ROOT) : "";
            String pg = r.primaryGroupName != null ? r.primaryGroupName.toLowerCase(Locale.ROOT) : "";
            String code = r.code != null ? r.code : "";

            if ("INCOME".equals(nature)) {
                BigDecimal amt = r.totalCredit.subtract(r.totalDebit).setScale(2, RoundingMode.HALF_UP);
                if (amt.compareTo(BigDecimal.ZERO) == 0) continue;
                ProfitLossDTO.ReportLine line = ProfitLossDTO.ReportLine.builder()
                        .id(r.id).code(code).name(r.name)
                        .primaryGroupName(r.primaryGroupName).secondaryGroupName(r.secondaryGroupName)
                        .amount(amt).build();
                if (code.startsWith("40") || code.startsWith("41") || pg.contains("sales") || pg.contains("direct") || lname.contains("sales")) {
                    revLines.add(line);
                    revOps = revOps.add(amt);
                } else {
                    indirectIncomeLines.add(line);
                    otherInc = otherInc.add(amt);
                }
            } else if ("EXPENSE".equals(nature)) {
                BigDecimal amt = r.totalDebit.subtract(r.totalCredit).setScale(2, RoundingMode.HALF_UP);
                if (amt.compareTo(BigDecimal.ZERO) == 0) continue;
                ProfitLossDTO.ReportLine line = ProfitLossDTO.ReportLine.builder()
                        .id(r.id).code(code).name(r.name)
                        .primaryGroupName(r.primaryGroupName).secondaryGroupName(r.secondaryGroupName)
                        .amount(amt).build();
                if ("5000".equals(code) || pg.contains("purchase") || pg.contains("cost of") || pg.contains("direct") || lname.contains("purchase")) {
                    purchaseLines.add(line);
                    purchases = purchases.add(amt);
                } else {
                    expLines.add(line);
                    if (code.startsWith("51") || lname.contains("salary") || lname.contains("wage") || lname.contains("epf") || lname.contains("esi")) {
                        empExpense = empExpense.add(amt);
                    } else if (lname.contains("interest") || lname.contains("finance") || pg.contains("finance")) {
                        finCosts = finCosts.add(amt);
                    } else if (lname.contains("depreciation") || lname.contains("amortization")) {
                        depExpense = depExpense.add(amt);
                    } else {
                        otherExp = otherExp.add(amt);
                    }
                }
            }
        }

        BigDecimal openingStock = computeStockValueAsOf(tenantId, LocalDate.parse(from).minusDays(1).toString());
        BigDecimal closingStock = computeStockValueAsOf(tenantId, to);
        BigDecimal changesInInv = openingStock.subtract(closingStock).setScale(2, RoundingMode.HALF_UP);

        BigDecimal totalRev = revOps.add(otherInc).setScale(2, RoundingMode.HALF_UP);
        BigDecimal grossProfit = revOps.add(closingStock).subtract(openingStock).subtract(purchases).setScale(2, RoundingMode.HALF_UP);
        BigDecimal indirectTotal = empExpense.add(finCosts).add(depExpense).add(otherExp).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalExp = purchases.add(changesInInv).add(indirectTotal).setScale(2, RoundingMode.HALF_UP);
        BigDecimal netProfit = totalRev.subtract(totalExp).setScale(2, RoundingMode.HALF_UP);

        ProfitLossDTO dto = ProfitLossDTO.builder()
                .fromDate(from)
                .toDate(to)
                .revenueFromOperations(revOps.setScale(2, RoundingMode.HALF_UP))
                .otherIncome(otherInc.setScale(2, RoundingMode.HALF_UP))
                .totalRevenue(totalRev)
                .openingStock(openingStock)
                .closingStock(closingStock)
                .grossProfit(grossProfit)
                .costOfMaterials(BigDecimal.ZERO)
                .purchaseOfStock(purchases.setScale(2, RoundingMode.HALF_UP))
                .changesInInventory(changesInInv)
                .employeeBenefitExpense(empExpense.setScale(2, RoundingMode.HALF_UP))
                .financeCosts(finCosts.setScale(2, RoundingMode.HALF_UP))
                .depreciationExpense(depExpense.setScale(2, RoundingMode.HALF_UP))
                .otherExpenses(otherExp.setScale(2, RoundingMode.HALF_UP))
                .totalExpenses(totalExp)
                .totalExpense(totalExp)
                .profitBeforeTax(netProfit)
                .taxExpense(BigDecimal.ZERO)
                .netProfit(netProfit)
                .revenueLines(revLines)
                .purchaseLines(purchaseLines)
                .indirectIncomeLines(indirectIncomeLines)
                .expenseLines(expLines)
                .build();

        return ApiResponse.ok(dto);
    }

    @GetMapping("/balance-sheet")
    public ApiResponse<BalanceSheetDTO> getBalanceSheet(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String granularity) {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank() || "control".equalsIgnoreCase(tenantId)) {
            tenantId = "1";
        }
        if (to == null || to.isBlank()) to = LocalDate.now().toString();

        Map<Long, LedgerBalanceRecord> balances = fetchPostedBalances(tenantId, "1900-01-01", to);

        Map<String, Map<String, List<BalanceSheetDTO.BalanceSheetLine>>> liabTree = new LinkedHashMap<>();
        Map<String, Map<String, List<BalanceSheetDTO.BalanceSheetLine>>> assetTree = new LinkedHashMap<>();

        BigDecimal totalAssets = BigDecimal.ZERO;
        BigDecimal totalLiab = BigDecimal.ZERO;
        BigDecimal totalIncome = BigDecimal.ZERO;
        BigDecimal totalExpense = BigDecimal.ZERO;

        for (LedgerBalanceRecord r : balances.values()) {
            String nature = r.nature != null ? r.nature.toLowerCase(Locale.ROOT) : "";
            String pGroup = r.primaryGroupName != null ? r.primaryGroupName : "Other";
            String sGroup = r.secondaryGroupName != null ? r.secondaryGroupName : "";
            BigDecimal op = r.openingBalance != null ? r.openingBalance : BigDecimal.ZERO;

            if ("asset".equals(nature)) {
                BigDecimal bal = op.add(r.totalDebit).subtract(r.totalCredit).setScale(2, RoundingMode.HALF_UP);
                if (bal.compareTo(BigDecimal.ZERO) == 0) continue;
                BalanceSheetDTO.BalanceSheetLine line = BalanceSheetDTO.BalanceSheetLine.builder()
                        .id(r.id).code(r.code).name(r.name).amount(bal).build();
                assetTree.computeIfAbsent(pGroup, k -> new LinkedHashMap<>())
                         .computeIfAbsent(sGroup, k -> new ArrayList<>())
                         .add(line);
                totalAssets = totalAssets.add(bal);
            } else if ("liability".equals(nature) || "equity".equals(nature)) {
                BigDecimal bal = op.add(r.totalCredit).subtract(r.totalDebit).setScale(2, RoundingMode.HALF_UP);
                if (bal.compareTo(BigDecimal.ZERO) == 0) continue;
                BalanceSheetDTO.BalanceSheetLine line = BalanceSheetDTO.BalanceSheetLine.builder()
                        .id(r.id).code(r.code).name(r.name).amount(bal).build();
                liabTree.computeIfAbsent(pGroup, k -> new LinkedHashMap<>())
                        .computeIfAbsent(sGroup, k -> new ArrayList<>())
                        .add(line);
                totalLiab = totalLiab.add(bal);
            } else if ("income".equals(nature)) {
                totalIncome = totalIncome.add(r.totalCredit.subtract(r.totalDebit));
            } else if ("expense".equals(nature)) {
                totalExpense = totalExpense.add(r.totalDebit.subtract(r.totalCredit));
            }
        }

        BigDecimal closingStock = computeStockValueAsOf(tenantId, to);
        if (closingStock.compareTo(BigDecimal.ZERO) > 0) {
            assetTree.computeIfAbsent("Current Assets", k -> new LinkedHashMap<>())
                     .computeIfAbsent("Closing Inventory", k -> new ArrayList<>())
                     .add(BalanceSheetDTO.BalanceSheetLine.builder()
                             .id(null).code("STK").name("Closing Stock (Inventory Valuation)").amount(closingStock).build());
            totalAssets = totalAssets.add(closingStock);
        }

        BigDecimal netProfit = totalIncome.subtract(totalExpense).add(closingStock).setScale(2, RoundingMode.HALF_UP);
        BigDecimal finalTotalLiabilities = totalLiab.add(netProfit).setScale(2, RoundingMode.HALF_UP);

        List<BalanceSheetDTO.BalanceSheetGroup> liabGroups = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<BalanceSheetDTO.BalanceSheetLine>>> pEntry : liabTree.entrySet()) {
            List<BalanceSheetDTO.BalanceSheetSubgroup> subgroups = new ArrayList<>();
            BigDecimal groupTotal = BigDecimal.ZERO;
            for (Map.Entry<String, List<BalanceSheetDTO.BalanceSheetLine>> sEntry : pEntry.getValue().entrySet()) {
                BigDecimal subTotal = sEntry.getValue().stream().map(BalanceSheetDTO.BalanceSheetLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                subgroups.add(BalanceSheetDTO.BalanceSheetSubgroup.builder()
                        .name(sEntry.getKey()).total(subTotal).ledgers(sEntry.getValue()).build());
                groupTotal = groupTotal.add(subTotal);
            }
            liabGroups.add(BalanceSheetDTO.BalanceSheetGroup.builder()
                    .name(pEntry.getKey()).total(groupTotal).subgroups(subgroups).build());
        }

        List<BalanceSheetDTO.BalanceSheetGroup> assetGroups = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<BalanceSheetDTO.BalanceSheetLine>>> pEntry : assetTree.entrySet()) {
            List<BalanceSheetDTO.BalanceSheetSubgroup> subgroups = new ArrayList<>();
            BigDecimal groupTotal = BigDecimal.ZERO;
            for (Map.Entry<String, List<BalanceSheetDTO.BalanceSheetLine>> sEntry : pEntry.getValue().entrySet()) {
                BigDecimal subTotal = sEntry.getValue().stream().map(BalanceSheetDTO.BalanceSheetLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                subgroups.add(BalanceSheetDTO.BalanceSheetSubgroup.builder()
                        .name(sEntry.getKey()).total(subTotal).ledgers(sEntry.getValue()).build());
                groupTotal = groupTotal.add(subTotal);
            }
            assetGroups.add(BalanceSheetDTO.BalanceSheetGroup.builder()
                    .name(pEntry.getKey()).total(groupTotal).subgroups(subgroups).build());
        }

        BalanceSheetDTO dto = BalanceSheetDTO.builder()
                .asOfDate(to)
                .totalAssets(totalAssets.setScale(2, RoundingMode.HALF_UP))
                .totalLiabilitiesAndEquity(finalTotalLiabilities)
                .currentPeriodProfit(netProfit)
                .isBalanced(totalAssets.setScale(2, RoundingMode.HALF_UP).compareTo(finalTotalLiabilities) == 0)
                .liabilityGroups(liabGroups)
                .assetGroups(assetGroups)
                .build();

        return ApiResponse.ok(dto);
    }

    @GetMapping("/cash-flow")
    public ApiResponse<CashFlowDTO> getCashFlow(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) tenantId = "1";
        if (to == null || to.isBlank()) to = LocalDate.now().toString();
        if (from == null || from.isBlank()) {
            try {
                LocalDate incDate = jdbcTemplate.queryForObject("SELECT MIN(voucher_date) FROM vouchers WHERE tenant_id = ? AND status = 'posted'", LocalDate.class, tenantId);
                if (incDate != null) from = incDate.toString();
            } catch (Exception ignored) {}
            if (from == null || from.isBlank()) from = to;
        }

        String dayBeforeFrom = LocalDate.parse(from).minusDays(1).toString();
        Map<Long, LedgerBalanceRecord> priorBalances = fetchPostedBalances(tenantId, "1900-01-01", dayBeforeFrom);
        Map<Long, LedgerBalanceRecord> periodBalances = fetchPostedBalances(tenantId, from, to);

        BigDecimal openingCash = BigDecimal.ZERO;
        BigDecimal netCashMove = BigDecimal.ZERO;
        BigDecimal netProfit = BigDecimal.ZERO;
        Set<Long> cashLedgerIds = new HashSet<>();

        List<CashFlowDTO.CashFlowItem> operatingItems = new ArrayList<>();
        List<CashFlowDTO.CashFlowItem> investingItems = new ArrayList<>();
        List<CashFlowDTO.CashFlowItem> financingItems = new ArrayList<>();

        BigDecimal wcTotal = BigDecimal.ZERO;
        BigDecimal invTotal = BigDecimal.ZERO;
        BigDecimal finTotal = BigDecimal.ZERO;

        for (LedgerBalanceRecord r : periodBalances.values()) {
            String nature = r.nature != null ? r.nature.toUpperCase(Locale.ROOT) : "";
            String lname = r.name != null ? r.name.toLowerCase(Locale.ROOT) : "";
            String pg = r.primaryGroupName != null ? r.primaryGroupName.toLowerCase(Locale.ROOT) : "";
            String code = r.code != null ? r.code : "";
            boolean isCashOrBank = "ASSET".equals(nature) && (lname.contains("cash") || lname.contains("bank") || code.startsWith("10") || code.startsWith("11"));

            if (isCashOrBank) {
                cashLedgerIds.add(r.id);
                LedgerBalanceRecord prior = priorBalances.get(r.id);
                BigDecimal priorNet = prior != null
                        ? prior.openingBalance.add(prior.totalDebit).subtract(prior.totalCredit)
                        : (r.openingBalance != null ? r.openingBalance : BigDecimal.ZERO);
                openingCash = openingCash.add(priorNet);
                netCashMove = netCashMove.add(r.totalDebit.subtract(r.totalCredit));
                continue;
            }

            if ("INCOME".equals(nature)) {
                netProfit = netProfit.add(r.totalCredit.subtract(r.totalDebit));
                continue;
            }
            if ("EXPENSE".equals(nature)) {
                netProfit = netProfit.subtract(r.totalDebit.subtract(r.totalCredit));
                continue;
            }

            BigDecimal cashEffect = "ASSET".equals(nature)
                    ? r.totalCredit.subtract(r.totalDebit).setScale(2, RoundingMode.HALF_UP)
                    : r.totalCredit.subtract(r.totalDebit).setScale(2, RoundingMode.HALF_UP);
            if (cashEffect.compareTo(BigDecimal.ZERO) == 0) continue;

            CashFlowDTO.CashFlowItem item = CashFlowDTO.CashFlowItem.builder()
                    .id(r.id).code(code).description(r.name).amount(cashEffect).build();

            if ("ASSET".equals(nature) && (pg.contains("fixed") || pg.contains("non-current") || lname.contains("asset") || lname.contains("depreciation") || lname.contains("investment"))) {
                investingItems.add(item);
                invTotal = invTotal.add(cashEffect);
            } else if ("EQUITY".equals(nature) || pg.contains("capital") || pg.contains("equity") || pg.contains("loan") || pg.contains("borrowing") || lname.contains("capital") || lname.contains("loan")) {
                financingItems.add(item);
                finTotal = finTotal.add(cashEffect);
            } else {
                operatingItems.add(item);
                wcTotal = wcTotal.add(cashEffect);
            }
        }

        BigDecimal operatingCash = netProfit.add(wcTotal).setScale(2, RoundingMode.HALF_UP);
        BigDecimal investingCash = invTotal.setScale(2, RoundingMode.HALF_UP);
        BigDecimal financingCash = finTotal.setScale(2, RoundingMode.HALF_UP);
        BigDecimal netCashFlow = operatingCash.add(investingCash).add(financingCash).setScale(2, RoundingMode.HALF_UP);
        BigDecimal closingCash = openingCash.add(netCashMove).setScale(2, RoundingMode.HALF_UP);

        // Direct method entries from posted cash/bank voucher items
        List<CashFlowDTO.DirectCashEntry> directOperating = new ArrayList<>();
        List<CashFlowDTO.DirectCashEntry> directInvesting = new ArrayList<>();
        List<CashFlowDTO.DirectCashEntry> directFinancing = new ArrayList<>();

        if (!cashLedgerIds.isEmpty()) {
            try {
                List<Map<String, Object>> voucherRows = jdbcTemplate.queryForList("""
                    SELECT v.id AS voucher_id, COALESCE(v.voucher_no, v.voucher_number) AS voucher_no,
                           v.voucher_date, v.narration, vi.ledger_id, vi.entry_type, vi.amount
                    FROM vouchers v
                    JOIN voucher_items vi ON vi.voucher_id = v.id
                    WHERE v.tenant_id = ? AND v.voucher_date BETWEEN ? AND ?
                    ORDER BY v.voucher_date ASC, v.id ASC
                """, tenantId, from, to);

                Map<Long, List<Map<String, Object>>> byVoucher = new LinkedHashMap<>();
                for (Map<String, Object> vr : voucherRows) {
                    Long vid = ((Number) vr.get("voucher_id")).longValue();
                    byVoucher.computeIfAbsent(vid, k -> new ArrayList<>()).add(vr);
                }

                for (List<Map<String, Object>> lines : byVoucher.values()) {
                    BigDecimal cashDelta = BigDecimal.ZERO;
                    Map<String, Object> first = lines.get(0);
                    Map<String, Object> counterLine = null;
                    for (Map<String, Object> ln : lines) {
                        Long lid = ((Number) ln.get("ledger_id")).longValue();
                        BigDecimal amt = (BigDecimal) ln.get("amount");
                        String et = String.valueOf(ln.get("entry_type"));
                        if (cashLedgerIds.contains(lid)) {
                            cashDelta = cashDelta.add("DEBIT".equalsIgnoreCase(et) ? amt : amt.negate());
                        } else if (counterLine == null) {
                            counterLine = ln;
                        }
                    }
                    if (cashDelta.compareTo(BigDecimal.ZERO) == 0) continue;

                    Long againstId = counterLine != null ? ((Number) counterLine.get("ledger_id")).longValue() : null;
                    LedgerBalanceRecord againstRec = againstId != null ? periodBalances.get(againstId) : null;
                    String againstName = againstRec != null ? againstRec.name : "Cash Movement";
                    String nature = againstRec != null && againstRec.nature != null ? againstRec.nature.toUpperCase(Locale.ROOT) : "";
                    String pg = againstRec != null && againstRec.primaryGroupName != null ? againstRec.primaryGroupName.toLowerCase(Locale.ROOT) : "";

                    CashFlowDTO.DirectCashEntry entry = CashFlowDTO.DirectCashEntry.builder()
                            .voucherId(((Number) first.get("voucher_id")).longValue())
                            .voucherNo(String.valueOf(first.get("voucher_no")))
                            .date(String.valueOf(first.get("voucher_date")))
                            .againstLedgerId(againstId)
                            .against(againstName)
                            .narration(first.get("narration") != null ? String.valueOf(first.get("narration")) : "")
                            .amount(cashDelta.setScale(2, RoundingMode.HALF_UP))
                            .build();

                    if ("ASSET".equals(nature) && (pg.contains("fixed") || pg.contains("non-current") || againstName.toLowerCase(Locale.ROOT).contains("asset"))) {
                        directInvesting.add(entry);
                    } else if ("EQUITY".equals(nature) || pg.contains("capital") || pg.contains("equity") || pg.contains("loan")) {
                        directFinancing.add(entry);
                    } else {
                        directOperating.add(entry);
                    }
                }
            } catch (Exception ignored) {}
        }

        CashFlowDTO dto = CashFlowDTO.builder()
                .fromDate(from)
                .toDate(to)
                .netProfit(netProfit.setScale(2, RoundingMode.HALF_UP))
                .operatingCashFlow(operatingCash)
                .investingCashFlow(investingCash)
                .financingCashFlow(financingCash)
                .netCashFlow(netCashFlow)
                .openingCash(openingCash.setScale(2, RoundingMode.HALF_UP))
                .closingCash(closingCash)
                .operatingItems(operatingItems)
                .investingItems(investingItems)
                .financingItems(financingItems)
                .directOperating(directOperating)
                .directInvesting(directInvesting)
                .directFinancing(directFinancing)
                .build();

        return ApiResponse.ok(dto);
    }

    private BigDecimal computeStockValueAsOf(String tenantId, String asOfDate) {
        try {
            BigDecimal val = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(
                    GREATEST(latest.balance_qty, 0) * COALESCE(
                        NULLIF(CASE WHEN latest.txn_type = 'purchase' THEN latest.rate ELSE NULL END, 0),
                        NULLIF(i.purchase_price, 0),
                        0
                    )
                ), 0)
                FROM items i
                JOIN (
                    SELECT sl.item_id, sl.location_id, sl.balance_qty, sl.rate, sl.txn_type,
                           ROW_NUMBER() OVER (PARTITION BY sl.item_id, sl.location_id ORDER BY sl.id DESC) rn
                    FROM stock_ledger sl
                    LEFT JOIN invoices inv ON sl.ref_type = 'invoice' AND sl.ref_id = inv.id
                    WHERE sl.tenant_id = ? AND DATE(COALESCE(NULLIF(inv.invoice_date, ''), sl.created_at)) <= ?
                ) latest ON latest.item_id = i.id AND latest.rn = 1
                WHERE i.tenant_id = ? AND i.is_active = 1 AND i.item_type = 'goods'
            """, BigDecimal.class, tenantId, asOfDate, tenantId);
            return val != null ? val.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    @GetMapping("/financials")
    public ApiResponse<FinancialsSchedule3DTO> getFinancialsSchedule3(@RequestParam(required = false) String to) {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) tenantId = "1";
        Optional<EntityEntity> ent = entityDao.findById(Long.parseLong(tenantId));
        String name = ent.isPresent() ? ent.get().getName() : "Enterprise";
        String cin = "U72200MH2026PTC123456";
        String pan = ent.isPresent() && ent.get().getPan() != null ? ent.get().getPan() : "AABCF1234F";

        BalanceSheetDTO bs = getBalanceSheet(null, to, "custom").getData();
        ProfitLossDTO pl = getProfitLoss("2026-04-01", to).getData();
        CashFlowDTO cf = getCashFlow("2026-04-01", to).getData();

        List<FinancialsSchedule3DTO.Schedule3Note> notes = List.of(
                FinancialsSchedule3DTO.Schedule3Note.builder()
                        .noteNumber("1")
                        .title("Corporate Information & Significant Accounting Policies")
                        .noteTotal(BigDecimal.ZERO)
                        .lines(List.of(FinancialsSchedule3DTO.NoteLine.builder().particulars("Basis of accounting: Accrual & Historical Cost").currentPeriod(BigDecimal.ZERO).previousPeriod(BigDecimal.ZERO).build()))
                        .build(),
                FinancialsSchedule3DTO.Schedule3Note.builder()
                        .noteNumber("2")
                        .title("Revenue from Operations")
                        .noteTotal(pl.getRevenueFromOperations())
                        .lines(List.of(FinancialsSchedule3DTO.NoteLine.builder().particulars("Sale of goods & services").currentPeriod(pl.getRevenueFromOperations()).previousPeriod(BigDecimal.ZERO).build()))
                        .build()
        );

        FinancialsSchedule3DTO dto = FinancialsSchedule3DTO.builder()
                .entityName(name)
                .cin(cin)
                .pan(pan)
                .asOfDate(to != null ? to : LocalDate.now().toString())
                .balanceSheet(bs)
                .profitLoss(pl)
                .cashFlow(cf)
                .notes(notes)
                .build();

        return ApiResponse.ok(dto);
    }
}
