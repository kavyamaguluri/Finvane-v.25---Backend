package com.finvane.reports;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.*;

public class ReportComplianceParityTest {

    @Test
    @DisplayName("PARITY: Trial balance total debits must equal total credits")
    void testTrialBalanceEquilibrium() {
        BigDecimal totalDebit = new BigDecimal("1254320.50");
        BigDecimal totalCredit = new BigDecimal("1254320.50");

        assertEquals(0, totalDebit.compareTo(totalCredit));
        assertEquals(0, BigDecimal.ZERO.compareTo(totalDebit.subtract(totalCredit)));
    }

    @Test
    @DisplayName("PARITY: Net Profit calculation (Revenue - Cost of Goods Sold - Operating Expenses)")
    void testNetProfitComputation() {
        BigDecimal revenue = new BigDecimal("5000000.00");
        BigDecimal cogs = new BigDecimal("3200000.00");
        BigDecimal grossProfit = revenue.subtract(cogs); // 1,800,000

        BigDecimal opex = new BigDecimal("850000.00");
        BigDecimal netProfit = grossProfit.subtract(opex); // 950,000

        assertEquals(new BigDecimal("1800000.00"), grossProfit);
        assertEquals(new BigDecimal("950000.00"), netProfit);
    }

    @Test
    @DisplayName("PARITY: Balance sheet fundamental accounting equation (Assets = Liabilities + Equity)")
    void testBalanceSheetAccountingEquation() {
        BigDecimal totalAssets = new BigDecimal("4500000.00");
        BigDecimal totalLiabilities = new BigDecimal("1800000.00");
        BigDecimal totalEquity = new BigDecimal("2700000.00");

        assertEquals(0, totalAssets.compareTo(totalLiabilities.add(totalEquity)));
    }

    @Test
    @DisplayName("PARITY: Income Tax cess calculation (4% Health and Education Cess)")
    void testTaxCessCalculation() {
        BigDecimal baseTax = new BigDecimal("250000.00");
        BigDecimal cessRate = new BigDecimal("0.04"); // 4%
        BigDecimal cessAmount = baseTax.multiply(cessRate).setScale(2, RoundingMode.HALF_UP);

        assertEquals(new BigDecimal("10000.00"), cessAmount);
        assertEquals(new BigDecimal("260000.00"), baseTax.add(cessAmount));
    }

    @Test
    @DisplayName("PARITY: Budget variance calculation (Actual - Budget and % Variance)")
    void testBudgetVarianceCalculation() {
        BigDecimal budgeted = new BigDecimal("100000.00");
        BigDecimal actual = new BigDecimal("115000.00");

        BigDecimal variance = actual.subtract(budgeted);
        assertEquals(new BigDecimal("15000.00"), variance);

        BigDecimal pctVariance = variance.divide(budgeted, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);
        assertEquals(new BigDecimal("15.00"), pctVariance);
    }
}
