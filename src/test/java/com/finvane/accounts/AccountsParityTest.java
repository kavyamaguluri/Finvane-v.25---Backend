package com.finvane.accounts;

import com.finvane.utils.ScheduleIIIUtility;
import com.finvane.utils.SectorMappingUtility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class AccountsParityTest {

    @Test
    @DisplayName("Schedule III Placement: Income and Expense rules")
    void testScheduleIIIIncomeExpense() {
        // Direct income -> Revenue from operations
        ScheduleIIIUtility.Placement p1 = ScheduleIIIUtility.scheduleIIIPlacement("income", "Sales Accounts", "Domestic Sales", true, null, null, "4000");
        assertEquals("Statement of P&L", p1.getSection());
        assertEquals("Revenue from operations", p1.getLine());
        assertEquals("ok", p1.getStatus());

        // Indirect income -> Other income
        ScheduleIIIUtility.Placement p2 = ScheduleIIIUtility.scheduleIIIPlacement("income", "Indirect Incomes", "Interest Income", false, null, null, "4100");
        assertEquals("Statement of P&L", p2.getSection());
        assertEquals("Other income", p2.getLine());
        assertEquals("ok", p2.getStatus());

        // Direct expense -> Purchases of stock-in-trade / direct costs
        ScheduleIIIUtility.Placement p3 = ScheduleIIIUtility.scheduleIIIPlacement("expense", "Purchase Accounts", "Raw Material Purchases", true, null, null, "5000");
        assertEquals("Statement of P&L", p3.getSection());
        assertEquals("Purchases of stock-in-trade / direct costs", p3.getLine());
        assertEquals("ok", p3.getStatus());

        // Employee expense
        ScheduleIIIUtility.Placement p4 = ScheduleIIIUtility.scheduleIIIPlacement("expense", "Indirect Expenses", "Employee Benefit Expenses", false, null, null, "5100");
        assertEquals("Statement of P&L", p4.getSection());
        assertEquals("Employee benefits expense", p4.getLine());
        assertEquals("ok", p4.getStatus());

        // Depreciation expense
        ScheduleIIIUtility.Placement p5 = ScheduleIIIUtility.scheduleIIIPlacement("expense", "Indirect Expenses", "Depreciation & Amortisation", false, null, null, "5900");
        assertEquals("Statement of P&L", p5.getSection());
        assertEquals("Depreciation and amortization", p5.getLine());
        assertEquals("ok", p5.getStatus());

        // Finance cost
        ScheduleIIIUtility.Placement p6 = ScheduleIIIUtility.scheduleIIIPlacement("expense", "Indirect Expenses", "Finance Costs", false, null, null, "5200");
        assertEquals("Statement of P&L", p6.getSection());
        assertEquals("Finance costs", p6.getLine());
        assertEquals("ok", p6.getStatus());

        // Residual / Other expense -> catchall
        ScheduleIIIUtility.Placement p7 = ScheduleIIIUtility.scheduleIIIPlacement("expense", "Indirect Expenses", "Administrative Expenses", false, null, null, "5300");
        assertEquals("Statement of P&L", p7.getSection());
        assertEquals("Other expenses", p7.getLine());
        assertEquals("catchall", p7.getStatus());
    }

    @Test
    @DisplayName("Schedule III Placement: Balance Sheet Assets and Liabilities")
    void testScheduleIIIBalanceSheet() {
        // Reserves & Surplus
        ScheduleIIIUtility.Placement p1 = ScheduleIIIUtility.scheduleIIIPlacement("equity", "Capital Account", "Reserves & Surplus", false, null, null, "3001");
        assertEquals("Balance Sheet › Equity & Liabilities", p1.getSection());
        assertEquals("Reserves and surplus", p1.getLine());
        assertEquals("ok", p1.getStatus());

        // Share Capital with secondary
        ScheduleIIIUtility.Placement p2 = ScheduleIIIUtility.scheduleIIIPlacement("equity", "Capital Account", "Equity Share Capital", false, null, null, "3000");
        assertEquals("Balance Sheet › Equity & Liabilities", p2.getSection());
        assertEquals("Share capital", p2.getLine());
        assertEquals("ok", p2.getStatus());

        // Trade Payables
        ScheduleIIIUtility.Placement p3 = ScheduleIIIUtility.scheduleIIIPlacement("liability", "Current Liabilities", "Sundry Creditors", false, null, null, "2000");
        assertEquals("Balance Sheet › Equity & Liabilities", p3.getSection());
        assertEquals("Trade payables", p3.getLine());
        assertEquals("ok", p3.getStatus());

        // Bank OD
        ScheduleIIIUtility.Placement p4 = ScheduleIIIUtility.scheduleIIIPlacement("liability", "Loans (Liability)", "Bank OD A/c", false, null, null, "2101");
        assertEquals("Balance Sheet › Equity & Liabilities", p4.getSection());
        assertEquals("Short-term borrowings", p4.getLine());
        assertEquals("ok", p4.getStatus());

        // Fixed Assets
        ScheduleIIIUtility.Placement p5 = ScheduleIIIUtility.scheduleIIIPlacement("asset", "Fixed Assets", "Plant & Machinery", false, null, null, "1001");
        assertEquals("Balance Sheet › Assets", p5.getSection());
        assertEquals("Tangible assets (non-current)", p5.getLine());
        assertEquals("ok", p5.getStatus());

        // Bank Accounts / Cash
        ScheduleIIIUtility.Placement p6 = ScheduleIIIUtility.scheduleIIIPlacement("asset", "Current Assets", "Bank Accounts", false, null, null, "1101");
        assertEquals("Balance Sheet › Assets", p6.getSection());
        assertEquals("Cash and cash equivalents", p6.getLine());
        assertEquals("ok", p6.getStatus());

        // Unclassified
        ScheduleIIIUtility.Placement p7 = ScheduleIIIUtility.scheduleIIIPlacement("asset", "Mysterious Group", null, false, null, null, "9999");
        assertEquals("Balance Sheet", p7.getSection());
        assertEquals("Unclassified (shown separately)", p7.getLine());
        assertEquals("unclassified", p7.getStatus());
    }

    @Test
    @DisplayName("Sector Mapping: Suggestions, Source and Status")
    void testSectorMappingSuggestions() {
        // Sales sub-group
        Map.Entry<String, String> sug1 = SectorMappingUtility.suggestLine("income", "Sales Accounts", "Domestic Sales", true);
        assertEquals("revenue", sug1.getKey());
        assertTrue(sug1.getValue().contains("Domestic Sales"));

        // Raw materials purchase
        Map.Entry<String, String> sug2 = SectorMappingUtility.suggestLine("expense", "Purchase Accounts", "Raw Material Purchases", true);
        assertEquals("materials", sug2.getKey());

        // Direct Labour
        Map.Entry<String, String> sug3 = SectorMappingUtility.suggestLine("expense", "Direct Expenses", "Direct Labour", true);
        assertEquals("direct_labour", sug3.getKey());

        // Status check
        assertEquals("mapped", SectorMappingUtility.statusOf("revenue", null, "income"));
        assertEquals("mapped", SectorMappingUtility.statusOf(null, "materials", "expense"));

        // Source check
        assertEquals("ledger", SectorMappingUtility.sourceOf("revenue", null, "income", "Sales Accounts", "Domestic Sales", true));
        assertEquals("group", SectorMappingUtility.sourceOf(null, "materials", "expense", "Purchase Accounts", "Raw Material Purchases", true));
        assertEquals("suggestion", SectorMappingUtility.sourceOf(null, null, "income", "Sales Accounts", "Domestic Sales", true));
    }

    @Test
    @DisplayName("Sector Mapping: Template normalization and Sector Selection")
    void testSectorMappingTemplateNormalization() {
        // Template picking
        assertEquals("manufacturing", SectorMappingUtility.pickTemplate("Manufacturing", null));
        assertEquals("trading", SectorMappingUtility.pickTemplate("Trading / Retail", null));
        assertEquals("services", SectorMappingUtility.pickTemplate("IT & Software Services", null));
        assertEquals("services", SectorMappingUtility.pickTemplate(null, "services"));
        assertEquals("trading", SectorMappingUtility.pickTemplate(null, "goods"));

        // Normalization
        assertEquals("factory_overheads", SectorMappingUtility.normalizeForTemplate("direct_cost", "manufacturing"));
        assertEquals("direct_cost", SectorMappingUtility.normalizeForTemplate("direct_labour", "trading"));
        assertEquals("direct_cost", SectorMappingUtility.normalizeForTemplate("factory_overheads", "trading"));
        assertEquals("direct_cost", SectorMappingUtility.normalizeForTemplate("materials", "services"));
        assertEquals("direct_cost", SectorMappingUtility.normalizeForTemplate("direct_labour", "services"));
    }

    @Test
    @DisplayName("System Ledger Codes Guard")
    void testSystemLedgerCodes() {
        Set<String> systemCodes = Set.of(
                "1000", "1100", "1200", "1400", "1401", "1402",
                "2000", "2100", "2200", "2201", "2202",
                "3900", "4000", "5000"
        );
        assertEquals(14, systemCodes.size());
        assertTrue(systemCodes.contains("1000")); // Cash
        assertTrue(systemCodes.contains("1100")); // Bank
        assertTrue(systemCodes.contains("1200")); // Debtors
        assertTrue(systemCodes.contains("2000")); // Creditors
        assertTrue(systemCodes.contains("4000")); // Sales
        assertTrue(systemCodes.contains("5000")); // Purchases
    }
}

