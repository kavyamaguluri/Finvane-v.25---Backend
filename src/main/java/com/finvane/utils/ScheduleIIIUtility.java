package com.finvane.utils;

import java.util.Set;

public final class ScheduleIIIUtility {

    private ScheduleIIIUtility() {}

    public static class Placement {
        private final String section;
        private final String line;
        private final String status; // "ok", "catchall", "unclassified"

        public Placement(String section, String line, String status) {
            this.section = section;
            this.line = line;
            this.status = status;
        }

        public String getSection() { return section; }
        public String getLine() { return line; }
        public String getStatus() { return status; }
    }

    private static final Set<String> RESERVE_GROUPS = Set.of("Reserves & Surplus", "Securities Premium", "Profit & Loss Account");
    private static final Set<String> OTHER_NON_CURRENT_ASSET_GROUPS = Set.of("Branch / Divisions", "Misc. Expenses (Asset)", "Suspense A/c");

    public static Placement scheduleIIIPlacement(String type, String primaryGroupName, String secondaryGroupName,
                                                 boolean affectsGrossProfit, String sectorLine, String groupLine, String code) {
        String p = primaryGroupName != null ? primaryGroupName : "";
        String s = secondaryGroupName != null ? secondaryGroupName : "";

        if ("income".equalsIgnoreCase(type)) {
            return affectsGrossProfit
                    ? new Placement("Statement of P&L", "Revenue from operations", "ok")
                    : new Placement("Statement of P&L", "Other income", "ok");
        }

        if ("expense".equalsIgnoreCase(type)) {
            if (affectsGrossProfit) {
                return new Placement("Statement of P&L", "Purchases of stock-in-trade / direct costs", "ok");
            }
            String b = SectorMappingUtility.scheduleIIIBucket(sectorLine, groupLine, code, type, primaryGroupName, secondaryGroupName, affectsGrossProfit);
            String line = "employee".equals(b) ? "Employee benefits expense"
                    : "depreciation".equals(b) ? "Depreciation and amortization"
                    : "finance".equals(b) ? "Finance costs" : "Other expenses";
            return new Placement("Statement of P&L", line, "other".equals(b) ? "catchall" : "ok");
        }

        final String BS = "Balance Sheet";
        final String BS_LIAB = "Balance Sheet › Equity & Liabilities";
        final String BS_ASSET = "Balance Sheet › Assets";

        if ("Capital Account".equals(p)) {
            return RESERVE_GROUPS.contains(s)
                    ? new Placement(BS_LIAB, "Reserves and surplus", "ok")
                    : new Placement(BS_LIAB, "Share capital", !s.isEmpty() ? "ok" : "catchall");
        }

        if ("Loans (Liability)".equals(p)) {
            return "Bank OD A/c".equals(s)
                    ? new Placement(BS_LIAB, "Short-term borrowings", "ok")
                    : new Placement(BS_LIAB, "Long-term borrowings", !s.isEmpty() ? "ok" : "catchall");
        }

        if ("Current Liabilities".equals(p)) {
            if ("Sundry Creditors".equals(s)) return new Placement(BS_LIAB, "Trade payables", "ok");
            if ("Provisions".equals(s)) return new Placement(BS_LIAB, "Short-term provisions", "ok");
            return new Placement(BS_LIAB, "Other current liabilities", "catchall");
        }

        if ("Fixed Assets".equals(p)) {
            return new Placement(BS_ASSET, "Tangible assets (non-current)", "ok");
        }

        if ("Investments".equals(p)) {
            return new Placement(BS_ASSET, "Non-current investments", "ok");
        }

        if (OTHER_NON_CURRENT_ASSET_GROUPS.contains(p)) {
            return new Placement(BS_ASSET, "Other non-current assets", "catchall");
        }

        if ("Current Assets".equals(p)) {
            if ("Stock-in-Hand".equals(s)) return new Placement(BS_ASSET, "Inventories", "ok");
            if ("Sundry Debtors".equals(s)) return new Placement(BS_ASSET, "Trade receivables", "ok");
            if ("Bank Accounts".equals(s) || "Cash-in-Hand".equals(s)) return new Placement(BS_ASSET, "Cash and cash equivalents", "ok");
            if ("Loans & Advances (Asset)".equals(s) || "Deposits (Asset)".equals(s)) return new Placement(BS_ASSET, "Short-term loans and advances", "ok");
            return new Placement(BS_ASSET, "Other current assets", "catchall");
        }

        return new Placement(BS, "Unclassified (shown separately)", "unclassified");
    }
}

