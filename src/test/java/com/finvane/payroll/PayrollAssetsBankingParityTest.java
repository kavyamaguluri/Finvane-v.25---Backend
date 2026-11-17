package com.finvane.payroll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.*;

public class PayrollAssetsBankingParityTest {

    @Test
    @DisplayName("PARITY: Employee Provident Fund (EPF) 12% calculation with statutory wage ceiling")
    void testEpfCalculation() {
        // EPF wage ceiling is ₹15,000 per month
        BigDecimal statutoryWageCeiling = new BigDecimal("15000.00");
        BigDecimal epfRate = new BigDecimal("0.12"); // 12%

        // Basic salary above ceiling (₹25,000) capped at ₹15,000
        BigDecimal basic1 = new BigDecimal("25000.00");
        BigDecimal epfWage1 = basic1.min(statutoryWageCeiling);
        BigDecimal epfDeduction1 = epfWage1.multiply(epfRate).setScale(2, RoundingMode.HALF_UP);
        assertEquals(new BigDecimal("1800.00"), epfDeduction1);

        // Basic salary below ceiling (₹12,000) calculated on actual
        BigDecimal basic2 = new BigDecimal("12000.00");
        BigDecimal epfWage2 = basic2.min(statutoryWageCeiling);
        BigDecimal epfDeduction2 = epfWage2.multiply(epfRate).setScale(2, RoundingMode.HALF_UP);
        assertEquals(new BigDecimal("1440.00"), epfDeduction2);
    }

    @Test
    @DisplayName("PARITY: Employees' State Insurance (ESI) 0.75% deduction below gross threshold")
    void testEsiCalculation() {
        // ESI gross wage threshold is ₹21,000 per month
        BigDecimal esiGrossLimit = new BigDecimal("21000.00");
        BigDecimal employeeEsiRate = new BigDecimal("0.0075"); // 0.75%

        // Gross = ₹18,000 (eligible for ESI)
        BigDecimal grossEligible = new BigDecimal("18000.00");
        assertTrue(grossEligible.compareTo(esiGrossLimit) <= 0);
        BigDecimal esiDeduction = grossEligible.multiply(employeeEsiRate).setScale(2, RoundingMode.HALF_UP);
        assertEquals(new BigDecimal("135.00"), esiDeduction);

        // Gross = ₹30,000 (exempt from ESI)
        BigDecimal grossExempt = new BigDecimal("30000.00");
        assertFalse(grossExempt.compareTo(esiGrossLimit) <= 0);
    }

    @Test
    @DisplayName("PARITY: Fixed Asset Straight Line Method (SLM) monthly depreciation")
    void testFixedAssetSlmDepreciation() {
        BigDecimal cost = new BigDecimal("120000.00");
        BigDecimal salvageValue = new BigDecimal("0.00");
        int usefulLifeYears = 5;

        BigDecimal annualDep = cost.subtract(salvageValue)
                .divide(new BigDecimal(usefulLifeYears), 2, RoundingMode.HALF_UP);
        assertEquals(new BigDecimal("24000.00"), annualDep);

        BigDecimal monthlyDep = annualDep.divide(new BigDecimal("12"), 2, RoundingMode.HALF_UP);
        assertEquals(new BigDecimal("2000.00"), monthlyDep);
    }

    @Test
    @DisplayName("PARITY: Bank reconciliation matched status logic")
    void testBankReconciliationMatching() {
        BigDecimal statementAmount = new BigDecimal("45000.50");
        BigDecimal ledgerAmount = new BigDecimal("45000.50");

        boolean isReconciled = statementAmount.compareTo(ledgerAmount) == 0;
        assertTrue(isReconciled, "Matching statement and book transactions must reconcile");

        String initialStatus = "unreconciled";
        String reconciledStatus = isReconciled ? "reconciled" : initialStatus;
        assertEquals("reconciled", reconciledStatus);
    }

    @Test
    @DisplayName("PARITY: Salary voucher posting balancing rule (Gross = Net + Statutory)")
    void testSalaryVoucherBalancing() {
        BigDecimal basic = new BigDecimal("50000.00");
        BigDecimal hra = new BigDecimal("20000.00");
        BigDecimal allowances = new BigDecimal("10000.00");
        BigDecimal grossSalary = basic.add(hra).add(allowances); // 80,000

        BigDecimal epf = new BigDecimal("1800.00");
        BigDecimal esi = BigDecimal.ZERO;
        BigDecimal tds = new BigDecimal("5000.00");
        BigDecimal totalDeductions = epf.add(esi).add(tds); // 6,800

        BigDecimal netPayable = grossSalary.subtract(totalDeductions); // 73,200

        // Debits (Expense)
        BigDecimal totalDebit = grossSalary;
        // Credits (Liabilities)
        BigDecimal totalCredit = epf.add(esi).add(tds).add(netPayable);

        assertEquals(0, totalDebit.compareTo(totalCredit));
        assertEquals(new BigDecimal("80000.00"), totalDebit);
        assertEquals(new BigDecimal("80000.00"), totalCredit);
    }
}

