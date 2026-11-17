package com.finvane.model.common;

/**
 * Universal TenantContext bridging model layer to config layer.
 */
public final class TenantContext {

    private TenantContext() {}

    public static void setTenantId(String tenantId) {
        com.finvane.config.TenantContext.setTenantId(tenantId);
    }

    public static String getTenantId() {
        String t = com.finvane.config.TenantContext.getTenantId();
        return (t == null || t.isBlank() || "control".equalsIgnoreCase(t)) ? "1" : t;
    }

    public static void clear() {
        com.finvane.config.TenantContext.clear();
    }
}
