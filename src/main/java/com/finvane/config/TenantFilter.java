package com.finvane.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "X-Entity-ID";
    public static final String TENANT_HEADER_ALT = "X-Tenant-ID";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String tenantId = request.getHeader(TENANT_HEADER);
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = request.getHeader(TENANT_HEADER_ALT);
        }
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = request.getParameter("entityId");
        }
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = request.getParameter("tenantId");
        }

        // If route is public auth or control management, default to control
        String path = request.getRequestURI();
        if (tenantId == null || tenantId.isBlank()) {
            if (path.startsWith("/api/v1/auth") || path.startsWith("/api/v1/entities") || path.startsWith("/actuator")) {
                tenantId = "control";
            } else {
                tenantId = "1"; // Default demo business
            }
        }

        TenantContext.setTenantId(tenantId);
        response.setHeader(TENANT_HEADER, TenantContext.getTenantId());

        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
