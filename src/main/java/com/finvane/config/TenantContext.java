package com.finvane.config;

/**
 * Thread-local context tracking the current business entity (tenant) ID per request.
 */
public final class TenantContext {
    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {}

    public static void setTenantId(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static String getTenantId() {
        return CURRENT_TENANT.get();
    }

    public static boolean isControl() {
        String t = CURRENT_TENANT.get();
        return t == null || t.isBlank() || "control".equalsIgnoreCase(t);
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}

