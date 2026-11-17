package com.finvane.service.settings;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Shared tenant-scoped settings and feature-flag resolver backed by app_settings and entities.
 */
@Service
public class TenantSettingsService {

    public record ServiceDefinition(String key, String group, String label, String hint, List<String> paths) {}

    public static final List<ServiceDefinition> SERVICES = List.of(
        new ServiceDefinition("financials", "Statutory", "Financials", "Statutory financial statements (Schedule III / sector view)", List.of("/reports/financials", "/reports/sector-financials")),
        new ServiceDefinition("gst", "Statutory", "GST", "GSTR-1, GSTR-3B and GSTR-2A/2B reconciliation", List.of("/reports/gstr1", "/reports/gstr3b", "/compliance/gstr2-reconciliation")),
        new ServiceDefinition("tds", "Statutory", "TDS", "TDS register from the TDS ledgers", List.of("/reports/tds")),
        new ServiceDefinition("epf", "Statutory", "EPF / PF report", "EPF deduction in payroll, EPF report and ECR file", List.of("/reports/epf")),
        new ServiceDefinition("esi", "Statutory", "ESI", "ESI deduction in payroll, ESI report and ESIC upload file", List.of("/reports/esi")),
        new ServiceDefinition("budget", "Budget", "Budget", "Plan Budget, Budget vs Actual and the dashboard Budget tab", List.of("/budgets")),
        new ServiceDefinition("financial_modelling", "Corporate Finance", "Financial Modelling", "Projections built from the books", List.of("/corporate-finance", "/financial-model")),
        new ServiceDefinition("valuation", "Corporate Finance", "Valuation report", "DCF and multiples valuation", List.of("/corporate-finance")),
        new ServiceDefinition("cma", "Corporate Finance", "CMA report", "Credit Monitoring Arrangement data for banks", List.of("/corporate-finance"))
    );

    private static final List<String> CORPORATE_KEYS = List.of("financial_modelling", "valuation", "cma");
    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private final JdbcTemplate jdbcTemplate;

    public TenantSettingsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String normalizeTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank() || "control".equalsIgnoreCase(tenantId) || "default".equalsIgnoreCase(tenantId)) {
            return "1";
        }
        return tenantId.trim();
    }

    public String getSetting(String tenantId, String key, String defaultValue) {
        String tid = normalizeTenantId(tenantId);
        try {
            List<String> rows = jdbcTemplate.query(
                "SELECT setting_value FROM app_settings WHERE tenant_id = ? AND setting_key = ? LIMIT 1",
                (rs, rowNum) -> rs.getString("setting_value"),
                tid, key
            );
            if (!rows.isEmpty() && rows.get(0) != null) {
                return rows.get(0);
            }
        } catch (Exception ignored) {}
        return defaultValue;
    }

    public void setSetting(String tenantId, String key, String value) {
        String tid = normalizeTenantId(tenantId);
        String val = value != null ? value : "";
        jdbcTemplate.update(
            "INSERT INTO app_settings (tenant_id, setting_key, setting_value) VALUES (?, ?, ?) " +
            "ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)",
            tid, key, val
        );
    }

    public boolean isInventoryEnabled(String tenantId) {
        String val = getSetting(tenantId, "inventory_enabled", "1");
        return !"0".equals(val.trim()) && !"false".equalsIgnoreCase(val.trim());
    }

    public boolean isGstRegistered(String tenantId) {
        String tid = normalizeTenantId(tenantId);
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT gst_registered FROM entities WHERE id = ? LIMIT 1",
                tid
            );
            if (!rows.isEmpty()) {
                return parseBooleanFlag(rows.get(0).get("gst_registered"), true);
            }
        } catch (Exception ignored) {}
        return true;
    }

    public boolean isManufacturingEnabled(String tenantId) {
        String tid = normalizeTenantId(tenantId);
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT sector FROM entities WHERE id = ? LIMIT 1",
                tid
            );
            if (!rows.isEmpty() && rows.get(0).get("sector") != null) {
                String sector = String.valueOf(rows.get(0).get("sector")).toLowerCase(Locale.ROOT);
                return sector.contains("manufactur");
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static boolean parseBooleanFlag(Object raw, boolean defaultValue) {
        if (raw == null) return defaultValue;
        if (raw instanceof Boolean b) return b;
        if (raw instanceof Number n) return n.intValue() != 0;
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return defaultValue;
        return !("0".equals(s) || "false".equals(s) || "no".equals(s) || "n".equals(s));
    }

    public LocalDate todayIst() {
        return LocalDate.now(ZoneId.of("Asia/Kolkata"));
    }

    public List<Map<String, Object>> getServiceDetails(String tenantId) {
        LocalDate today = todayIst();
        String todayStr = today.toString();
        List<Map<String, Object>> list = new ArrayList<>();

        for (ServiceDefinition s : SERVICES) {
            String rawOn = getSetting(tenantId, "svc_" + s.key(), "1").trim();
            boolean on = !"0".equals(rawOn) && !"false".equalsIgnoreCase(rawOn);
            String from = getSetting(tenantId, "svc_" + s.key() + "_from", "").trim();
            String to = getSetting(tenantId, "svc_" + s.key() + "_to", "").trim();

            String status = "off";
            if (on) {
                if (!from.isEmpty() && todayStr.compareTo(from) < 0) {
                    status = "scheduled";
                } else if (!to.isEmpty() && todayStr.compareTo(to) > 0) {
                    status = "expired";
                } else {
                    status = "active";
                }
            }

            Long daysLeft = null;
            if (!to.isEmpty() && "active".equals(status) && ISO_DATE.matcher(to).matches()) {
                daysLeft = ChronoUnit.DAYS.between(today, LocalDate.parse(to));
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", s.key());
            item.put("group", s.group());
            item.put("label", s.label());
            item.put("hint", s.hint());
            item.put("paths", s.paths());
            item.put("on", on);
            item.put("from", from);
            item.put("to", to);
            item.put("status", status);
            item.put("daysLeft", daysLeft);
            list.add(item);
        }
        return list;
    }

    public Map<String, Boolean> getActiveServices(String tenantId) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (Map<String, Object> detail : getServiceDetails(tenantId)) {
            out.put((String) detail.get("key"), "active".equals(detail.get("status")));
        }
        boolean corpActive = CORPORATE_KEYS.stream().anyMatch(k -> Boolean.TRUE.equals(out.get(k)));
        out.put("corporateFinance", corpActive);
        return out;
    }

    @SuppressWarnings("unchecked")
    public String saveServices(String tenantId, Map<String, Object> body) {
        if (body == null) return "Invalid request body.";

        // Structured payload { services: ["financials", ...], from_financials: "...", to_financials: "..." }
        if (body.containsKey("services") || body.containsKey("details")) {
            Set<String> enabled = new HashSet<>();
            Map<String, String> fromDates = new HashMap<>();
            Map<String, String> toDates = new HashMap<>();

            if (body.get("details") instanceof List<?> detailRows) {
                for (Object rowObj : detailRows) {
                    if (!(rowObj instanceof Map<?, ?> row)) continue;
                    String key = row.get("key") != null ? String.valueOf(row.get("key")) : "";
                    if (key.isEmpty()) continue;
                    if (parseBooleanFlag(row.get("on"), false)) {
                        enabled.add(key);
                    }
                    fromDates.put(key, cleanDate(row.get("from")));
                    toDates.put(key, cleanDate(row.get("to")));
                }
            } else {
                Object svcObj = body.get("services");
                if (svcObj instanceof Collection<?> col) {
                    for (Object k : col) {
                        if (k != null) enabled.add(String.valueOf(k));
                    }
                } else if (svcObj != null) {
                    enabled.add(String.valueOf(svcObj));
                }
                for (ServiceDefinition s : SERVICES) {
                    fromDates.put(s.key(), cleanDate(body.get("from_" + s.key())));
                    toDates.put(s.key(), cleanDate(body.get("to_" + s.key())));
                }
            }

            for (ServiceDefinition s : SERVICES) {
                String from = fromDates.getOrDefault(s.key(), "");
                String to = toDates.getOrDefault(s.key(), "");
                if (!from.isEmpty() && !to.isEmpty() && to.compareTo(from) < 0) {
                    return s.label() + ": the To date cannot be before the From date.";
                }
            }

            for (ServiceDefinition s : SERVICES) {
                setSetting(tenantId, "svc_" + s.key(), enabled.contains(s.key()) ? "1" : "0");
                setSetting(tenantId, "svc_" + s.key() + "_from", fromDates.getOrDefault(s.key(), ""));
                setSetting(tenantId, "svc_" + s.key() + "_to", toDates.getOrDefault(s.key(), ""));
            }
            return null;
        }

        // Flat key-value map fallback
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            String key = entry.getKey();
            String val = entry.getValue() != null ? String.valueOf(entry.getValue()) : "";
            for (ServiceDefinition s : SERVICES) {
                if (s.key().equals(key)) {
                    key = "svc_" + s.key();
                    val = parseBooleanFlag(entry.getValue(), false) ? "1" : "0";
                    break;
                }
            }
            setSetting(tenantId, key, val);
        }
        return null;
    }

    public Map<String, Object> getContextFlags(String tenantId) {
        Map<String, Object> flags = new LinkedHashMap<>();
        flags.put("services", getActiveServices(tenantId));
        flags.put("details", getServiceDetails(tenantId));
        flags.put("inventoryEnabled", isInventoryEnabled(tenantId));
        flags.put("gstRegistered", isGstRegistered(tenantId));
        flags.put("manufacturingEnabled", isManufacturingEnabled(tenantId));
        return flags;
    }

    public Long resolvePartyLedgerId(String tenantId, Long partyId, String partyName, boolean customer) {
        String tid = normalizeTenantId(tenantId);
        List<String> partyCandidates = new ArrayList<>();
        if (partyName != null && !partyName.isBlank()) {
            partyCandidates.add(partyName.trim());
        }
        String ptyCode = partyId != null ? ("PTY-" + partyId) : null;
        Long partyLedgerId = findExistingLedgerId(tid, ptyCode, partyCandidates);
        if (partyLedgerId != null) {
            return partyLedgerId;
        }
        return resolveOrCreateLedgerId(
            tid,
            customer ? "1200" : "2100",
            customer ? List.of("sundry debtors", "trade receivables", "accounts receivable", "debtors")
                     : List.of("sundry creditors", "trade payables", "accounts payable", "creditors"),
            customer ? "Sundry Debtors" : "Sundry Creditors",
            customer ? "Sundry Debtors" : "Sundry Creditors",
            customer ? "ASSET" : "LIABILITY"
        );
    }

    public Long resolveOrCreateLedgerId(String tenantId, String preferredCode, List<String> nameCandidates,
                                        String defaultName, String preferredGroupName, String fallbackNature) {
        String tid = normalizeTenantId(tenantId);
        Long existing = findExistingLedgerId(tid, preferredCode, nameCandidates);
        if (existing != null) {
            return existing;
        }

        Long groupId = resolveGroupId(tid, preferredGroupName, fallbackNature);
        String code = ensureUniqueLedgerCode(tid, preferredCode);

        KeyHolder kh = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO account_ledgers (tenant_id, code, name, group_id, opening_balance, current_balance, is_active, created_at) " +
                "VALUES (?, ?, ?, ?, 0.00, 0.00, 1, NOW())",
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, tid);
            ps.setString(2, code);
            ps.setString(3, defaultName);
            ps.setLong(4, groupId);
            return ps;
        }, kh);
        Number key = kh.getKey();
        return key != null ? key.longValue() : findExistingLedgerId(tid, code, List.of(defaultName));
    }

    private Long findExistingLedgerId(String tid, String preferredCode, List<String> nameCandidates) {
        if (preferredCode != null && !preferredCode.isBlank()) {
            List<Long> byCode = jdbcTemplate.query(
                "SELECT id FROM account_ledgers WHERE tenant_id = ? AND is_active = 1 AND LOWER(TRIM(code)) = LOWER(TRIM(?)) ORDER BY id LIMIT 1",
                (rs, rn) -> rs.getLong("id"),
                tid, preferredCode
            );
            if (!byCode.isEmpty()) return byCode.get(0);
        }
        if (nameCandidates != null) {
            for (String candidate : nameCandidates) {
                if (candidate == null || candidate.isBlank()) continue;
                List<Long> byName = jdbcTemplate.query(
                    "SELECT id FROM account_ledgers WHERE tenant_id = ? AND is_active = 1 AND LOWER(TRIM(name)) = LOWER(TRIM(?)) ORDER BY id LIMIT 1",
                    (rs, rn) -> rs.getLong("id"),
                    tid, candidate
                );
                if (!byName.isEmpty()) return byName.get(0);
            }
        }
        return null;
    }

    private Long resolveGroupId(String tid, String preferredGroupName, String fallbackNature) {
        if (preferredGroupName != null && !preferredGroupName.isBlank()) {
            List<Long> exact = jdbcTemplate.query(
                "SELECT id FROM account_groups WHERE (tenant_id = ? OR tenant_id = '1') AND LOWER(TRIM(name)) = LOWER(TRIM(?)) " +
                "ORDER BY CASE WHEN tenant_id = ? THEN 0 ELSE 1 END, id LIMIT 1",
                (rs, rn) -> rs.getLong("id"),
                tid, preferredGroupName, tid
            );
            if (!exact.isEmpty()) return exact.get(0);
        }
        if (fallbackNature != null && !fallbackNature.isBlank()) {
            List<Long> byNature = jdbcTemplate.query(
                "SELECT id FROM account_groups WHERE (tenant_id = ? OR tenant_id = '1') AND LOWER(nature) = LOWER(?) " +
                "ORDER BY CASE WHEN tenant_id = ? THEN 0 ELSE 1 END, id LIMIT 1",
                (rs, rn) -> rs.getLong("id"),
                tid, fallbackNature, tid
            );
            if (!byNature.isEmpty()) return byNature.get(0);
        }
        List<Long> any = jdbcTemplate.query("SELECT id FROM account_groups ORDER BY id LIMIT 1", (rs, rn) -> rs.getLong("id"));
        return any.isEmpty() ? 1L : any.get(0);
    }

    private String ensureUniqueLedgerCode(String tid, String preferredCode) {
        String base = (preferredCode != null && !preferredCode.isBlank()) ? preferredCode.trim() : "LDG";
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM account_ledgers WHERE tenant_id = ? AND LOWER(TRIM(code)) = LOWER(TRIM(?))",
            Integer.class, tid, base
        );
        if (count == null || count == 0) return base;
        for (int i = 1; i <= 50; i++) {
            String alt = base + "-" + i;
            Integer altCnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_ledgers WHERE tenant_id = ? AND LOWER(TRIM(code)) = LOWER(TRIM(?))",
                Integer.class, tid, alt
            );
            if (altCnt == null || altCnt == 0) return alt;
        }
        return base + "-" + (System.currentTimeMillis() % 10000);
    }

    private String cleanDate(Object raw) {
        if (raw == null) return "";
        String s = String.valueOf(raw).trim();
        return ISO_DATE.matcher(s).matches() ? s : "";
    }
}

