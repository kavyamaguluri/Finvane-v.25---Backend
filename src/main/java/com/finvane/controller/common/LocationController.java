package com.finvane.controller.common;

import com.finvane.model.common.TenantContext;
import com.finvane.service.audit.AuditLogService;
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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;

@RestController
@RequestMapping({"/api/v1/locations", "/locations"})
@PreAuthorize("hasAuthority('locations_manage')")
public class LocationController {

    private final JdbcTemplate jdbcTemplate;
    private final AuditLogService auditLogService;

    private static final List<String> VALID_TYPES = List.of("warehouse", "branch", "office", "retail_store");
    private static final String[] HEADERS = {
            "code", "name", "location_type (warehouse/branch/office/retail_store)", "state_code", "gstin", "address"
    };

    public LocationController(JdbcTemplate jdbcTemplate, AuditLogService auditLogService) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditLogService = auditLogService;
    }

    // 1. GET /locations and GET /api/v1/locations
    @GetMapping
    public ResponseEntity<?> listLocations() {
        String tenantId = TenantContext.getTenantId();
        List<Map<String, Object>> locations = jdbcTemplate.queryForList(
                "SELECT * FROM locations WHERE tenant_id = ? ORDER BY code", tenantId);
        return ResponseEntity.ok(Map.of("ok", true, "locations", locations, "data", locations));
    }

    // 2. GET /locations/new and GET /api/v1/locations/form-options
    @GetMapping({"/new", "/form-options"})
    public ResponseEntity<?> getLocationFormOptions() {
        List<Map<String, String>> types = List.of(
                Map.of("code", "warehouse", "label", "Warehouse"),
                Map.of("code", "branch", "label", "Branch"),
                Map.of("code", "office", "label", "Office"),
                Map.of("code", "retail_store", "label", "Retail Store")
        );
        return ResponseEntity.ok(Map.of("ok", true, "locationTypes", types));
    }

    // 3. POST /locations and POST /locations/new and POST /api/v1/locations
    @PostMapping({"", "/new"})
    public ResponseEntity<?> createLocation(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String code = stringVal(body.get("code"));
        String name = stringVal(body.get("name"));
        String rawLocationType = stringVal(body.containsKey("location_type") ? body.get("location_type") : body.get("locationType"));
        String locationType = rawLocationType.isBlank() ? "branch" : rawLocationType;
        String address = stringVal(body.get("address"));
        String stateCode = stringVal(body.containsKey("state_code") ? body.get("state_code") : body.get("stateCode"));
        String gstin = stringVal(body.get("gstin"));

        if (code.isBlank() || name.isBlank() || stateCode.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "code, name and state_code are required."));
        }
        if (!VALID_TYPES.contains(locationType.toLowerCase())) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "location_type must be warehouse, branch, office, or retail_store."));
        }

        String tenantId = TenantContext.getTenantId();
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM locations WHERE tenant_id = ? AND LOWER(TRIM(code)) = LOWER(TRIM(?))", Integer.class, tenantId, code);
        if (count != null && count > 0) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Location code must be unique."));
        }

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO locations (tenant_id, code, name, location_type, address, state_code, gstin, is_active, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, 1, NOW())",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, tenantId);
            ps.setString(2, code);
            ps.setString(3, name);
            ps.setString(4, locationType.toLowerCase());
            ps.setString(5, address.isBlank() ? null : address);
            ps.setString(6, stateCode);
            ps.setString(7, gstin.isBlank() ? null : gstin);
            return ps;
        }, keyHolder);

        long newId = Objects.requireNonNull(keyHolder.getKey()).longValue();
        auditLogService.logAction("create", "location", String.valueOf(newId), null, body, request);

        Map<String, Object> created = new LinkedHashMap<>();
        created.put("id", newId);
        created.put("code", code);
        created.put("name", name);
        created.put("location_type", locationType.toLowerCase());
        created.put("state_code", stateCode);
        created.put("stateCode", stateCode);
        created.put("address", address);
        created.put("gstin", gstin);
        return ResponseEntity.ok(Map.of("ok", true, "id", newId, "data", created, "message", "Location created successfully."));
    }

    // 4. GET /locations/{id} and GET /locations/{id}/edit
    @GetMapping({"/{id}", "/{id}/edit"})
    public ResponseEntity<?> getLocation(@PathVariable("id") long id) {
        String tenantId = TenantContext.getTenantId();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM locations WHERE tenant_id = ? AND id = ?", tenantId, id);
        if (rows.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", "Location not found."));
        }
        return ResponseEntity.ok(Map.of("ok", true, "location", rows.get(0)));
    }

    // 5. PUT /locations/{id} and POST /locations/{id}/edit and POST /locations/{id}
    @RequestMapping(value = {"/{id}", "/{id}/edit"}, method = {RequestMethod.PUT, RequestMethod.POST})
    public ResponseEntity<?> updateLocation(@PathVariable("id") long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        String tenantId = TenantContext.getTenantId();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM locations WHERE tenant_id = ? AND id = ?", tenantId, id);
        if (rows.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", "Location not found."));
        }
        Map<String, Object> old = rows.get(0);

        String code = stringVal(body.get("code"));
        String name = stringVal(body.get("name"));
        String rawLocationType = stringVal(body.containsKey("location_type") ? body.get("location_type") : body.get("locationType"));
        String locationType = rawLocationType.isBlank() ? "branch" : rawLocationType;
        String address = stringVal(body.get("address"));
        String stateCode = stringVal(body.containsKey("state_code") ? body.get("state_code") : body.get("stateCode"));
        String gstin = stringVal(body.get("gstin"));

        if (code.isBlank() || name.isBlank() || stateCode.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "code, name and state_code are required."));
        }
        if (!VALID_TYPES.contains(locationType.toLowerCase())) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "location_type must be warehouse, branch, office, or retail_store."));
        }

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM locations WHERE tenant_id = ? AND LOWER(TRIM(code)) = LOWER(TRIM(?)) AND id <> ?", Integer.class, tenantId, code, id);
        if (count != null && count > 0) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Location code must be unique."));
        }

        jdbcTemplate.update(
                "UPDATE locations SET code = ?, name = ?, location_type = ?, address = ?, state_code = ?, gstin = ? WHERE tenant_id = ? AND id = ?",
                code, name, locationType.toLowerCase(), address.isBlank() ? null : address, stateCode, gstin.isBlank() ? null : gstin, tenantId, id
        );

        auditLogService.logAction("update", "location", String.valueOf(id), old, body, request);
        return ResponseEntity.ok(Map.of("ok", true, "message", "Location updated successfully."));
    }

    // 6. DELETE /locations/{id} and POST /locations/{id}/delete
    @RequestMapping(value = {"/{id}", "/{id}/delete"}, method = {RequestMethod.DELETE, RequestMethod.POST})
    public ResponseEntity<?> deleteLocation(@PathVariable("id") long id, HttpServletRequest request) {
        String tenantId = TenantContext.getTenantId();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM locations WHERE tenant_id = ? AND id = ?", tenantId, id);
        if (rows.isEmpty()) {
            return ResponseEntity.ok(Map.of("ok", true, "message", "Location already removed."));
        }
        Map<String, Object> loc = rows.get(0);
        String name = (String) loc.get("name");

        boolean used = false;
        try {
            Integer stockCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE tenant_id = ? AND location_id = ?", Integer.class, tenantId, id);
            if (stockCount != null && stockCount > 0) used = true;
        } catch (Exception ignored) {}

        if (!used) {
            try {
                Integer invCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoices WHERE tenant_id = ? AND location_id = ?", Integer.class, tenantId, id);
                if (invCount != null && invCount > 0) used = true;
            } catch (Exception ignored) {}
        }

        if (!used) {
            try {
                Integer trCount = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stock_transfers WHERE tenant_id = ? AND (from_location_id = ? OR to_location_id = ?)", Integer.class, tenantId, id, id
                );
                if (trCount != null && trCount > 0) used = true;
            } catch (Exception ignored) {}
        }

        if (used) {
            return ResponseEntity.badRequest().body(Map.of(
                    "ok", false,
                    "error", "\"" + name + "\" has stock or transactions, so it cannot be deleted. Use Deactivate instead."
            ));
        }

        jdbcTemplate.update("DELETE FROM locations WHERE tenant_id = ? AND id = ?", tenantId, id);
        auditLogService.logAction("delete", "location", String.valueOf(id), loc, null, request);

        return ResponseEntity.ok(Map.of("ok", true, "message", "\"" + name + "\" deleted."));
    }

    // 7. POST /locations/{id}/toggle
    @PostMapping("/{id}/toggle")
    public ResponseEntity<?> toggleLocation(@PathVariable("id") long id, HttpServletRequest request) {
        String tenantId = TenantContext.getTenantId();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM locations WHERE tenant_id = ? AND id = ?", tenantId, id);
        if (rows.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", "Location not found."));
        }
        Map<String, Object> loc = rows.get(0);
        Number activeNum = (Number) loc.get("is_active");
        int newActive = (activeNum != null && activeNum.intValue() == 1) ? 0 : 1;

        jdbcTemplate.update("UPDATE locations SET is_active = ? WHERE tenant_id = ? AND id = ?", newActive, tenantId, id);
        auditLogService.logAction("toggle_active", "location", String.valueOf(id), loc, Map.of("is_active", newActive), request);

        return ResponseEntity.ok(Map.of("ok", true, "is_active", newActive, "message", "Location status updated."));
    }

    // 8. GET /locations/export/template and GET /locations/template
    @GetMapping({"/export/template", "/template"})
    public ResponseEntity<byte[]> downloadLocationTemplate() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Locations Template");
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(HEADERS[i]);
            }

            Row exampleRow = sheet.createRow(1);
            exampleRow.createCell(0).setCellValue("LOC-MUM");
            exampleRow.createCell(1).setCellValue("Mumbai Central Warehouse");
            exampleRow.createCell(2).setCellValue("warehouse");
            exampleRow.createCell(3).setCellValue("27");
            exampleRow.createCell(4).setCellValue("27AAACB1234F1Z5");
            exampleRow.createCell(5).setCellValue("Plot 12, MIDC Andheri, Mumbai");

            for (int i = 0; i < HEADERS.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(out);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=locations-template.xlsx")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // 9. GET /locations/export
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportLocationExcel() {
        String tenantId = TenantContext.getTenantId();
        List<Map<String, Object>> locations = jdbcTemplate.queryForList("SELECT * FROM locations WHERE tenant_id = ? ORDER BY code", tenantId);
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Locations");
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(HEADERS[i]);
            }

            int rowIdx = 1;
            for (Map<String, Object> l : locations) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(stringVal(l.get("code")));
                row.createCell(1).setCellValue(stringVal(l.get("name")));
                row.createCell(2).setCellValue(stringVal(l.get("location_type")));
                row.createCell(3).setCellValue(stringVal(l.get("state_code")));
                row.createCell(4).setCellValue(stringVal(l.get("gstin")));
                row.createCell(5).setCellValue(stringVal(l.get("address")));
            }

            for (int i = 0; i < HEADERS.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(out);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=locations.xlsx")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // 10. POST /locations/import
    @PostMapping("/import")
    public ResponseEntity<?> importLocations(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
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

                String code = getCellValue(row.getCell(0)).trim();
                String name = getCellValue(row.getCell(1)).trim();
                String type = getCellValue(row.getCell(2)).trim().toLowerCase();
                String stateCode = getCellValue(row.getCell(3)).trim();
                String gstin = getCellValue(row.getCell(4)).trim();
                String address = getCellValue(row.getCell(5)).trim();

                int rowNum = r + 1;
                if (code.isBlank() || name.isBlank() || stateCode.isBlank()) {
                    errors.add(Map.of("row", rowNum, "message", "code, name and state_code are required"));
                    continue;
                }
                if (type.isBlank()) type = "warehouse";
                if (!VALID_TYPES.contains(type)) {
                    errors.add(Map.of("row", rowNum, "message", "location_type must be one of " + String.join(", ", VALID_TYPES)));
                    continue;
                }

                String tenantId = TenantContext.getTenantId();
                Integer existing = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM locations WHERE tenant_id = ? AND code = ?", Integer.class, tenantId, code);
                if (existing != null && existing > 0) {
                    errors.add(Map.of("row", rowNum, "message", "location code \"" + code + "\" already exists"));
                    continue;
                }

                try {
                    KeyHolder keyHolder = new GeneratedKeyHolder();
                    final String fType = type;
                    jdbcTemplate.update(connection -> {
                        PreparedStatement ps = connection.prepareStatement(
                                "INSERT INTO locations (tenant_id, code, name, location_type, state_code, address, gstin, is_active, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, 1, NOW())",
                                Statement.RETURN_GENERATED_KEYS
                        );
                        ps.setString(1, tenantId);
                        ps.setString(2, code);
                        ps.setString(3, name);
                        ps.setString(4, fType);
                        ps.setString(5, stateCode);
                        ps.setString(6, address.isBlank() ? null : address);
                        ps.setString(7, gstin.isBlank() ? null : gstin);
                        return ps;
                    }, keyHolder);

                    long newId = Objects.requireNonNull(keyHolder.getKey()).longValue();
                    auditLogService.logAction("create", "location", String.valueOf(newId), null, Map.of("code", code, "name", name), request);
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
                "moduleName", "Locations"
        ));
    }

    private static String stringVal(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
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
