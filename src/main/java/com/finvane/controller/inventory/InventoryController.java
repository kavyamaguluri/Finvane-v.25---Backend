package com.finvane.controller.inventory;

import com.finvane.model.common.TenantContext;
import com.finvane.service.audit.AuditLogService;
import com.finvane.service.settings.TenantSettingsService;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

@RestController
@RequestMapping({"/api/v1/inventory", "/inventory", "/api/inventory"})
public class InventoryController {

    private final JdbcTemplate jdbcTemplate;
    private final AuditLogService auditLogService;
    private final TenantSettingsService tenantSettingsService;

    private static final List<String> CLASSES = List.of("raw_material", "wip", "finished_good");
    private static final String[] HEADERS = {
            "sku", "name", "item_type (goods/service)", "category", "brand", "hsn_sac", "uom",
            "purchase_price", "sale_price", "gst_rate", "opening_stock", "reorder_level",
            "item_class (raw_material/wip/finished_good, optional)"
    };

    public InventoryController(JdbcTemplate jdbcTemplate, AuditLogService auditLogService,
                               TenantSettingsService tenantSettingsService) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditLogService = auditLogService;
        this.tenantSettingsService = tenantSettingsService;
    }

    private boolean isInventoryEnabled() {
        return tenantSettingsService.isInventoryEnabled(TenantContext.getTenantId());
    }

    private String generateNextServiceSku(String tenantId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT sku FROM items WHERE tenant_id = ? AND sku LIKE 'SVC-%'", tenantId);
        int max = 0;
        for (Map<String, Object> r : rows) {
            String sku = (String) r.get("sku");
            if (sku != null && sku.startsWith("SVC-")) {
                try {
                    int num = Integer.parseInt(sku.substring(4).trim());
                    if (num > max) max = num;
                } catch (Exception ignored) {}
            }
        }
        return String.format("SVC-%04d", max + 1);
    }

    private Map<Long, BigDecimal> getLatestStockPerItem(String tenantId) {
        Map<Long, BigDecimal> map = new HashMap<>();
        String sql = "SELECT item_id, SUM(balance_qty) as total FROM (" +
                "  SELECT item_id, location_id, balance_qty, ROW_NUMBER() OVER (PARTITION BY location_id, item_id ORDER BY id DESC) rn " +
                "  FROM stock_ledger WHERE tenant_id = ?" +
                ") latest WHERE latest.rn = 1 GROUP BY item_id";
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, tenantId);
            for (Map<String, Object> r : rows) {
                Number iid = (Number) r.get("item_id");
                Number tot = (Number) r.get("total");
                if (iid != null && tot != null) {
                    map.put(iid.longValue(), new BigDecimal(tot.toString()).setScale(2, RoundingMode.HALF_UP));
                }
            }
        } catch (Exception ignored) {}
        return map;
    }

    // 1. GET /inventory/suggest and GET /api/inventory/suggest (Login only)
    @GetMapping("/suggest")
    public ResponseEntity<?> suggestItems(@RequestParam(value = "q", defaultValue = "") String q) {
        String tenantId = TenantContext.getTenantId();
        String trimmed = q.trim();
        if (trimmed.isEmpty()) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        boolean invEnabled = isInventoryEnabled();
        String sql = "SELECT id, sku, name, item_type, gst_rate, sale_price, hsn_sac FROM items " +
                "WHERE tenant_id = ? AND is_active = 1 AND LOWER(name) LIKE ? " +
                (!invEnabled ? "AND item_type = 'service' " : "") +
                "ORDER BY name, gst_rate LIMIT 20";

        List<Map<String, Object>> items = jdbcTemplate.queryForList(sql, tenantId, "%" + trimmed.toLowerCase() + "%");
        return ResponseEntity.ok(items);
    }

    // 2. POST /inventory/quick-add-service and POST /api/inventory/quick-add-service
    @PostMapping("/quick-add-service")
    public ResponseEntity<?> quickAddService(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean hasPerm = auth.getAuthorities().stream().anyMatch(a ->
                "invoices_manage".equals(a.getAuthority()) ||
                        "orders_manage".equals(a.getAuthority()) ||
                        "inventory_manage".equals(a.getAuthority()) ||
                        "ROLE_ADMIN".equals(a.getAuthority())
        );
        if (!hasPerm) {
            return ResponseEntity.status(403).body(Map.of("ok", false, "error", "Your role cannot add services."));
        }

        String name = stringVal(body.get("name"));
        BigDecimal salePrice = bigDecimalVal(body.get("sale_price"));
        BigDecimal gstRate = body.get("gst_rate") != null && !stringVal(body.get("gst_rate")).isBlank()
                ? bigDecimalVal(body.get("gst_rate")) : new BigDecimal("18.00");
        String sac = stringVal(body.get("hsn_sac"));

        if (name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Service name is required."));
        }
        if (salePrice.compareTo(BigDecimal.ZERO) < 0) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Enter the rate (0 or more)."));
        }
        if (gstRate.compareTo(BigDecimal.ZERO) < 0 || gstRate.compareTo(new BigDecimal("100")) > 0) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "GST rate must be between 0 and 100."));
        }

        String tenantId = TenantContext.getTenantId();
        List<Map<String, Object>> dup = jdbcTemplate.queryForList(
                "SELECT id FROM items WHERE tenant_id = ? AND LOWER(name) = LOWER(?) AND gst_rate = ? AND is_active = 1 LIMIT 1",
                tenantId, name, gstRate
        );
        if (!dup.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "A service with this name and GST rate already exists. Search for it in the box."));
        }

        String sku = generateNextServiceSku(tenantId);

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO items (tenant_id, sku, name, item_type, hsn_sac, uom, purchase_price, sale_price, gst_rate, opening_stock, reorder_level, is_active, created_at) " +
                            "VALUES (?, ?, ?, 'service', ?, 'NOS', 0, ?, ?, 0, 0, 1, NOW())",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, tenantId);
            ps.setString(2, sku);
            ps.setString(3, name);
            ps.setString(4, sac.isBlank() ? null : sac);
            ps.setBigDecimal(5, salePrice.setScale(2, RoundingMode.HALF_UP));
            ps.setBigDecimal(6, gstRate.setScale(2, RoundingMode.HALF_UP));
            return ps;
        }, keyHolder);

        long newId = Objects.requireNonNull(keyHolder.getKey()).longValue();
        auditLogService.logAction("create", "item", String.valueOf(newId), null, Map.of("sku", sku, "name", name, "item_type", "service"), request);

        Map<String, Object> itemData = new LinkedHashMap<>();
        itemData.put("id", newId);
        itemData.put("sku", sku);
        itemData.put("name", name);
        itemData.put("item_type", "service");
        itemData.put("hsn_sac", sac);
        itemData.put("gst_rate", gstRate);
        itemData.put("sale_price", salePrice);

        return ResponseEntity.ok(Map.of("ok", true, "item", itemData));
    }

    // 3. GET /inventory and GET /api/v1/inventory
    @GetMapping({"", "/items"})
    @PreAuthorize("hasAuthority('inventory_manage')")
    public ResponseEntity<?> listInventory() {
        String tenantId = TenantContext.getTenantId();
        boolean invEnabled = isInventoryEnabled();
        boolean servicesOnly = !invEnabled;

        String sql = "SELECT * FROM items WHERE tenant_id = ? AND is_active = 1 " +
                (servicesOnly ? "AND item_type = 'service' " : "") +
                "ORDER BY name";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, tenantId);
        Map<Long, BigDecimal> stockMap = getLatestStockPerItem(tenantId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> item = new LinkedHashMap<>(r);
            long id = ((Number) r.get("id")).longValue();
            String itemType = (String) r.get("item_type");

            BigDecimal purchasePrice = bigDecimalVal(r.get("purchase_price")).setScale(2, RoundingMode.HALF_UP);
            BigDecimal salePrice = bigDecimalVal(r.get("sale_price")).setScale(2, RoundingMode.HALF_UP);
            BigDecimal margin = salePrice.subtract(purchasePrice).setScale(2, RoundingMode.HALF_UP);

            BigDecimal marginPercent = null;
            if (salePrice.compareTo(BigDecimal.ZERO) > 0) {
                marginPercent = margin.multiply(BigDecimal.valueOf(100)).divide(salePrice, 2, RoundingMode.HALF_UP);
            }

            item.put("margin", margin);
            item.put("marginPercent", marginPercent);
            item.put("itemType", itemType);
            item.put("salePrice", salePrice);
            item.put("purchasePrice", purchasePrice);
            item.put("costPrice", purchasePrice);
            item.put("gstRate", bigDecimalVal(r.get("gst_rate")).setScale(2, RoundingMode.HALF_UP));
            item.put("hsnSac", r.get("hsn_sac"));

            if ("service".equalsIgnoreCase(itemType)) {
                item.put("current_stock", null);
            } else {
                BigDecimal currentStock = stockMap.getOrDefault(id, bigDecimalVal(r.get("opening_stock")));
                item.put("current_stock", currentStock.setScale(2, RoundingMode.HALF_UP));
            }
            result.add(item);
        }

        boolean gstRegistered = tenantSettingsService.isGstRegistered(TenantContext.getTenantId());
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "title", servicesOnly ? "Services" : "Inventory & Services",
                "servicesOnly", servicesOnly,
                "gstRegistered", gstRegistered,
                "items", result,
                "data", result
        ));
    }

    // 4. GET /inventory/new and GET /api/v1/inventory/form-options
    @GetMapping({"/new", "/form-options"})
    @PreAuthorize("hasAuthority('inventory_manage')")
    public ResponseEntity<?> getItemFormOptions() {
        boolean invEnabled = isInventoryEnabled();
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "servicesOnly", !invEnabled,
                "uoms", List.of("NOS", "KGS", "MTR", "BOX", "PCS", "LTR", "SET"),
                "itemClasses", List.of(
                        Map.of("code", "raw_material", "label", "Raw Material"),
                        Map.of("code", "wip", "label", "WIP (semi-finished)"),
                        Map.of("code", "finished_good", "label", "Finished Good")
                ),
                "gstRates", List.of(0, 5, 12, 18, 28)
        ));
    }

    // 5. POST /inventory and POST /inventory/new and POST /api/v1/inventory
    @PostMapping({"", "/new", "/items"})
    @PreAuthorize("hasAuthority('inventory_manage')")
    public ResponseEntity<?> createItem(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String tenantId = TenantContext.getTenantId();
        boolean invEnabled = isInventoryEnabled();
        boolean servicesOnly = !invEnabled;

        String itemType = servicesOnly ? "service" : stringVal(body.containsKey("item_type") ? body.get("item_type") : body.get("itemType")).toLowerCase();
        if (itemType.isBlank()) itemType = "goods";

        String sku = stringVal(body.get("sku"));
        if (servicesOnly && sku.isBlank()) {
            sku = generateNextServiceSku(tenantId);
        }

        String name = stringVal(body.get("name"));
        if (sku.isBlank() || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "SKU and Name are required."));
        }

        Integer existingSku = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM items WHERE tenant_id = ? AND LOWER(TRIM(sku)) = LOWER(TRIM(?))", Integer.class, tenantId, sku);
        if (existingSku != null && existingSku > 0) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", servicesOnly ? "A service with this code already exists." : "SKU must be unique."));
        }

        String category = stringVal(body.get("category"));
        String brand = stringVal(body.get("brand"));
        String hsnSac = stringVal(body.containsKey("hsn_sac") ? body.get("hsn_sac") : body.get("hsnSac"));
        String uom = "service".equals(itemType) ? "NOS" : (stringVal(body.get("uom")).isBlank() ? "NOS" : stringVal(body.get("uom")));
        Object rawPurchasePrice = body.containsKey("purchase_price") ? body.get("purchase_price") : (body.containsKey("purchasePrice") ? body.get("purchasePrice") : body.get("costPrice"));
        BigDecimal purchasePrice = "service".equals(itemType) ? BigDecimal.ZERO : bigDecimalVal(rawPurchasePrice);
        BigDecimal salePrice = bigDecimalVal(body.containsKey("sale_price") ? body.get("sale_price") : body.get("salePrice"));
        Object rawGst = body.containsKey("gst_rate") ? body.get("gst_rate") : body.get("gstRate");
        BigDecimal gstRate = rawGst != null && !stringVal(rawGst).isBlank()
                ? bigDecimalVal(rawGst) : new BigDecimal("18.00");
        BigDecimal openingStock = "service".equals(itemType) ? BigDecimal.ZERO : bigDecimalVal(body.containsKey("opening_stock") ? body.get("opening_stock") : body.get("openingStock"));
        BigDecimal reorderLevel = "service".equals(itemType) ? BigDecimal.ZERO : bigDecimalVal(body.containsKey("reorder_level") ? body.get("reorder_level") : body.get("reorderLevel"));
        String rawClass = stringVal(body.containsKey("item_class") ? body.get("item_class") : body.get("itemClass"));
        String itemClass = (!"service".equals(itemType) && CLASSES.contains(rawClass)) ? rawClass : null;

        KeyHolder keyHolder = new GeneratedKeyHolder();
        final String fSku = sku;
        final String fType = itemType;
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO items (tenant_id, sku, name, item_type, category, brand, hsn_sac, uom, purchase_price, sale_price, gst_rate, opening_stock, reorder_level, item_class, is_active, created_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, NOW())",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, tenantId);
            ps.setString(2, fSku);
            ps.setString(3, name);
            ps.setString(4, fType);
            ps.setString(5, category.isBlank() ? null : category);
            ps.setString(6, brand.isBlank() ? null : brand);
            ps.setString(7, hsnSac.isBlank() ? null : hsnSac);
            ps.setString(8, uom);
            ps.setBigDecimal(9, purchasePrice.setScale(2, RoundingMode.HALF_UP));
            ps.setBigDecimal(10, salePrice.setScale(2, RoundingMode.HALF_UP));
            ps.setBigDecimal(11, gstRate.setScale(2, RoundingMode.HALF_UP));
            ps.setBigDecimal(12, openingStock.setScale(2, RoundingMode.HALF_UP));
            ps.setBigDecimal(13, reorderLevel.setScale(2, RoundingMode.HALF_UP));
            ps.setString(14, itemClass);
            return ps;
        }, keyHolder);

        long newId = Objects.requireNonNull(keyHolder.getKey()).longValue();
        auditLogService.logAction("create", "item", String.valueOf(newId), null, body, request);

        return ResponseEntity.ok(Map.of("ok", true, "id", newId, "message", "Item created successfully."));
    }

    // 6. GET /inventory/{id}/ledger and GET /api/v1/inventory/{id}/ledger
    @GetMapping({"/{id}/ledger", "/items/{id}/ledger"})
    @PreAuthorize("hasAuthority('inventory_manage')")
    public ResponseEntity<?> getItemLedger(@PathVariable("id") long id) {
        String tenantId = TenantContext.getTenantId();
        List<Map<String, Object>> itemRows = jdbcTemplate.queryForList("SELECT * FROM items WHERE tenant_id = ? AND id = ?", tenantId, id);
        if (itemRows.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", "Item not found."));
        }
        Map<String, Object> item = itemRows.get(0);

        List<Map<String, Object>> entries = jdbcTemplate.queryForList(
                "SELECT sl.*, l.code as location_code, l.name as location_name FROM stock_ledger sl " +
                        "LEFT JOIN locations l ON l.id = sl.location_id WHERE sl.tenant_id = ? AND sl.item_id = ? ORDER BY sl.id DESC", tenantId, id
        );

        List<Map<String, Object>> locations = jdbcTemplate.queryForList("SELECT id, code, name FROM locations WHERE tenant_id = ? AND is_active = 1 ORDER BY code", tenantId);
        List<Map<String, Object>> byLocation = new ArrayList<>();
        for (Map<String, Object> loc : locations) {
            long locId = ((Number) loc.get("id")).longValue();
            BigDecimal qty = BigDecimal.ZERO;
            try {
                List<Map<String, Object>> balRows = jdbcTemplate.queryForList(
                        "SELECT balance_qty FROM stock_ledger WHERE item_id = ? AND location_id = ? ORDER BY id DESC LIMIT 1", id, locId
                );
                if (!balRows.isEmpty()) {
                    qty = bigDecimalVal(balRows.get(0).get("balance_qty"));
                }
            } catch (Exception ignored) {}

            Map<String, Object> bLoc = new LinkedHashMap<>(loc);
            bLoc.put("location_id", locId);
            bLoc.put("qty", qty.setScale(2, RoundingMode.HALF_UP));
            byLocation.add(bLoc);
        }

        Map<String, Object> data = Map.of(
                "item", item,
                "entries", entries,
                "byLocation", byLocation
        );

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "item", item,
                "entries", entries,
                "byLocation", byLocation,
                "data", data
        ));
    }

    // 7. GET /inventory/report and GET /api/v1/inventory/report
    @GetMapping("/report")
    @PreAuthorize("hasAuthority('inventory_manage')")
    public ResponseEntity<?> getInventoryReport(
            @RequestParam(value = "from", required = false) String fromStr,
            @RequestParam(value = "to", required = false) String toStr) {

        String tenantId = TenantContext.getTenantId();
        LocalDate today = LocalDate.now();
        LocalDate fromDate = (fromStr != null && !fromStr.isBlank()) ? LocalDate.parse(fromStr) : today.withDayOfMonth(1);
        LocalDate toDate = (toStr != null && !toStr.isBlank()) ? LocalDate.parse(toStr) : today;

        List<Map<String, Object>> goods = jdbcTemplate.queryForList("SELECT * FROM items WHERE tenant_id = ? AND is_active = 1 AND item_type = 'goods' ORDER BY name", tenantId);
        Map<Long, BigDecimal> stockMap = getLatestStockPerItem(tenantId);

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal totalStockVal = BigDecimal.ZERO;
        BigDecimal totD30 = BigDecimal.ZERO;
        BigDecimal totD60 = BigDecimal.ZERO;
        BigDecimal totD90 = BigDecimal.ZERO;
        BigDecimal totD90Plus = BigDecimal.ZERO;

        for (Map<String, Object> item : goods) {
            long itemId = ((Number) item.get("id")).longValue();
            BigDecimal currentStock = stockMap.getOrDefault(itemId, bigDecimalVal(item.get("opening_stock"))).setScale(2, RoundingMode.HALF_UP);

            List<Map<String, Object>> openLots = jdbcTemplate.queryForList(
                    "SELECT * FROM stock_lots WHERE item_id = ? AND qty_remaining > 0", itemId
            );

            BigDecimal stockValue = BigDecimal.ZERO;
            BigDecimal d30 = BigDecimal.ZERO;
            BigDecimal d60 = BigDecimal.ZERO;
            BigDecimal d90 = BigDecimal.ZERO;
            BigDecimal d90plus = BigDecimal.ZERO;

            for (Map<String, Object> l : openLots) {
                BigDecimal qtyRem = bigDecimalVal(l.get("qty_remaining"));
                BigDecimal rate = bigDecimalVal(l.get("rate"));
                stockValue = stockValue.add(qtyRem.multiply(rate));

                Object lotDateObj = l.get("lot_date");
                LocalDate lotDate = today;
                if (lotDateObj instanceof java.sql.Date sd) {
                    lotDate = sd.toLocalDate();
                } else if (lotDateObj instanceof java.time.LocalDate ld) {
                    lotDate = ld;
                }
                long days = ChronoUnit.DAYS.between(lotDate, today);
                if (days <= 30) d30 = d30.add(qtyRem);
                else if (days <= 60) d60 = d60.add(qtyRem);
                else if (days <= 90) d90 = d90.add(qtyRem);
                else d90plus = d90plus.add(qtyRem);
            }
            stockValue = stockValue.setScale(2, RoundingMode.HALF_UP);
            totalStockVal = totalStockVal.add(stockValue);

            totD30 = totD30.add(d30);
            totD60 = totD60.add(d60);
            totD90 = totD90.add(d90);
            totD90Plus = totD90Plus.add(d90plus);

            // Last purchase
            Map<String, Object> lastPurchase = null;
            try {
                List<Map<String, Object>> lp = jdbcTemplate.queryForList(
                        "SELECT sl.lot_date, sl.rate, p.name as vendor FROM stock_lots sl " +
                                "LEFT JOIN parties p ON p.id = sl.vendor_party_id " +
                                "WHERE sl.item_id = ? AND sl.source_type = 'purchase' " +
                                "ORDER BY sl.lot_date DESC, sl.id DESC LIMIT 1", itemId
                );
                if (!lp.isEmpty()) lastPurchase = lp.get(0);
            } catch (Exception ignored) {}

            // Last sale
            Map<String, Object> lastSale = null;
            try {
                List<Map<String, Object>> ls = jdbcTemplate.queryForList(
                        "SELECT c.sale_date, c.sale_rate, p.name as customer FROM stock_lot_consumptions c " +
                                "LEFT JOIN parties p ON p.id = c.customer_party_id " +
                                "WHERE c.item_id = ? AND c.sale_ref_type = 'sale' " +
                                "ORDER BY c.sale_date DESC, c.id DESC LIMIT 1", itemId
                );
                if (!ls.isEmpty()) lastSale = ls.get(0);
            } catch (Exception ignored) {}

            Map<String, Object> r = new LinkedHashMap<>(item);
            r.put("currentStock", currentStock);
            r.put("stockValue", stockValue);
            r.put("lastPurchase", lastPurchase);
            r.put("lastSale", lastSale);
            r.put("aging", Map.of(
                    "d30", d30.setScale(2, RoundingMode.HALF_UP),
                    "d60", d60.setScale(2, RoundingMode.HALF_UP),
                    "d90", d90.setScale(2, RoundingMode.HALF_UP),
                    "d90plus", d90plus.setScale(2, RoundingMode.HALF_UP)
            ));
            rows.add(r);
        }

        Map<String, Object> agingTotals = Map.of(
                "d30", totD30.setScale(2, RoundingMode.HALF_UP),
                "d60", totD60.setScale(2, RoundingMode.HALF_UP),
                "d90", totD90.setScale(2, RoundingMode.HALF_UP),
                "d90plus", totD90Plus.setScale(2, RoundingMode.HALF_UP)
        );

        List<Map<String, Object>> profitRows = new ArrayList<>();
        BigDecimal totalProfit = BigDecimal.ZERO;
        try {
            profitRows = jdbcTemplate.queryForList(
                    "SELECT c.*, i.name as item_name, i.sku, vp.name as vendor_name, cp.name as customer_name " +
                            "FROM stock_lot_consumptions c " +
                            "JOIN items i ON i.id = c.item_id " +
                            "LEFT JOIN stock_lots l ON l.id = c.lot_id " +
                            "LEFT JOIN parties vp ON vp.id = l.vendor_party_id " +
                            "LEFT JOIN parties cp ON cp.id = c.customer_party_id " +
                            "WHERE c.sale_date BETWEEN ? AND ? " +
                            "ORDER BY c.sale_date DESC, c.id DESC LIMIT 300",
                    Date.valueOf(fromDate), Date.valueOf(toDate)
            );
            for (Map<String, Object> pr : profitRows) {
                totalProfit = totalProfit.add(bigDecimalVal(pr.get("profit")));
            }
        } catch (Exception ignored) {}

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "title", "Inventory Report",
                "rows", rows,
                "agingTotals", agingTotals,
                "profitRows", profitRows,
                "totalStockValue", totalStockVal.setScale(2, RoundingMode.HALF_UP),
                "totalProfit", totalProfit.setScale(2, RoundingMode.HALF_UP),
                "from", fromDate.toString(),
                "to", toDate.toString()
        ));
    }

    // 8. GET /inventory/report/export and GET /api/v1/inventory/report/export
    @GetMapping("/report/export")
    @PreAuthorize("hasAuthority('inventory_manage')")
    public ResponseEntity<byte[]> exportInventoryValuationReport() {
        String tenantId = TenantContext.getTenantId();
        List<Map<String, Object>> goods = jdbcTemplate.queryForList("SELECT * FROM items WHERE tenant_id = ? AND is_active = 1 AND item_type = 'goods' ORDER BY name", tenantId);
        Map<Long, BigDecimal> stockMap = getLatestStockPerItem(tenantId);

        String[] cols = {
                "sku", "name", "category", "brand", "current_stock", "stock_value",
                "last_purchase_date", "last_purchase_rate", "last_purchase_vendor",
                "last_sale_date", "last_sale_rate", "last_sale_customer"
        };

        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Inventory Report");
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < cols.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(cols[i]);
            }

            int rowIdx = 1;
            for (Map<String, Object> item : goods) {
                long itemId = ((Number) item.get("id")).longValue();
                BigDecimal currentStock = stockMap.getOrDefault(itemId, bigDecimalVal(item.get("opening_stock")));

                List<Map<String, Object>> openLots = jdbcTemplate.queryForList(
                        "SELECT * FROM stock_lots WHERE item_id = ? AND qty_remaining > 0", itemId
                );
                BigDecimal stockValue = BigDecimal.ZERO;
                for (Map<String, Object> l : openLots) {
                    stockValue = stockValue.add(bigDecimalVal(l.get("qty_remaining")).multiply(bigDecimalVal(l.get("rate"))));
                }

                Map<String, Object> lp = null;
                try {
                    List<Map<String, Object>> lps = jdbcTemplate.queryForList(
                            "SELECT sl.lot_date, sl.rate, p.name as vendor FROM stock_lots sl " +
                                    "LEFT JOIN parties p ON p.id = sl.vendor_party_id " +
                                    "WHERE sl.item_id = ? AND sl.source_type = 'purchase' " +
                                    "ORDER BY sl.lot_date DESC LIMIT 1", itemId
                    );
                    if (!lps.isEmpty()) lp = lps.get(0);
                } catch (Exception ignored) {}

                Map<String, Object> ls = null;
                try {
                    List<Map<String, Object>> lss = jdbcTemplate.queryForList(
                            "SELECT c.sale_date, c.sale_rate, p.name as customer FROM stock_lot_consumptions c " +
                                    "LEFT JOIN parties p ON p.id = c.customer_party_id " +
                                    "WHERE c.item_id = ? AND c.sale_ref_type = 'sale' " +
                                    "ORDER BY c.sale_date DESC LIMIT 1", itemId
                    );
                    if (!lss.isEmpty()) ls = lss.get(0);
                } catch (Exception ignored) {}

                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(stringVal(item.get("sku")));
                row.createCell(1).setCellValue(stringVal(item.get("name")));
                row.createCell(2).setCellValue(stringVal(item.get("category")));
                row.createCell(3).setCellValue(stringVal(item.get("brand")));
                row.createCell(4).setCellValue(currentStock.doubleValue());
                row.createCell(5).setCellValue(stockValue.setScale(2, RoundingMode.HALF_UP).doubleValue());
                row.createCell(6).setCellValue(lp != null ? stringVal(lp.get("lot_date")) : "");
                row.createCell(7).setCellValue(lp != null ? bigDecimalVal(lp.get("rate")).doubleValue() : 0.0);
                row.createCell(8).setCellValue(lp != null ? stringVal(lp.get("vendor")) : "");
                row.createCell(9).setCellValue(ls != null ? stringVal(ls.get("sale_date")) : "");
                row.createCell(10).setCellValue(ls != null ? bigDecimalVal(ls.get("sale_rate")).doubleValue() : 0.0);
                row.createCell(11).setCellValue(ls != null ? stringVal(ls.get("customer")) : "");
            }

            for (int i = 0; i < cols.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(out);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=inventory-report.xlsx")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // 9. GET /inventory/report/profit-export and GET /api/v1/inventory/report/profit-export
    @GetMapping("/report/profit-export")
    @PreAuthorize("hasAuthority('inventory_manage')")
    public ResponseEntity<byte[]> exportInventoryProfitReport(
            @RequestParam(value = "from", required = false) String fromStr,
            @RequestParam(value = "to", required = false) String toStr) {

        LocalDate today = LocalDate.now();
        LocalDate fromDate = (fromStr != null && !fromStr.isBlank()) ? LocalDate.parse(fromStr) : today.withDayOfMonth(1);
        LocalDate toDate = (toStr != null && !toStr.isBlank()) ? LocalDate.parse(toStr) : today;

        String[] cols = {"item", "sku", "vendor", "customer", "qty", "purchase_rate", "sale_rate", "sale_date", "profit"};

        String tenantId = TenantContext.getTenantId();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT c.*, i.name as item_name, i.sku, vp.name as vendor_name, cp.name as customer_name " +
                        "FROM stock_lot_consumptions c JOIN items i ON i.id = c.item_id " +
                        "LEFT JOIN stock_lots l ON l.id = c.lot_id LEFT JOIN parties vp ON vp.id = l.vendor_party_id " +
                        "LEFT JOIN parties cp ON cp.id = c.customer_party_id WHERE i.tenant_id = ? AND c.sale_date BETWEEN ? AND ? ORDER BY c.sale_date DESC",
                tenantId, Date.valueOf(fromDate), Date.valueOf(toDate)
        );

        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Purchase-to-Sale Profit");
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < cols.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(cols[i]);
            }

            int rowIdx = 1;
            for (Map<String, Object> r : rows) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(stringVal(r.get("item_name")));
                row.createCell(1).setCellValue(stringVal(r.get("sku")));
                row.createCell(2).setCellValue(stringVal(r.get("vendor_name")));
                row.createCell(3).setCellValue(stringVal(r.get("customer_name")));
                row.createCell(4).setCellValue(bigDecimalVal(r.get("consumed_qty")).doubleValue());
                row.createCell(5).setCellValue(bigDecimalVal(r.get("purchase_rate")).doubleValue());
                row.createCell(6).setCellValue(bigDecimalVal(r.get("sale_rate")).doubleValue());
                row.createCell(7).setCellValue(stringVal(r.get("sale_date")));
                row.createCell(8).setCellValue(bigDecimalVal(r.get("profit")).doubleValue());
            }

            for (int i = 0; i < cols.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(out);
            String fn = "inventory-purchase-to-sale-profit-" + fromDate + "-to-" + toDate + ".xlsx";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + fn)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // 10. GET /inventory/export/template and GET /inventory/template
    @GetMapping({"/export/template", "/template"})
    @PreAuthorize("hasAuthority('inventory_manage')")
    public ResponseEntity<byte[]> downloadItemTemplate() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Items Template");
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(HEADERS[i]);
            }

            Row exampleRow = sheet.createRow(1);
            exampleRow.createCell(0).setCellValue("ITM-001");
            exampleRow.createCell(1).setCellValue("Wireless Mouse");
            exampleRow.createCell(2).setCellValue("goods");
            exampleRow.createCell(3).setCellValue("Electronics");
            exampleRow.createCell(4).setCellValue("Logi");
            exampleRow.createCell(5).setCellValue("84716060");
            exampleRow.createCell(6).setCellValue("NOS");
            exampleRow.createCell(7).setCellValue(450.0);
            exampleRow.createCell(8).setCellValue(799.0);
            exampleRow.createCell(9).setCellValue(18.0);
            exampleRow.createCell(10).setCellValue(50.0);
            exampleRow.createCell(11).setCellValue(10.0);
            exampleRow.createCell(12).setCellValue("finished_good");

            for (int i = 0; i < HEADERS.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(out);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=items-template.xlsx")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // 11. GET /inventory/export
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('inventory_manage')")
    public ResponseEntity<byte[]> exportItemExcel() {
        String tenantId = TenantContext.getTenantId();
        List<Map<String, Object>> items = jdbcTemplate.queryForList("SELECT * FROM items WHERE tenant_id = ? AND is_active = 1 ORDER BY name", tenantId);
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Items & Inventory Master");
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(HEADERS[i]);
            }

            int rowIdx = 1;
            for (Map<String, Object> i : items) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(stringVal(i.get("sku")));
                row.createCell(1).setCellValue(stringVal(i.get("name")));
                row.createCell(2).setCellValue(stringVal(i.get("item_type")));
                row.createCell(3).setCellValue(stringVal(i.get("category")));
                row.createCell(4).setCellValue(stringVal(i.get("brand")));
                row.createCell(5).setCellValue(stringVal(i.get("hsn_sac")));
                row.createCell(6).setCellValue(stringVal(i.get("uom")));
                row.createCell(7).setCellValue(bigDecimalVal(i.get("purchase_price")).doubleValue());
                row.createCell(8).setCellValue(bigDecimalVal(i.get("sale_price")).doubleValue());
                row.createCell(9).setCellValue(bigDecimalVal(i.get("gst_rate")).doubleValue());
                row.createCell(10).setCellValue(bigDecimalVal(i.get("opening_stock")).doubleValue());
                row.createCell(11).setCellValue(bigDecimalVal(i.get("reorder_level")).doubleValue());
                row.createCell(12).setCellValue(stringVal(i.get("item_class")));
            }

            for (int i = 0; i < HEADERS.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(out);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=items.xlsx")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // 12. POST /inventory/import
    @PostMapping("/import")
    @PreAuthorize("hasAuthority('inventory_manage')")
    public ResponseEntity<?> importItems(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
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

                String sku = getCellValue(row.getCell(0)).trim();
                String name = getCellValue(row.getCell(1)).trim();
                String itemType = getCellValue(row.getCell(2)).trim().toLowerCase();
                if (itemType.isBlank()) itemType = "goods";
                String category = getCellValue(row.getCell(3)).trim();
                String brand = getCellValue(row.getCell(4)).trim();
                String hsnSac = getCellValue(row.getCell(5)).trim();
                String uom = getCellValue(row.getCell(6)).trim();
                if (uom.isBlank()) uom = "NOS";
                BigDecimal purchasePrice = parseDecimal(getCellValue(row.getCell(7)));
                BigDecimal salePrice = parseDecimal(getCellValue(row.getCell(8)));
                BigDecimal gstRate = parseDecimal(getCellValue(row.getCell(9)));
                if (gstRate.compareTo(BigDecimal.ZERO) == 0) gstRate = new BigDecimal("18.00");
                BigDecimal openingStock = "goods".equals(itemType) ? parseDecimal(getCellValue(row.getCell(10))) : BigDecimal.ZERO;
                BigDecimal reorderLevel = "goods".equals(itemType) ? parseDecimal(getCellValue(row.getCell(11))) : BigDecimal.ZERO;
                String itemClass = getCellValue(row.getCell(12)).trim().toLowerCase();
                if (!CLASSES.contains(itemClass)) itemClass = null;

                int rowNum = r + 1;
                if (sku.isBlank() || name.isBlank()) {
                    errors.add(Map.of("row", rowNum, "message", "sku and name are required"));
                    continue;
                }
                if (!"goods".equals(itemType) && !"service".equals(itemType)) {
                    errors.add(Map.of("row", rowNum, "message", "item_type must be goods or service"));
                    continue;
                }

                String tenantId = TenantContext.getTenantId();
                Integer existing = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM items WHERE tenant_id = ? AND sku = ?", Integer.class, tenantId, sku);
                if (existing != null && existing > 0) {
                    errors.add(Map.of("row", rowNum, "message", "sku \"" + sku + "\" already exists"));
                    continue;
                }

                try {
                    KeyHolder keyHolder = new GeneratedKeyHolder();
                    final String fSku = sku;
                    final String fType = itemType;
                    final BigDecimal fPur = purchasePrice;
                    final BigDecimal fSale = salePrice;
                    final BigDecimal fGst = gstRate;
                    final BigDecimal fOp = openingStock;
                    final BigDecimal fReo = reorderLevel;
                    final String fCls = itemClass;
                    final String fUom = uom;
                    final String fCat = category;
                    final String fBr = brand;
                    final String fSac = hsnSac;

                    jdbcTemplate.update(connection -> {
                        PreparedStatement ps = connection.prepareStatement(
                                "INSERT INTO items (tenant_id, sku, name, item_type, category, brand, hsn_sac, uom, purchase_price, sale_price, gst_rate, opening_stock, reorder_level, item_class, is_active, created_at) " +
                                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, NOW())",
                                Statement.RETURN_GENERATED_KEYS
                        );
                        ps.setString(1, tenantId);
                        ps.setString(2, fSku);
                        ps.setString(3, name);
                        ps.setString(4, fType);
                        ps.setString(5, fCat.isBlank() ? null : fCat);
                        ps.setString(6, fBr.isBlank() ? null : fBr);
                        ps.setString(7, fSac.isBlank() ? null : fSac);
                        ps.setString(8, fUom);
                        ps.setBigDecimal(9, fPur);
                        ps.setBigDecimal(10, fSale);
                        ps.setBigDecimal(11, fGst);
                        ps.setBigDecimal(12, fOp);
                        ps.setBigDecimal(13, fReo);
                        ps.setString(14, fCls);
                        return ps;
                    }, keyHolder);

                    long newId = Objects.requireNonNull(keyHolder.getKey()).longValue();
                    auditLogService.logAction("create", "item", String.valueOf(newId), null, Map.of("sku", sku, "name", name, "item_type", itemType), request);
                    created++;
                } catch (Exception ex) {
                    errors.add(Map.of("row", rowNum, "message", "Error saving row: " + ex.getMessage()));
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
                "moduleName", "Inventory & Services"
        ));
    }

    // 13. GET /inventory/direct-stock and GET /api/v1/inventory/direct-stock
    @GetMapping("/direct-stock")
    @PreAuthorize("hasAuthority('inventory_manage')")
    public ResponseEntity<?> getDirectStock() {
        String tenantId = TenantContext.getTenantId();
        boolean invEnabled = isInventoryEnabled();
        List<Map<String, Object>> entries = jdbcTemplate.queryForList(
                "SELECT * FROM direct_stock_entries WHERE tenant_id = ? ORDER BY period_year DESC, period_month DESC, item_name",
                tenantId
        );

        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> e : entries) {
            int year = ((Number) e.get("period_year")).intValue();
            int month = ((Number) e.get("period_month")).intValue();
            String key = String.format("%d-%02d", year, month);

            if (!grouped.containsKey(key)) {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("period_month", month);
                g.put("period_year", year);
                g.put("items", new ArrayList<Map<String, Object>>());
                g.put("openingTotal", BigDecimal.ZERO);
                g.put("closingTotal", BigDecimal.ZERO);
                grouped.put(key, g);
            }

            Map<String, Object> g = grouped.get(key);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> gItems = (List<Map<String, Object>>) g.get("items");
            gItems.add(e);

            BigDecimal opVal = bigDecimalVal(e.get("opening_value"));
            BigDecimal clVal = bigDecimalVal(e.get("closing_value"));
            g.put("openingTotal", ((BigDecimal) g.get("openingTotal")).add(opVal).setScale(2, RoundingMode.HALF_UP));
            g.put("closingTotal", ((BigDecimal) g.get("closingTotal")).add(clVal).setScale(2, RoundingMode.HALF_UP));
        }

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "title", "Direct Stock",
                "inventoryEnabled", invEnabled,
                "months", new ArrayList<>(grouped.values())
        ));
    }

    // 14. POST /inventory/direct-stock and POST /inventory/direct-stock/new
    @PostMapping({"/direct-stock", "/direct-stock/new"})
    @PreAuthorize("hasAuthority('inventory_manage')")
    public ResponseEntity<?> createDirectStockEntry(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String itemName = stringVal(body.get("item_name"));
        Integer month = body.get("period_month") != null ? Integer.parseInt(stringVal(body.get("period_month"))) : null;
        Integer year = body.get("period_year") != null ? Integer.parseInt(stringVal(body.get("period_year"))) : null;
        BigDecimal openingQty = body.get("opening_qty") != null && !stringVal(body.get("opening_qty")).isBlank()
                ? bigDecimalVal(body.get("opening_qty")) : null;
        BigDecimal openingValue = bigDecimalVal(body.get("opening_value"));
        BigDecimal closingQty = body.get("closing_qty") != null && !stringVal(body.get("closing_qty")).isBlank()
                ? bigDecimalVal(body.get("closing_qty")) : null;
        BigDecimal closingValue = bigDecimalVal(body.get("closing_value"));
        String notes = stringVal(body.get("notes"));

        if (itemName.isBlank() || month == null || year == null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Item name, month and year are required."));
        }
        if (month < 1 || month > 12) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Month must be between 1 and 12."));
        }
        if (year < 2000 || year > 2100) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Year must be between 2000 and 2100."));
        }

        String tenantId = TenantContext.getTenantId();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO direct_stock_entries (tenant_id, period_month, period_year, item_name, opening_qty, opening_value, closing_qty, closing_value, notes, created_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, tenantId);
            ps.setInt(2, month);
            ps.setInt(3, year);
            ps.setString(4, itemName);
            if (openingQty != null) ps.setBigDecimal(5, openingQty); else ps.setNull(5, java.sql.Types.DECIMAL);
            ps.setBigDecimal(6, openingValue.setScale(2, RoundingMode.HALF_UP));
            if (closingQty != null) ps.setBigDecimal(7, closingQty); else ps.setNull(7, java.sql.Types.DECIMAL);
            ps.setBigDecimal(8, closingValue.setScale(2, RoundingMode.HALF_UP));
            ps.setString(9, notes.isBlank() ? null : notes);
            return ps;
        }, keyHolder);

        long newId = Objects.requireNonNull(keyHolder.getKey()).longValue();
        auditLogService.logAction("create", "direct_stock_entry", String.valueOf(newId), null, body, request);

        return ResponseEntity.ok(Map.of("ok", true, "id", newId, "message", "Direct stock entry created."));
    }

    // 15. DELETE /inventory/direct-stock/{id} and POST /inventory/direct-stock/{id}/delete
    @RequestMapping(value = {"/direct-stock/{id}", "/direct-stock/{id}/delete"}, method = {RequestMethod.DELETE, RequestMethod.POST})
    @PreAuthorize("hasAuthority('inventory_manage')")
    public ResponseEntity<?> deleteDirectStockEntry(@PathVariable("id") long id, HttpServletRequest request) {
        String tenantId = TenantContext.getTenantId();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM direct_stock_entries WHERE tenant_id = ? AND id = ?", tenantId, id);
        if (rows.isEmpty()) {
            return ResponseEntity.ok(Map.of("ok", true, "message", "Direct stock entry already removed."));
        }
        Map<String, Object> entry = rows.get(0);
        jdbcTemplate.update("DELETE FROM direct_stock_entries WHERE tenant_id = ? AND id = ?", tenantId, id);
        auditLogService.logAction("delete", "direct_stock_entry", String.valueOf(id), entry, null, request);

        return ResponseEntity.ok(Map.of("ok", true, "message", "Direct stock entry deleted."));
    }

    // 16. GET /inventory/{id} and GET /inventory/{id}/edit
    @GetMapping({"/{id}", "/{id}/edit", "/items/{id}"})
    @PreAuthorize("hasAuthority('inventory_manage')")
    public ResponseEntity<?> getItem(@PathVariable("id") long id) {
        String tenantId = TenantContext.getTenantId();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM items WHERE tenant_id = ? AND id = ?", tenantId, id);
        if (rows.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", "Item not found."));
        }
        return ResponseEntity.ok(Map.of("ok", true, "item", rows.get(0), "data", rows.get(0)));
    }

    // 17. PUT /inventory/{id} and POST /inventory/{id} and POST /inventory/{id}/edit
    @RequestMapping(value = {"/{id}", "/{id}/edit", "/items/{id}"}, method = {RequestMethod.PUT, RequestMethod.POST})
    @PreAuthorize("hasAuthority('inventory_manage')")
    public ResponseEntity<?> updateItem(@PathVariable("id") long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        String tenantId = TenantContext.getTenantId();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM items WHERE tenant_id = ? AND id = ?", tenantId, id);
        if (rows.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", "Item not found."));
        }
        Map<String, Object> old = rows.get(0);
        boolean isService = "service".equalsIgnoreCase((String) old.get("item_type"));

        String name = stringVal(body.get("name"));
        String sku = isService ? (String) old.get("sku") : stringVal(body.get("sku"));
        BigDecimal salePrice = bigDecimalVal(body.get("sale_price"));
        BigDecimal gstRate = body.get("gst_rate") != null && !stringVal(body.get("gst_rate")).isBlank()
                ? bigDecimalVal(body.get("gst_rate")) : new BigDecimal("18.00");
        BigDecimal purchasePrice = isService ? BigDecimal.ZERO : bigDecimalVal(body.get("purchase_price"));
        BigDecimal reorderLevel = isService ? BigDecimal.ZERO : bigDecimalVal(body.get("reorder_level"));
        String hsnSac = stringVal(body.get("hsn_sac"));
        String category = isService ? (String) old.get("category") : stringVal(body.get("category"));
        String brand = isService ? (String) old.get("brand") : stringVal(body.get("brand"));
        String uom = isService ? "NOS" : (stringVal(body.get("uom")).isBlank() ? "NOS" : stringVal(body.get("uom")));
        String itemClass = (!isService && CLASSES.contains(stringVal(body.get("item_class"))))
                ? stringVal(body.get("item_class")) : (String) old.get("item_class");

        if (name.isBlank()) return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Enter the name."));
        if (sku.isBlank()) return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Enter the SKU."));
        if (salePrice.compareTo(BigDecimal.ZERO) < 0) return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Enter the rate / sale price (0 or more)."));
        if (!isService && purchasePrice.compareTo(BigDecimal.ZERO) < 0) return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Enter the purchase / cost price (0 or more)."));
        if (gstRate.compareTo(BigDecimal.ZERO) < 0 || gstRate.compareTo(new BigDecimal("100")) > 0) return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "GST rate must be between 0 and 100."));

        Integer skuClash = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM items WHERE tenant_id = ? AND LOWER(TRIM(sku)) = LOWER(TRIM(?)) AND id <> ?", Integer.class, tenantId, sku, id);
        if (skuClash != null && skuClash > 0) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Another item already uses this SKU."));
        }

        Integer nameClash = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM items WHERE tenant_id = ? AND LOWER(name) = LOWER(?) AND gst_rate = ? AND is_active = 1 AND id <> ?", Integer.class, tenantId, name, gstRate, id);
        if (nameClash != null && nameClash > 0) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Another item or service with this name and GST rate already exists."));
        }

        jdbcTemplate.update(
                "UPDATE items SET sku = ?, name = ?, hsn_sac = ?, gst_rate = ?, sale_price = ?, purchase_price = ?, " +
                        "category = ?, brand = ?, uom = ?, reorder_level = ?, item_class = ? WHERE tenant_id = ? AND id = ?",
                sku, name, hsnSac.isBlank() ? null : hsnSac, gstRate.setScale(2, RoundingMode.HALF_UP),
                salePrice.setScale(2, RoundingMode.HALF_UP), purchasePrice.setScale(2, RoundingMode.HALF_UP),
                (category == null || category.isBlank()) ? null : category, (brand == null || brand.isBlank()) ? null : brand,
                uom, reorderLevel.setScale(2, RoundingMode.HALF_UP), itemClass, tenantId, id
        );

        auditLogService.logAction("update", "item", String.valueOf(id),
                Map.of("name", old.get("name"), "sale_price", old.get("sale_price"), "gst_rate", old.get("gst_rate")),
                Map.of("name", name, "sale_price", salePrice, "gst_rate", gstRate), request);

        return ResponseEntity.ok(Map.of("ok", true, "message", (isService ? "Service" : "Item") + " \"" + name + "\" updated."));
    }

    // 18. DELETE /inventory/{id} and POST /inventory/{id}/delete
    @RequestMapping(value = {"/{id}", "/{id}/delete", "/items/{id}", "/items/{id}/delete"}, method = {RequestMethod.DELETE, RequestMethod.POST})
    @PreAuthorize("hasAuthority('inventory_manage')")
    public ResponseEntity<?> deleteItem(@PathVariable("id") long id, HttpServletRequest request) {
        String tenantId = TenantContext.getTenantId();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM items WHERE tenant_id = ? AND id = ?", tenantId, id);
        if (rows.isEmpty()) {
            return ResponseEntity.ok(Map.of("ok", true, "message", "Item already removed."));
        }
        Map<String, Object> item = rows.get(0);
        String name = (String) item.get("name");

        List<String> used = new ArrayList<>();
        checkUsage("invoice_lines", "item_id", id, "invoices", used);
        checkUsage("purchase_order_lines", "item_id", id, "purchase orders", used);
        checkUsage("sales_order_lines", "item_id", id, "sales orders", used);
        checkUsage("stock_ledger", "item_id", id, "stock movements", used);
        checkUsage("stock_lots", "item_id", id, "stock lots", used);
        checkUsage("stock_lot_consumptions", "item_id", id, "lot consumptions", used);
        checkUsage("stock_transfer_lines", "item_id", id, "stock transfers", used);

        if (!used.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "ok", false,
                    "error", "\"" + name + "\" cannot be deleted because it is already used in: " + String.join(", ", used) +
                            ". Use \"Remove from list\" instead: it hides it from new documents and keeps your records intact."
            ));
        }

        jdbcTemplate.update("DELETE FROM items WHERE tenant_id = ? AND id = ?", tenantId, id);
        auditLogService.logAction("delete", "item", String.valueOf(id), item, null, request);

        return ResponseEntity.ok(Map.of("ok", true, "message", "\"" + name + "\" deleted."));
    }

    // 19. POST /inventory/{id}/deactivate
    @PostMapping({"/{id}/deactivate", "/items/{id}/deactivate"})
    @PreAuthorize("hasAuthority('inventory_manage')")
    public ResponseEntity<?> deactivateItem(@PathVariable("id") long id, HttpServletRequest request) {
        String tenantId = TenantContext.getTenantId();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM items WHERE tenant_id = ? AND id = ?", tenantId, id);
        if (rows.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", "Item not found."));
        }
        Map<String, Object> item = rows.get(0);
        String name = (String) item.get("name");

        jdbcTemplate.update("UPDATE items SET is_active = 0 WHERE tenant_id = ? AND id = ?", tenantId, id);
        auditLogService.logAction("deactivate", "item", String.valueOf(id), item, null, request);

        return ResponseEntity.ok(Map.of("ok", true, "message", "\"" + name + "\" removed from the list. Past invoices are not affected."));
    }

    private void checkUsage(String tableName, String colName, long id, String label, List<String> used) {
        try {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName + " WHERE " + colName + " = ?", Integer.class, id);
            if (count != null && count > 0) {
                used.add(label + " (" + count + ")");
            }
        } catch (Exception ignored) {}
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

    private static BigDecimal parseDecimal(String s) {
        if (s == null || s.isBlank()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(s.trim());
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
