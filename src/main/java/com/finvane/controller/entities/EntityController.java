package com.finvane.controller.entities;

import com.finvane.config.TenantContext;
import com.finvane.service.audit.AuditLogService;
import com.finvane.service.auth.RbacService;
import com.finvane.service.settings.TenantSettingsService;
import com.finvane.service.tenant.TenantProvisionerService;
import com.finvane.utils.JwtUtility;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class EntityController {

    private final JdbcTemplate controlJdbcTemplate;
    private final TenantProvisionerService tenantProvisionerService;
    private final JwtUtility jwtUtility;
    private final RbacService rbacService;
    private final AuditLogService auditLogService;
    private final TenantSettingsService tenantSettingsService;

    public EntityController(JdbcTemplate controlJdbcTemplate,
                            TenantProvisionerService tenantProvisionerService,
                            JwtUtility jwtUtility,
                            RbacService rbacService,
                            AuditLogService auditLogService,
                            TenantSettingsService tenantSettingsService) {
        this.controlJdbcTemplate = controlJdbcTemplate;
        this.tenantProvisionerService = tenantProvisionerService;
        this.jwtUtility = jwtUtility;
        this.rbacService = rbacService;
        this.auditLogService = auditLogService;
        this.tenantSettingsService = tenantSettingsService;
    }

    private Long getUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return null;
        String email = authentication.getName().toLowerCase();
        List<Long> ids = controlJdbcTemplate.query("SELECT id FROM users WHERE LOWER(email) = ?", (rs, rowNum) -> rs.getLong("id"), email);
        return ids.isEmpty() ? null : ids.get(0);
    }

    // 1. GET /setup
    @GetMapping("/setup")
    public ResponseEntity<?> getSetupStatus() {
        Integer userCount = controlJdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        boolean isConfigured = userCount != null && userCount > 0;
        return ResponseEntity.ok(Map.of("ok", true, "configured", isConfigured));
    }

    // 2. POST /setup
    @PostMapping("/setup")
    public ResponseEntity<?> initialSetup(@RequestBody Map<String, Object> body) {
        Integer userCount = controlJdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        if (userCount != null && userCount > 0) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Initial setup has already been completed."));
        }
        // Delegates to registration flow
        return ResponseEntity.ok(Map.of("ok", true, "message", "Ready for admin registration."));
    }

    // 3. GET /entities
    @GetMapping("/entities")
    public ResponseEntity<?> listEntities(Authentication authentication) {
        Long userId = getUserId(authentication);
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("ok", false, "error", "Unauthorized"));
        }

        String email = authentication.getName().toLowerCase();
        String role = controlJdbcTemplate.queryForObject(
            "SELECT role FROM users WHERE id = ?", String.class, userId
        );

        List<Map<String, Object>> entities;
        if ("SUPER_ADMIN".equalsIgnoreCase(role) || "admin@finvane.com".equalsIgnoreCase(email)) {
            entities = controlJdbcTemplate.queryForList("""
                SELECT e.id, e.name, e.legal_name, e.sector, e.business_type, e.gstin, e.gst_registered, e.state_code, e.is_active,
                       e.books_start_date, e.constitution, e.pan
                FROM entities e
                WHERE e.is_active = 1
                ORDER BY e.name
            """);
        } else {
            entities = controlJdbcTemplate.queryForList("""
                SELECT e.id, e.name, e.legal_name, e.sector, e.business_type, e.gstin, e.gst_registered, e.state_code, e.is_active,
                       e.books_start_date, e.constitution, e.pan
                FROM entities e
                JOIN user_entities ue ON ue.entity_id = e.id
                WHERE ue.user_id = ? AND e.is_active = 1
                ORDER BY e.name
            """, userId);
        }

        String activeEntityId = TenantContext.getTenantId();

        Map<String, Object> resp = new HashMap<>();
        resp.put("ok", true);
        resp.put("entities", entities);
        resp.put("data", entities);
        resp.put("activeEntityId", activeEntityId != null ? activeEntityId : (entities.isEmpty() ? "1" : String.valueOf(entities.get(0).get("id"))));
        return ResponseEntity.ok(resp);
    }

    // 4. GET /entities/{id}/logo
    @GetMapping("/entities/{id}/logo")
    public ResponseEntity<?> getLogo(@PathVariable("id") long entityId) {
        List<Map<String, Object>> rows = controlJdbcTemplate.queryForList(
            "SELECT mime_type, logo_blob FROM entity_logos WHERE entity_id = ?", entityId
        );
        if (rows.isEmpty() || rows.get(0).get("logo_blob") == null) {
            return ResponseEntity.notFound().build();
        }

        byte[] blob = (byte[]) rows.get(0).get("logo_blob");
        String mime = (String) rows.get(0).get("mime_type");
        if (mime == null || mime.isBlank()) mime = "image/png";

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, mime)
            .body(blob);
    }

    // 4.5 GET /entities/active
    @GetMapping("/entities/active")
    public ResponseEntity<?> getActiveEntity(Authentication authentication) {
        String activeTenantId = TenantContext.getTenantId();
        if (activeTenantId == null || activeTenantId.isBlank() || "default".equalsIgnoreCase(activeTenantId)) {
            activeTenantId = "1";
        }
        long entityId = 1L;
        try {
            entityId = Long.parseLong(activeTenantId);
        } catch (Exception ignored) {}
        return getEntity(entityId, authentication);
    }

    // 5. GET /entities/{id}
    @GetMapping("/entities/{id}")
    public ResponseEntity<?> getEntity(@PathVariable("id") long entityId, Authentication authentication) {
        Long userId = getUserId(authentication);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("ok", false, "error", "Unauthorized"));

        String role = controlJdbcTemplate.queryForObject(
            "SELECT role FROM users WHERE id = ?", String.class, userId
        );

        if (!"ADMIN".equalsIgnoreCase(role) && !"SUPER_ADMIN".equalsIgnoreCase(role)) {
            Integer allowed = controlJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_entities WHERE user_id = ? AND entity_id = ?", Integer.class, userId, entityId
            );
            if (allowed == null || allowed == 0) {
                return ResponseEntity.status(403).body(Map.of("ok", false, "error", "You do not have access to this business."));
            }
        }

        List<Map<String, Object>> list = controlJdbcTemplate.queryForList("SELECT * FROM entities WHERE id = ?", entityId);
        if (list.isEmpty()) return ResponseEntity.notFound().build();

        Map<String, Object> entity = new LinkedHashMap<>(list.get(0));
        Map<String, Object> flags = tenantSettingsService.getContextFlags(String.valueOf(entityId));
        entity.put("gstRegistered", flags.get("gstRegistered"));
        entity.put("manufacturingEnabled", flags.get("manufacturingEnabled"));
        entity.put("inventoryEnabled", flags.get("inventoryEnabled"));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("entity", entity);
        resp.put("data", entity);
        resp.putAll(flags);
        return ResponseEntity.ok(resp);
    }

    // 6. PUT /entities/{id}
    @PutMapping("/entities/{id}")
    @PreAuthorize("hasAuthority('admin') or hasAuthority('users_manage')")
    public ResponseEntity<?> updateEntity(@PathVariable("id") long entityId,
                                          @RequestBody Map<String, Object> body,
                                          Authentication authentication) {
        Long userId = getUserId(authentication);
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        if (name.isBlank()) return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Business name is required."));

        Map<String, Object> old = controlJdbcTemplate.queryForMap("SELECT * FROM entities WHERE id = ?", entityId);
        boolean gstRegistered = TenantSettingsService.parseBooleanFlag(
                body.containsKey("gst_registered") ? body.get("gst_registered")
                        : (body.containsKey("gstRegistered") ? body.get("gstRegistered") : old.get("gst_registered")),
                true
        );

        controlJdbcTemplate.update("""
            UPDATE entities SET name = ?, legal_name = ?, sector = ?, business_type = ?,
                                pan = ?, gst_registered = ?, gstin = ?, bank_name = ?, bank_account_no = ?, bank_ifsc = ?,
                                address = ?, state_code = ?, books_start_date = ?, constitution = ?
            WHERE id = ?
        """, name,
            pickField(body, old, "legal_name", "legalName", name),
            pickField(body, old, "sector", "sector", old.get("sector")),
            pickField(body, old, "business_type", "businessType", old.get("business_type")),
            pickField(body, old, "pan", "pan", old.get("pan")),
            gstRegistered ? 1 : 0,
            gstRegistered ? pickField(body, old, "gstin", "gstin", old.get("gstin")) : null,
            pickField(body, old, "bank_name", "bankName", old.get("bank_name")),
            pickField(body, old, "bank_account_no", "bankAccountNo", old.get("bank_account_no")),
            pickField(body, old, "bank_ifsc", "bankIfsc", old.get("bank_ifsc")),
            pickField(body, old, "address", "address", old.get("address")),
            pickField(body, old, "state_code", "stateCode", old.get("state_code")),
            pickField(body, old, "books_start_date", "booksStartDate", old.get("books_start_date")),
            pickField(body, old, "constitution", "constitution", old.get("constitution")),
            entityId);

        auditLogService.logControlAudit(entityId, userId, authentication.getName(), "update", "entity", entityId, old, body);
        return ResponseEntity.ok(Map.of("ok", true, "gstRegistered", gstRegistered, "message", "Business details updated successfully."));
    }

    private Object pickField(Map<String, Object> body, Map<String, Object> old, String snakeKey, String camelKey, Object fallback) {
        if (body.containsKey(snakeKey)) return body.get(snakeKey);
        if (body.containsKey(camelKey)) return body.get(camelKey);
        return fallback != null ? fallback : old.get(snakeKey);
    }

    // 7. GET /entities/{id}/factory-reset
    @GetMapping("/entities/{id}/factory-reset")
    @PreAuthorize("hasAuthority('admin')")
    public ResponseEntity<?> getFactoryResetInfo(@PathVariable("id") long entityId) {
        List<Map<String, Object>> e = controlJdbcTemplate.queryForList("SELECT id, name FROM entities WHERE id = ?", entityId);
        if (e.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("ok", true, "entity", e.get(0)));
    }

    // 8. POST /entities/switch and /entities/switch/{id}
    @PostMapping({"/entities/switch", "/entities/switch/{id}"})
    public ResponseEntity<?> switchEntity(@PathVariable(value = "id", required = false) Long pathId,
                                          @RequestBody(required = false) Map<String, Object> body,
                                          Authentication authentication) {
        Long userId = getUserId(authentication);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("ok", false, "error", "Unauthorized"));

        Long targetEntityId = pathId;
        if (targetEntityId == null && body != null && body.get("entityId") != null) {
            targetEntityId = Long.parseLong(String.valueOf(body.get("entityId")));
        }
        if (targetEntityId == null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "entityId is required."));
        }

        String role = controlJdbcTemplate.queryForObject(
            "SELECT role FROM users WHERE id = ?", String.class, userId
        );

        if (!"ADMIN".equalsIgnoreCase(role) && !"SUPER_ADMIN".equalsIgnoreCase(role)) {
            Integer allowed = controlJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_entities WHERE user_id = ? AND entity_id = ?", Integer.class, userId, targetEntityId
            );
            if (allowed == null || allowed == 0) {
                return ResponseEntity.status(403).body(Map.of("ok", false, "error", "You do not have access to this business."));
            }
        }

        Map<String, Object> user = controlJdbcTemplate.queryForMap(
            "SELECT email, role, permissions_json FROM users WHERE id = ?", userId
        );
        String email = String.valueOf(user.get("email"));
        String permsJson = user.get("permissions_json") != null ? String.valueOf(user.get("permissions_json")) : null;
        List<String> permissions = rbacService.getEffectivePermissions(role, permsJson);

        TenantContext.setTenantId(String.valueOf(targetEntityId));
        String newToken = jwtUtility.generateToken(userId, email, role, permissions, String.valueOf(targetEntityId));

        return ResponseEntity.ok(Map.of(
            "ok", true,
            "activeEntityId", String.valueOf(targetEntityId),
            "token", newToken
        ));
    }

    // 9. GET /entities/form-options
    @GetMapping("/entities/form-options")
    public ResponseEntity<?> getFormOptions() {
        List<Map<String, Object>> states = controlJdbcTemplate.queryForList("SELECT code, name FROM config_states ORDER BY name");
        List<String> sectors = List.of(
            "Retail & Trading", "Manufacturing", "Information Technology", "Professional Services",
            "Healthcare & Pharma", "Construction & Real Estate", "Hospitality & Tourism",
            "Logistics & Transport", "Financial Services", "Education", "Agriculture", "Other"
        );
        List<String> constitutions = List.of(
            "Private Limited Company", "Public Limited Company", "Limited Liability Partnership (LLP)",
            "Partnership Firm", "Sole Proprietorship", "One Person Company (OPC)", "Trust / Society / NGO"
        );
        return ResponseEntity.ok(Map.of("ok", true, "states", states, "sectors", sectors, "constitutions", constitutions));
    }

    // 10. POST /entities
    @PostMapping("/entities")
    @PreAuthorize("hasAuthority('admin') or hasAuthority('users_manage')")
    public ResponseEntity<?> createEntity(@RequestBody Map<String, Object> body, Authentication authentication) {
        Long userId = getUserId(authentication);
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        if (name.isBlank()) return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Business name is required."));

        String legalName = String.valueOf(body.getOrDefault("legal_name", name)).trim();
        String sector = String.valueOf(body.getOrDefault("sector", "Retail & Trading")).trim();
        String stateCode = String.valueOf(body.getOrDefault("state_code", "27")).trim();
        String gstin = String.valueOf(body.getOrDefault("gstin", "")).trim();
        boolean gstRegistered = TenantSettingsService.parseBooleanFlag(
                body.containsKey("gst_registered") ? body.get("gst_registered") : body.get("gstRegistered"),
                !gstin.isEmpty() || !body.containsKey("gst_registered") && !body.containsKey("gstRegistered")
        );
        String pan = String.valueOf(body.getOrDefault("pan", "")).trim();
        String constitution = String.valueOf(body.getOrDefault("constitution", "Private Limited Company")).trim();
        java.time.LocalDate now = java.time.LocalDate.now();
        int fyYear = now.getMonthValue() >= 4 ? now.getYear() : now.getYear() - 1;
        String defaultFy = String.format("%04d-04-01", fyYear);
        String booksStartDate = String.valueOf(body.getOrDefault("books_start_date", defaultFy)).trim();
        if (booksStartDate.isEmpty()) booksStartDate = defaultFy;

        controlJdbcTemplate.update("""
            INSERT INTO entities (name, legal_name, sector, gst_registered, gstin, pan, constitution, state_code, books_start_date, is_active, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
        """, name, legalName, sector, gstRegistered ? 1 : 0, gstRegistered && !gstin.isEmpty() ? gstin : null, pan.isEmpty() ? null : pan, constitution, stateCode, booksStartDate, userId);

        Long entityId = controlJdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        // Grant access to creating user
        controlJdbcTemplate.update("INSERT INTO user_entities (user_id, entity_id) VALUES (?, ?)", userId, entityId);

        // Provision dedicated database fv_<id>
        tenantProvisionerService.provisionTenant(entityId);

        auditLogService.logControlAudit(entityId, userId, authentication.getName(), "create", "entity", entityId, null, body);

        return ResponseEntity.ok(Map.of("ok", true, "entityId", entityId, "message", "Business " + name + " created and provisioned successfully."));
    }
}
