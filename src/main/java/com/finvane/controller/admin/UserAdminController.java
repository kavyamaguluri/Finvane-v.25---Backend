package com.finvane.controller.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finvane.service.audit.AuditLogService;
import com.finvane.service.auth.RbacService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasAuthority('users_manage') or hasAuthority('admin')")
public class UserAdminController {

    private final JdbcTemplate controlJdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public UserAdminController(@org.springframework.beans.factory.annotation.Qualifier("controlJdbcTemplate") JdbcTemplate controlJdbcTemplate,
                               PasswordEncoder passwordEncoder,
                               AuditLogService auditLogService) {
        this.controlJdbcTemplate = controlJdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
    }

    private Long getUserId(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return null;
        List<Long> ids = controlJdbcTemplate.query("SELECT id FROM users WHERE LOWER(email) = ?", (rs, rowNum) -> rs.getLong("id"), auth.getName().toLowerCase());
        return ids.isEmpty() ? null : ids.get(0);
    }

    // 1. GET /admin/users
    @GetMapping
    public ResponseEntity<?> getUsers(Authentication auth) {
        List<Map<String, Object>> users = controlJdbcTemplate.queryForList("""
            SELECT u.id, u.name, u.email, u.role, u.is_active, u.designation, u.mobile, u.email_verified,
                   u.must_change_password, u.totp_enabled, u.created_at
            FROM users u
            ORDER BY u.name ASC
        """);

        for (Map<String, Object> u : users) {
            long uid = ((Number) u.get("id")).longValue();
            List<String> entityNames = controlJdbcTemplate.query(
                "SELECT e.name FROM entities e JOIN user_entities ue ON ue.entity_id = e.id WHERE ue.user_id = ? ORDER BY e.name",
                (rs, rowNum) -> rs.getString("name"), uid
            );
            u.put("entities", entityNames);
        }

        return ResponseEntity.ok(Map.of("ok", true, "users", users));
    }

    // 2. GET /admin/users/form-options
    @GetMapping("/form-options")
    public ResponseEntity<?> getFormOptions() {
        List<Map<String, Object>> entities = controlJdbcTemplate.queryForList("SELECT id, name FROM entities WHERE is_active = 1 ORDER BY name");
        List<String> roles = RbacService.ALL_ROLES;
        List<String> permissions = RbacService.ALL_PERMISSIONS;

        return ResponseEntity.ok(Map.of(
            "ok", true,
            "roles", roles,
            "permissions", permissions,
            "entities", entities
        ));
    }

    // 3. POST /admin/users
    @PostMapping
    public ResponseEntity<?> createUser(@RequestBody Map<String, Object> body, Authentication auth) {
        Long currentUserId = getUserId(auth);
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        String email = String.valueOf(body.getOrDefault("email", "")).trim().toLowerCase();
        String role = String.valueOf(body.getOrDefault("role", "accountant")).trim();
        String designation = String.valueOf(body.getOrDefault("designation", "")).trim();
        String mobile = String.valueOf(body.getOrDefault("mobile", "")).trim();

        if (name.isBlank()) return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Name is required."));
        if (!email.matches("^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}$")) return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Valid email is required."));

        Integer existing = controlJdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE LOWER(email) = ?", Integer.class, email);
        if (existing != null && existing > 0) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Email is already registered."));
        }

        String initialPassword = "ChangeMe@12345";
        String hash = passwordEncoder.encode(initialPassword);

        // Permissions override JSON
        String permsJson = null;
        if (body.get("permissions") instanceof List<?> list && !list.isEmpty()) {
            try {
                permsJson = objectMapper.writeValueAsString(list);
            } catch (Exception ignored) {}
        }

        controlJdbcTemplate.update("""
            INSERT INTO users (name, email, password_hash, role, permissions_json, designation, mobile, is_active, email_verified, must_change_password)
            VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1, 1)
        """, name, email, hash, role, permsJson, designation.isEmpty() ? null : designation, mobile.isEmpty() ? null : mobile);

        Long newUserId = controlJdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        // Assign entities
        if (body.get("entityIds") instanceof List<?> entityIds) {
            for (Object eid : entityIds) {
                long entityId = Long.parseLong(String.valueOf(eid));
                controlJdbcTemplate.update("INSERT INTO user_entities (user_id, entity_id) VALUES (?, ?)", newUserId, entityId);
            }
        }

        auditLogService.logControlAudit(null, currentUserId, auth.getName(), "create", "user", newUserId, null, body);
        return ResponseEntity.ok(Map.of("ok", true, "userId", newUserId, "message", email + " added successfully."));
    }

    // 4. GET /admin/users/{id}
    @GetMapping("/{id}")
    public ResponseEntity<?> getUser(@PathVariable("id") long id) {
        List<Map<String, Object>> users = controlJdbcTemplate.queryForList("""
            SELECT id, name, email, role, permissions_json, designation, mobile, is_active FROM users WHERE id = ?
        """, id);
        if (users.isEmpty()) return ResponseEntity.notFound().build();

        Map<String, Object> u = users.get(0);
        List<Long> entityIds = controlJdbcTemplate.query(
            "SELECT entity_id FROM user_entities WHERE user_id = ?",
            (rs, rowNum) -> rs.getLong("entity_id"), id
        );
        u.put("entityIds", entityIds);

        return ResponseEntity.ok(Map.of("ok", true, "user", u));
    }

    // 5. PUT /admin/users/{id}
    @PutMapping("/{id}")
    public ResponseEntity<?> updateUser(@PathVariable("id") long id, @RequestBody Map<String, Object> body, Authentication auth) {
        Long currentUserId = getUserId(auth);
        Map<String, Object> old = controlJdbcTemplate.queryForMap("SELECT * FROM users WHERE id = ?", id);

        String name = String.valueOf(body.getOrDefault("name", old.get("name"))).trim();
        String role = String.valueOf(body.getOrDefault("role", old.get("role"))).trim();
        String designation = String.valueOf(body.getOrDefault("designation", old.get("designation"))).trim();
        String mobile = String.valueOf(body.getOrDefault("mobile", old.get("mobile"))).trim();

        String permsJson = null;
        if (body.get("permissions") instanceof List<?> list) {
            try {
                permsJson = objectMapper.writeValueAsString(list);
            } catch (Exception ignored) {}
        }

        controlJdbcTemplate.update("""
            UPDATE users SET name = ?, role = ?, designation = ?, mobile = ?, permissions_json = ? WHERE id = ?
        """, name, role, designation.isEmpty() ? null : designation, mobile.isEmpty() ? null : mobile, permsJson, id);

        if (body.get("entityIds") instanceof List<?> entityIds) {
            controlJdbcTemplate.update("DELETE FROM user_entities WHERE user_id = ?", id);
            for (Object eid : entityIds) {
                long entityId = Long.parseLong(String.valueOf(eid));
                controlJdbcTemplate.update("INSERT INTO user_entities (user_id, entity_id) VALUES (?, ?)", id, entityId);
            }
        }

        auditLogService.logControlAudit(null, currentUserId, auth.getName(), "update", "user", id, old, body);
        return ResponseEntity.ok(Map.of("ok", true, "message", "User updated successfully."));
    }

    // 6. DELETE /admin/users/{id}
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable("id") long id, Authentication auth) {
        Long currentUserId = getUserId(auth);
        if (currentUserId != null && currentUserId.equals(id)) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "You cannot delete your own account."));
        }

        Map<String, Object> u = controlJdbcTemplate.queryForMap("SELECT role, email FROM users WHERE id = ?", id);
        if ("admin".equalsIgnoreCase(String.valueOf(u.get("role")))) {
            Integer adminCount = controlJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE role = 'admin' AND is_active = 1", Integer.class
            );
            if (adminCount != null && adminCount <= 1) {
                return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "This is the only active admin."));
            }
        }

        Integer auditCount = controlJdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE user_id = ?", Integer.class, id);
        if (auditCount != null && auditCount > 0) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "User has entries in the audit log. Deactivate instead."));
        }

        controlJdbcTemplate.update("DELETE FROM user_entities WHERE user_id = ?", id);
        controlJdbcTemplate.update("DELETE FROM users WHERE id = ?", id);

        auditLogService.logControlAudit(null, currentUserId, auth.getName(), "delete", "user", id, u, null);
        return ResponseEntity.ok(Map.of("ok", true, "message", "User deleted successfully."));
    }

    // 7. POST /admin/users/{id}/toggle
    @PostMapping("/{id}/toggle")
    public ResponseEntity<?> toggleUser(@PathVariable("id") long id, Authentication auth) {
        Long currentUserId = getUserId(auth);
        if (currentUserId != null && currentUserId.equals(id)) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "You cannot deactivate your own account."));
        }

        Map<String, Object> u = controlJdbcTemplate.queryForMap("SELECT is_active, role FROM users WHERE id = ?", id);
        boolean currentActive = u.get("is_active") != null && !u.get("is_active").equals(0) && (u.get("is_active").equals(true) || "1".equals(String.valueOf(u.get("is_active"))));

        if (currentActive && "admin".equalsIgnoreCase(String.valueOf(u.get("role")))) {
            Integer adminCount = controlJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE role = 'admin' AND is_active = 1", Integer.class
            );
            if (adminCount != null && adminCount <= 1) {
                return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "This is the only active admin."));
            }
        }

        int newActive = currentActive ? 0 : 1;
        controlJdbcTemplate.update("UPDATE users SET is_active = ? WHERE id = ?", newActive, id);
        auditLogService.logControlAudit(null, currentUserId, auth.getName(), "toggle_status", "user", id, Map.of("is_active", currentActive), Map.of("is_active", newActive));

        return ResponseEntity.ok(Map.of("ok", true, "isActive", newActive == 1, "message", newActive == 1 ? "User activated." : "User deactivated."));
    }

    // 8. GET /admin/users/export/template
    @GetMapping("/export/template")
    public ResponseEntity<byte[]> downloadTemplate() throws Exception {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Users Template");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Name");
            header.createCell(1).setCellValue("Email");
            header.createCell(2).setCellValue("Role");
            header.createCell(3).setCellValue("Designation");
            header.createCell(4).setCellValue("Mobile");

            Row sample = sheet.createRow(1);
            sample.createCell(0).setCellValue("Jane Doe");
            sample.createCell(1).setCellValue("jane.doe@example.com");
            sample.createCell(2).setCellValue("accountant");
            sample.createCell(3).setCellValue("Senior Accountant");
            sample.createCell(4).setCellValue("+91 9876543210");

            for (int i = 0; i < 5; i++) sheet.autoSizeColumn(i);

            workbook.write(out);
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=users_template.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(out.toByteArray());
        }
    }

    // 9. GET /admin/users/export
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportUsers() throws Exception {
        List<Map<String, Object>> users = controlJdbcTemplate.queryForList("""
            SELECT id, name, email, role, designation, mobile, is_active FROM users ORDER BY name
        """);

        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Users");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("ID");
            header.createCell(1).setCellValue("Name");
            header.createCell(2).setCellValue("Email");
            header.createCell(3).setCellValue("Role");
            header.createCell(4).setCellValue("Designation");
            header.createCell(5).setCellValue("Mobile");
            header.createCell(6).setCellValue("Status");

            int rowIdx = 1;
            for (Map<String, Object> u : users) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(String.valueOf(u.get("id")));
                row.createCell(1).setCellValue(String.valueOf(u.get("name")));
                row.createCell(2).setCellValue(String.valueOf(u.get("email")));
                row.createCell(3).setCellValue(String.valueOf(u.get("role")));
                row.createCell(4).setCellValue(u.get("designation") != null ? String.valueOf(u.get("designation")) : "");
                row.createCell(5).setCellValue(u.get("mobile") != null ? String.valueOf(u.get("mobile")) : "");
                boolean act = u.get("is_active") != null && !u.get("is_active").equals(0);
                row.createCell(6).setCellValue(act ? "Active" : "Inactive");
            }

            for (int i = 0; i < 7; i++) sheet.autoSizeColumn(i);

            workbook.write(out);
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=users_export.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(out.toByteArray());
        }
    }

    // 10. POST /admin/users/import
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importUsers(@RequestParam("file") MultipartFile file, Authentication auth) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Please select an Excel file to import."));
        }

        int count = 0;
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                Cell nameCell = row.getCell(0);
                Cell emailCell = row.getCell(1);
                Cell roleCell = row.getCell(2);
                if (nameCell == null || emailCell == null) continue;

                String name = nameCell.getStringCellValue().trim();
                String email = emailCell.getStringCellValue().trim().toLowerCase();
                String role = roleCell != null ? roleCell.getStringCellValue().trim().toLowerCase() : "accountant";

                if (name.isEmpty() || email.isEmpty()) continue;

                Integer exists = controlJdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE LOWER(email) = ?", Integer.class, email);
                if (exists == null || exists == 0) {
                    String hash = passwordEncoder.encode("ChangeMe@12345");
                    controlJdbcTemplate.update("""
                        INSERT INTO users (name, email, password_hash, role, is_active, email_verified, must_change_password)
                        VALUES (?, ?, ?, ?, 1, 1, 1)
                    """, name, email, hash, role);
                    count++;
                }
            }
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("ok", false, "error", "Failed to parse import file: " + e.getMessage()));
        }

        return ResponseEntity.ok(Map.of("ok", true, "imported", count, "message", "Imported " + count + " new user(s)."));
    }
}
