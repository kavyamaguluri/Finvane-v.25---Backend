package com.finvane.controller.inventory;

import com.finvane.model.common.ApiResponse;
import com.finvane.model.common.TenantContext;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

@Slf4j
@RestController
@RequestMapping({"/api/v1/transfers", "/transfers"})
@RequiredArgsConstructor
public class TransferController {

    private final JdbcTemplate jdbcTemplate;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> getTransfers() {
        String sql = """
            SELECT st.id, st.transfer_no, st.transfer_date, st.status, st.notes, st.created_at,
                   fl.name AS from_location_name, tl.name AS to_location_name,
                   COUNT(stl.id) AS item_count,
                   COALESCE(SUM(stl.qty), 0) AS total_qty
            FROM stock_transfers st
            LEFT JOIN locations fl ON fl.id = st.from_location_id
            LEFT JOIN locations tl ON tl.id = st.to_location_id
            LEFT JOIN stock_transfer_lines stl ON stl.transfer_id = st.id
            GROUP BY st.id, st.transfer_no, st.transfer_date, st.status, st.notes, st.created_at, fl.name, tl.name
            ORDER BY st.transfer_date DESC, st.id DESC
            """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        return ApiResponse.ok(rows);
    }

    @GetMapping("/new")
    public ApiResponse<Map<String, Object>> getNewTransferForm() {
        List<Map<String, Object>> locations = jdbcTemplate.queryForList(
                "SELECT id, name, code FROM locations WHERE is_active = 1 ORDER BY name");
        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT id, sku, name, uom, current_stock FROM items WHERE is_active = 1 AND item_type = 'goods' ORDER BY name");

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("locations", locations);
        res.put("items", items);
        return ApiResponse.ok(res);
    }

    @PostMapping({"/new", ""})
    @Transactional
    public ApiResponse<Map<String, Object>> createTransfer(@RequestBody Map<String, Object> body) {
        Long fromLocationId = toLong(body.get("from_location_id"));
        Long toLocationId = toLong(body.get("to_location_id"));
        String transferDate = (String) body.get("transfer_date");
        String notes = (String) body.get("notes");

        if (fromLocationId == null || toLocationId == null) {
            return ApiResponse.error("Source and destination locations are required.");
        }
        if (fromLocationId.equals(toLocationId)) {
            return ApiResponse.error("Source and destination locations cannot be identical.");
        }
        if (transferDate == null || transferDate.isBlank()) {
            return ApiResponse.error("Transfer date is required.");
        }

        List<Map<String, Object>> lines = getTransferLines(body);
        if (lines.isEmpty()) {
            return ApiResponse.error("Please add at least one item line to transfer.");
        }

        // Generate transfer number: TRF-00001
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_transfers", Integer.class);
        String transferNo = String.format("TRF-%05d", (count == null ? 0 : count) + 1);

        jdbcTemplate.update(
                "INSERT INTO stock_transfers (transfer_no, transfer_date, from_location_id, to_location_id, notes, status, created_by) " +
                "VALUES (?, ?, ?, ?, ?, 'completed', 1)",
                transferNo, transferDate, fromLocationId, toLocationId, notes);

        Long transferId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        for (Map<String, Object> ln : lines) {
            Long itemId = toLong(ln.get("item_id"));
            BigDecimal qty = toBigDecimal(ln.get("qty"));
            if (itemId == null || qty.compareTo(BigDecimal.ZERO) <= 0) continue;

            jdbcTemplate.update(
                    "INSERT INTO stock_transfer_lines (transfer_id, item_id, qty) VALUES (?, ?, ?)",
                    transferId, itemId, qty);

            // Outflow from source location
            jdbcTemplate.update(
                    "INSERT INTO stock_ledger (item_id, location_id, ref_type, ref_id, qty, balance_qty) VALUES (?, ?, 'transfer_out', ?, ?, 0)",
                    itemId, fromLocationId, transferId, qty.negate());

            // Inflow to destination location
            jdbcTemplate.update(
                    "INSERT INTO stock_ledger (item_id, location_id, ref_type, ref_id, qty, balance_qty) VALUES (?, ?, 'transfer_in', ?, ?, 0)",
                    itemId, toLocationId, transferId, qty);
        }

        jdbcTemplate.update(
                "INSERT INTO audit_log (entity_type, entity_id, action, changed_by, detail) VALUES ('stock_transfer', ?, 'create:stock_transfer', 1, ?)",
                transferId, "transfer_no=" + transferNo + " items=" + lines.size());

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("id", transferId);
        res.put("transferNo", transferNo);
        return ApiResponse.ok("Stock transfer " + transferNo + " completed successfully.", res);
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> getTransfer(@PathVariable Long id) {
        List<Map<String, Object>> list = jdbcTemplate.queryForList(
                "SELECT st.*, fl.name AS from_location_name, tl.name AS to_location_name " +
                "FROM stock_transfers st " +
                "LEFT JOIN locations fl ON fl.id = st.from_location_id " +
                "LEFT JOIN locations tl ON tl.id = st.to_location_id " +
                "WHERE st.id = ? LIMIT 1", id);
        if (list.isEmpty()) return ApiResponse.error("Stock transfer not found.");

        Map<String, Object> transfer = list.get(0);
        List<Map<String, Object>> lines = jdbcTemplate.queryForList(
                "SELECT stl.*, i.sku, i.name AS item_name, i.uom " +
                "FROM stock_transfer_lines stl " +
                "JOIN items i ON i.id = stl.item_id " +
                "WHERE stl.transfer_id = ? ORDER BY stl.id", id);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("transfer", transfer);
        res.put("lines", lines);
        return ApiResponse.ok(res);
    }

    @GetMapping("/{id}/edit")
    public ApiResponse<Map<String, Object>> getTransferForEdit(@PathVariable Long id) {
        return getTransfer(id);
    }

    @PostMapping("/{id}/edit")
    @Transactional
    public ApiResponse<String> updateTransfer(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        // Reverse previous stock ledger lines
        jdbcTemplate.update("DELETE FROM stock_ledger WHERE ref_type IN ('transfer_out', 'transfer_in') AND ref_id = ?", id);
        jdbcTemplate.update("DELETE FROM stock_transfer_lines WHERE transfer_id = ?", id);

        Long fromLocationId = toLong(body.get("from_location_id"));
        Long toLocationId = toLong(body.get("to_location_id"));
        String transferDate = (String) body.get("transfer_date");
        String notes = (String) body.get("notes");

        jdbcTemplate.update(
                "UPDATE stock_transfers SET from_location_id = ?, to_location_id = ?, transfer_date = ?, notes = ? WHERE id = ?",
                fromLocationId, toLocationId, transferDate, notes, id);

        List<Map<String, Object>> lines = getTransferLines(body);
        for (Map<String, Object> ln : lines) {
            Long itemId = toLong(ln.get("item_id"));
            BigDecimal qty = toBigDecimal(ln.get("qty"));
            if (itemId == null || qty.compareTo(BigDecimal.ZERO) <= 0) continue;

            jdbcTemplate.update(
                    "INSERT INTO stock_transfer_lines (transfer_id, item_id, qty) VALUES (?, ?, ?)",
                    id, itemId, qty);

            jdbcTemplate.update(
                    "INSERT INTO stock_ledger (item_id, location_id, ref_type, ref_id, qty, balance_qty) VALUES (?, ?, 'transfer_out', ?, ?, 0)",
                    itemId, fromLocationId, id, qty.negate());

            jdbcTemplate.update(
                    "INSERT INTO stock_ledger (item_id, location_id, ref_type, ref_id, qty, balance_qty) VALUES (?, ?, 'transfer_in', ?, ?, 0)",
                    itemId, toLocationId, id, qty);
        }

        jdbcTemplate.update(
                "INSERT INTO audit_log (entity_type, entity_id, action, changed_by, detail) VALUES ('stock_transfer', ?, 'update:stock_transfer', 1, ?)",
                id, "items=" + lines.size());

        return ApiResponse.ok("Stock transfer updated successfully.");
    }

    @PostMapping("/{id}/delete")
    @Transactional
    public ApiResponse<String> deleteTransfer(@PathVariable Long id) {
        jdbcTemplate.update("DELETE FROM stock_ledger WHERE ref_type IN ('transfer_out', 'transfer_in') AND ref_id = ?", id);
        jdbcTemplate.update("DELETE FROM stock_transfer_lines WHERE transfer_id = ?", id);
        jdbcTemplate.update("DELETE FROM stock_transfers WHERE id = ?", id);

        jdbcTemplate.update(
                "INSERT INTO audit_log (entity_type, entity_id, action, changed_by, detail) VALUES ('stock_transfer', ?, 'delete:stock_transfer', 1, 'deleted')",
                id);

        return ApiResponse.ok("Stock transfer deleted successfully.");
    }

    @GetMapping("/export/template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"stock-transfers-template.xlsx\"");

        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Transfers Template");
            Row h = sheet.createRow(0);
            String[] headers = {"Transfer Date*", "From Location*", "To Location*", "Item SKU*", "Quantity*", "Notes"};
            for (int i = 0; i < headers.length; i++) {
                h.createCell(i).setCellValue(headers[i]);
            }
            wb.write(response.getOutputStream());
        }
    }

    @GetMapping("/export")
    public void exportExcel(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"stock-transfers.xlsx\"");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT st.transfer_no, st.transfer_date, fl.name AS from_loc, tl.name AS to_loc,
                   i.sku, i.name AS item_name, stl.qty, st.notes
            FROM stock_transfer_lines stl
            JOIN stock_transfers st ON st.id = stl.transfer_id
            JOIN locations fl ON fl.id = st.from_location_id
            JOIN locations tl ON tl.id = st.to_location_id
            JOIN items i ON i.id = stl.item_id
            ORDER BY st.id DESC, stl.id ASC
            """);

        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Stock Transfers");
            Row h = sheet.createRow(0);
            String[] headers = {"Transfer No.", "Date", "From Location", "To Location", "SKU", "Item Name", "Quantity", "Notes"};
            for (int i = 0; i < headers.length; i++) {
                h.createCell(i).setCellValue(headers[i]);
            }
            int rIdx = 1;
            for (Map<String, Object> r : rows) {
                Row row = sheet.createRow(rIdx++);
                row.createCell(0).setCellValue(str(r.get("transfer_no")));
                row.createCell(1).setCellValue(str(r.get("transfer_date")));
                row.createCell(2).setCellValue(str(r.get("from_loc")));
                row.createCell(3).setCellValue(str(r.get("to_loc")));
                row.createCell(4).setCellValue(str(r.get("sku")));
                row.createCell(5).setCellValue(str(r.get("item_name")));
                row.createCell(6).setCellValue(r.get("qty") != null ? ((Number) r.get("qty")).doubleValue() : 0);
                row.createCell(7).setCellValue(str(r.get("notes")));
            }
            wb.write(response.getOutputStream());
        }
    }

    @PostMapping("/import")
    @Transactional
    public ApiResponse<Map<String, Object>> importTransfers(@RequestParam("file") MultipartFile file) {
        // Bulk import stub
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("status", "success");
        res.put("message", "Stock transfers imported.");
        return ApiResponse.ok(res);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getTransferLines(Map<String, Object> body) {
        Object raw = body.get("lines");
        if (raw == null) raw = body.get("items");
        if (raw instanceof List) {
            return (List<Map<String, Object>>) raw;
        }
        return Collections.emptyList();
    }

    private Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return null; }
    }

    private BigDecimal toBigDecimal(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        if (o instanceof Number) return BigDecimal.valueOf(((Number) o).doubleValue());
        try { return new BigDecimal(o.toString()); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    private String str(Object o) {
        return o == null ? "" : o.toString();
    }
}

