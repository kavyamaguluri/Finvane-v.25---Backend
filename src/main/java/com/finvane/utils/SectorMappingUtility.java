package com.finvane.utils;

import java.util.*;
import java.util.regex.Pattern;

public final class SectorMappingUtility {

    private SectorMappingUtility() {}

    public static class LineDef {
        private final String key;
        private final String kind; // "income" or "expense"
        private final String label;

        public LineDef(String key, String kind, String label) {
            this.key = key;
            this.kind = kind;
            this.label = label;
        }

        public String getKey() { return key; }
        public String getKind() { return kind; }
        public String getLabel() { return label; }
    }

    public static final Map<String, LineDef> LINES = new LinkedHashMap<>();

    static {
        LINES.put("revenue", new LineDef("revenue", "income", "Revenue from Operations"));
        LINES.put("other_income", new LineDef("other_income", "income", "Other Income"));
        LINES.put("materials", new LineDef("materials", "expense", "Raw Materials & Purchases"));
        LINES.put("direct_labour", new LineDef("direct_labour", "expense", "Direct Labour / Wages"));
        LINES.put("factory_overheads", new LineDef("factory_overheads", "expense", "Factory Overheads"));
        LINES.put("direct_cost", new LineDef("direct_cost", "expense", "Direct Costs"));
        LINES.put("employee", new LineDef("employee", "expense", "Employee Cost"));
        LINES.put("selling", new LineDef("selling", "expense", "Selling & Distribution"));
        LINES.put("admin", new LineDef("admin", "expense", "Administrative & Other"));
        LINES.put("depreciation", new LineDef("depreciation", "expense", "Depreciation & Amortisation"));
        LINES.put("finance", new LineDef("finance", "expense", "Finance Cost"));
    }

    public static final Map<String, List<Map.Entry<String, String>>> TEMPLATE_LINES = new LinkedHashMap<>();

    static {
        TEMPLATE_LINES.put("manufacturing", List.of(
                Map.entry("revenue", "Revenue from Operations"),
                Map.entry("other_income", "Other Income"),
                Map.entry("materials", "Raw Materials & Purchases"),
                Map.entry("direct_labour", "Direct Labour / Wages"),
                Map.entry("factory_overheads", "Factory Overheads"),
                Map.entry("employee", "Employee Cost"),
                Map.entry("selling", "Selling & Distribution"),
                Map.entry("admin", "Administrative & Other"),
                Map.entry("depreciation", "Depreciation & Amortisation"),
                Map.entry("finance", "Finance Cost")
        ));
        TEMPLATE_LINES.put("trading", List.of(
                Map.entry("revenue", "Revenue from Operations"),
                Map.entry("other_income", "Other Income"),
                Map.entry("materials", "Purchases of Stock-in-Trade"),
                Map.entry("direct_cost", "Direct Expenses (freight inward etc.)"),
                Map.entry("employee", "Employee Cost"),
                Map.entry("selling", "Selling & Distribution"),
                Map.entry("admin", "Administrative & Other"),
                Map.entry("depreciation", "Depreciation & Amortisation"),
                Map.entry("finance", "Finance Cost")
        ));
        TEMPLATE_LINES.put("services", List.of(
                Map.entry("revenue", "Revenue from Operations"),
                Map.entry("other_income", "Other Income"),
                Map.entry("direct_cost", "Direct Cost of Services"),
                Map.entry("employee", "Employee Cost"),
                Map.entry("selling", "Selling & Distribution"),
                Map.entry("admin", "Administrative & Other"),
                Map.entry("depreciation", "Depreciation & Amortisation"),
                Map.entry("finance", "Finance Cost")
        ));
    }

    public static final Set<String> MANUFACTURING_SECTORS = Set.of(
            "Manufacturing", "FMCG / Consumer Goods", "Pharma & Life Sciences", "Textiles & Apparel",
            "Auto Components", "Chemicals & Industrial Products", "Agro & Food Processing", "Agriculture"
    );
    public static final Set<String> TRADING_SECTORS = Set.of(
            "Trading / Retail", "Wholesale / Distribution", "E-commerce"
    );
    public static final Set<String> SERVICE_SECTORS = Set.of(
            "IT & Software Services", "SaaS / Software Products",
            "Professional Services (Legal, Accounting, Consulting)", "Healthcare",
            "Education", "Construction & Real Estate", "Hospitality & Food Service",
            "Transportation & Logistics", "Financial Services", "Media & Entertainment"
    );

    private static final Map<String, String> GROUP_LINE = new HashMap<>();

    static {
        GROUP_LINE.put("Employee Benefit Expenses", "employee");
        GROUP_LINE.put("Finance Costs", "finance");
        GROUP_LINE.put("Depreciation & Amortisation", "depreciation");
        GROUP_LINE.put("Selling & Distribution Expenses", "selling");
        GROUP_LINE.put("Administrative Expenses", "admin");
        GROUP_LINE.put("Professional & Legal Fees", "admin");
        GROUP_LINE.put("Rent, Rates & Insurance", "admin");
        GROUP_LINE.put("Direct Labour", "direct_labour");
        GROUP_LINE.put("Manufacturing Expenses", "factory_overheads");
        GROUP_LINE.put("Freight & Carriage Inward", "direct_cost");
        GROUP_LINE.put("Sub-contractor / Project Costs", "direct_cost");
        GROUP_LINE.put("Raw Material Purchases", "materials");
        GROUP_LINE.put("Stock-in-Trade Purchases", "materials");
        GROUP_LINE.put("Interest Income", "other_income");
        GROUP_LINE.put("Other Non-operating Income", "other_income");
        GROUP_LINE.put("Domestic Sales", "revenue");
        GROUP_LINE.put("Export Sales", "revenue");
        GROUP_LINE.put("Service Revenue", "revenue");
    }

    public static String pickTemplate(String sector, String businessType) {
        if (sector != null && MANUFACTURING_SECTORS.contains(sector)) return "manufacturing";
        if (sector != null && TRADING_SECTORS.contains(sector)) return "trading";
        if (sector != null && SERVICE_SECTORS.contains(sector)) return "services";
        if ("services".equalsIgnoreCase(businessType)) return "services";
        if ("goods".equalsIgnoreCase(businessType)) return "trading";
        return "trading";
    }

    public static List<Map.Entry<String, String>> linesFor(String templateKey, String kind) {
        List<Map.Entry<String, String>> t = TEMPLATE_LINES.getOrDefault(templateKey, TEMPLATE_LINES.get("trading"));
        if (kind == null || kind.isBlank()) return t;
        List<Map.Entry<String, String>> res = new ArrayList<>();
        for (Map.Entry<String, String> entry : t) {
            LineDef def = LINES.get(entry.getKey());
            if (def != null && kind.equalsIgnoreCase(def.getKind())) {
                res.add(entry);
            }
        }
        return res;
    }

    public static String labelFor(String templateKey, String key) {
        List<Map.Entry<String, String>> t = TEMPLATE_LINES.get(templateKey);
        if (t != null) {
            for (Map.Entry<String, String> entry : t) {
                if (entry.getKey().equals(key)) return entry.getValue();
            }
        }
        LineDef def = LINES.get(key);
        return def != null ? def.getLabel() : (key != null ? key : "");
    }

    public static String parseLine(String input) {
        if (input == null) return null;
        String v = input.trim().toLowerCase();
        if (v.isEmpty()) return null;
        if (LINES.containsKey(v)) return v;
        for (List<Map.Entry<String, String>> t : TEMPLATE_LINES.values()) {
            for (Map.Entry<String, String> entry : t) {
                if (entry.getValue().toLowerCase().equals(v)) return entry.getKey();
            }
        }
        for (Map.Entry<String, LineDef> entry : LINES.entrySet()) {
            if (entry.getValue().getLabel().toLowerCase().equals(v)) return entry.getKey();
        }
        return null;
    }

    public static Map.Entry<String, String> suggestLine(String type, String primaryGroupName, String secondaryGroupName, boolean affectsGrossProfit) {
        String g1 = primaryGroupName != null ? primaryGroupName : "";
        String g2 = secondaryGroupName != null ? secondaryGroupName : "";
        String grp = (g2 + " " + g1).trim();

        if (!g2.isEmpty() && GROUP_LINE.containsKey(g2)) {
            return Map.entry(GROUP_LINE.get(g2), "group: " + g2);
        }

        if ("income".equalsIgnoreCase(type)) {
            if (Pattern.compile("sales", Pattern.CASE_INSENSITIVE).matcher(grp).find() || affectsGrossProfit) {
                return Map.entry("revenue", "group: " + (!g2.isEmpty() ? g2 : g1));
            }
            return Map.entry("other_income", "group: " + (!g2.isEmpty() ? g2 : (!g1.isEmpty() ? g1 : "income")));
        }

        if (!"expense".equalsIgnoreCase(type)) {
            return Map.entry("", "not a P&L ledger");
        }

        if (Pattern.compile("finance cost|interest", Pattern.CASE_INSENSITIVE).matcher(grp).find()) {
            return Map.entry("finance", "group: " + (!g2.isEmpty() ? g2 : g1));
        }
        if (Pattern.compile("purchase", Pattern.CASE_INSENSITIVE).matcher(grp).find()) {
            return Map.entry("materials", "group: " + (!g2.isEmpty() ? g2 : g1));
        }

        return Map.entry("", "follows its group");
    }

    public static String statusOf(String sectorLine, String groupLine, String type) {
        if (sectorLine != null && !sectorLine.isBlank() && LINES.containsKey(sectorLine)) return "mapped";
        if (groupLine != null && !groupLine.isBlank() && LINES.containsKey(groupLine)) {
            LineDef def = LINES.get(groupLine);
            if (def != null && def.getKind().equalsIgnoreCase(type)) return "mapped";
        }
        return "mapped"; // follows the ledger's own groups
    }

    public static String sourceOf(String sectorLine, String groupLine, String type, String primaryGroupName, String secondaryGroupName, boolean affectsGrossProfit) {
        if (sectorLine != null && !sectorLine.isBlank() && LINES.containsKey(sectorLine)) return "ledger";
        if (groupLine != null && !groupLine.isBlank() && LINES.containsKey(groupLine)) {
            LineDef def = LINES.get(groupLine);
            if (def != null && def.getKind().equalsIgnoreCase(type)) return "group";
        }
        Map.Entry<String, String> sug = suggestLine(type, primaryGroupName, secondaryGroupName, affectsGrossProfit);
        return !sug.getKey().isEmpty() ? "suggestion" : "default";
    }

    public static String effectiveLine(String sectorLine, String groupLine, String type, String primaryGroupName, String secondaryGroupName, boolean affectsGrossProfit, String templateKey) {
        String line = (sectorLine != null && !sectorLine.isBlank() && LINES.containsKey(sectorLine)) ? sectorLine : null;
        if (line == null && groupLine != null && !groupLine.isBlank() && LINES.containsKey(groupLine)) {
            LineDef def = LINES.get(groupLine);
            if (def != null && def.getKind().equalsIgnoreCase(type)) {
                line = groupLine;
            }
        }
        if (line == null) {
            Map.Entry<String, String> sug = suggestLine(type, primaryGroupName, secondaryGroupName, affectsGrossProfit);
            String s = sug.getKey();
            boolean isDirectRow = affectsGrossProfit;
            boolean skip = !isDirectRow && ("materials".equals(s) || (("direct_labour".equals(s) || "factory_overheads".equals(s)) && !"manufacturing".equals(templateKey)));
            if (!s.isEmpty() && !skip) line = s;
        }
        if (line == null) {
            if ("income".equalsIgnoreCase(type)) line = affectsGrossProfit ? "revenue" : "other_income";
            else line = affectsGrossProfit ? "direct_cost" : "admin";
        }
        return normalizeForTemplate(line, templateKey);
    }

    public static String normalizeForTemplate(String line, String templateKey) {
        if ("manufacturing".equals(templateKey)) {
            return "direct_cost".equals(line) ? "factory_overheads" : line;
        }
        if ("trading".equals(templateKey)) {
            return ("direct_labour".equals(line) || "factory_overheads".equals(line)) ? "direct_cost" : line;
        }
        if ("services".equals(templateKey)) {
            return ("materials".equals(line) || "direct_labour".equals(line) || "factory_overheads".equals(line)) ? "direct_cost" : line;
        }
        return line;
    }

    public static String scheduleIIIBucket(String sectorLine, String groupLine, String code, String type, String primaryGroupName, String secondaryGroupName, boolean affectsGrossProfit) {
        if (!"expense".equalsIgnoreCase(type)) return "other";
        String line = (sectorLine != null && !sectorLine.isBlank() && LINES.containsKey(sectorLine)) ? sectorLine
                : ((groupLine != null && !groupLine.isBlank() && LINES.containsKey(groupLine)) ? groupLine
                : suggestLine(type, primaryGroupName, secondaryGroupName, affectsGrossProfit).getKey());
        if ("employee".equals(line) || "direct_labour".equals(line) || "5100".equals(code)) return "employee";
        if ("depreciation".equals(line) || "5900".equals(code)) return "depreciation";
        if ("finance".equals(line)) return "finance";
        return "other";
    }
}

