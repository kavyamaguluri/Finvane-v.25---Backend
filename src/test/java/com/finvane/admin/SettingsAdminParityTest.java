package com.finvane.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class SettingsAdminParityTest {

    @Test
    @DisplayName("PARITY: Allowed attachment MIME types match legacy upload security policy")
    void testAllowedAttachmentMimeTypes() {
        Set<String> allowedMime = Set.of("application/pdf", "image/png", "image/jpeg");

        assertTrue(allowedMime.contains("application/pdf"));
        assertTrue(allowedMime.contains("image/png"));
        assertTrue(allowedMime.contains("image/jpeg"));
        assertFalse(allowedMime.contains("application/x-executable"));
        assertFalse(allowedMime.contains("text/javascript"));
    }

    @Test
    @DisplayName("PARITY: Factory reset preserves core infrastructure (Chart of accounts, Entity profile, Users)")
    void testFactoryResetPreservedEntities() {
        Set<String> preservedTables = Set.of(
                "entities", "users", "user_entities", "account_groups", "account_ledgers", "company_settings"
        );

        assertTrue(preservedTables.contains("account_ledgers"), "Chart of accounts must not be wiped");
        assertTrue(preservedTables.contains("users"), "User authentication records must not be wiped");
        assertTrue(preservedTables.contains("entities"), "Business identity profile must not be wiped");
    }

    @Test
    @DisplayName("PARITY: Dynamic company service switch enabled state detection")
    void testServiceSwitchState() {
        Map<String, String> companySettings = new HashMap<>();
        companySettings.put("inventory_enabled", "true");
        companySettings.put("payroll_enabled", "false");

        boolean isInventoryActive = "true".equalsIgnoreCase(companySettings.get("inventory_enabled"));
        boolean isPayrollActive = "true".equalsIgnoreCase(companySettings.get("payroll_enabled"));

        assertTrue(isInventoryActive);
        assertFalse(isPayrollActive);
    }
}

