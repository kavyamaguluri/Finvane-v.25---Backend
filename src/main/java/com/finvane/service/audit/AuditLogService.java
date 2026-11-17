package com.finvane.service.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finvane.config.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class AuditLogService {

    private final JdbcTemplate controlJdbcTemplate;
    private final ObjectMapper objectMapper;

    public AuditLogService(JdbcTemplate controlJdbcTemplate, ObjectMapper objectMapper) {
        this.controlJdbcTemplate = controlJdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void logControlAudit(Long businessEntityId,
                                Long userId,
                                String userEmail,
                                String action,
                                String entity,
                                Long entityId,
                                Object before,
                                Object after) {
        try {
            String beforeJson = before != null ? objectMapper.writeValueAsString(before) : null;
            String afterJson = after != null ? objectMapper.writeValueAsString(after) : null;

            controlJdbcTemplate.update("""
                INSERT INTO audit_log (business_entity_id, user_id, user_email, action, entity, entity_id, before_json, after_json, ip)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, businessEntityId, userId, userEmail, action, entity, entityId, beforeJson, afterJson, "127.0.0.1");
        } catch (Exception ignored) {}
    }

    public void logAction(String action, String entity, String entityId, Object before, Object after, HttpServletRequest request) {
        try {
            Long businessEntityId = null;
            String tenantId = TenantContext.getTenantId();
            if (tenantId != null && !tenantId.isBlank() && !"control".equalsIgnoreCase(tenantId)) {
                try {
                    businessEntityId = Long.parseLong(tenantId);
                } catch (Exception ignored) {}
            }

            String userEmail = null;
            Long userId = null;
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
                userEmail = auth.getName();
                try {
                    userId = controlJdbcTemplate.queryForObject("SELECT id FROM users WHERE LOWER(email) = ?", Long.class, userEmail.toLowerCase());
                } catch (Exception ignored) {}
            }

            Long entIdLong = null;
            if (entityId != null) {
                try { entIdLong = Long.parseLong(entityId); } catch (Exception ignored) {}
            }

            logControlAudit(businessEntityId, userId, userEmail, action, entity, entIdLong, before, after);
        } catch (Exception ignored) {}
    }
}
