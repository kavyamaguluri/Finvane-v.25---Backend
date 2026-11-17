package com.finvane.auth;

import com.finvane.controller.auth.AuthController;
import com.finvane.service.auth.RbacService;
import com.finvane.service.auth.TotpService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AuthAdminParityTest {

    private final TotpService totpService = new TotpService();
    private final RbacService rbacService = new RbacService(null);

    @Test
    @DisplayName("Password strength rule parity (10+ chars, upper, lower, digit, symbol)")
    void testPasswordStrength() {
        assertFalse(AuthController.isPasswordStrong("weak"));
        assertFalse(AuthController.isPasswordStrong("short1@A")); // 8 chars
        assertFalse(AuthController.isPasswordStrong("alllowercase123@")); // no upper
        assertFalse(AuthController.isPasswordStrong("ALLUPPERCASE123@")); // no lower
        assertFalse(AuthController.isPasswordStrong("NoDigitsHere!@#$")); // no digit
        assertFalse(AuthController.isPasswordStrong("NoSymbols1234567")); // no symbol

        assertTrue(AuthController.isPasswordStrong("Admin@12345")); // valid
        assertTrue(AuthController.isPasswordStrong("StrongP@ssw0rd2026")); // valid
    }

    @Test
    @DisplayName("TOTP generation and verification parity")
    void testTotpGenerationAndVerification() {
        String secret = totpService.generateSecret();
        assertNotNull(secret);
        assertTrue(secret.length() >= 16);

        String qrUri = totpService.generateQrDataUri("admin@finvane.com", secret);
        assertNotNull(qrUri);
        assertTrue(qrUri.startsWith("data:image/png;base64,"));

        // Invalid codes rejected
        assertFalse(totpService.verifyCode(secret, "000000"));
        assertFalse(totpService.verifyCode(secret, "123"));
        assertFalse(totpService.verifyCode(secret, null));
    }

    @Test
    @DisplayName("RBAC role permissions parity: Admin has all 20 permissions")
    void testAdminPermissions() {
        List<String> adminPerms = rbacService.getEffectivePermissions("admin", null);
        assertEquals(20, adminPerms.size());
        assertTrue(adminPerms.containsAll(RbacService.ALL_PERMISSIONS));
    }

    @Test
    @DisplayName("RBAC roles catalog parity: Exactly 7 standard roles")
    void testRolesCatalog() {
        assertEquals(7, RbacService.ALL_ROLES.size());
        assertTrue(RbacService.ALL_ROLES.contains("admin"));
        assertTrue(RbacService.ALL_ROLES.contains("accountant"));
        assertTrue(RbacService.ALL_ROLES.contains("approver"));
        assertTrue(RbacService.ALL_ROLES.contains("sales"));
        assertTrue(RbacService.ALL_ROLES.contains("inventory_manager"));
        assertTrue(RbacService.ALL_ROLES.contains("payroll_manager"));
        assertTrue(RbacService.ALL_ROLES.contains("auditor"));
    }

    @Test
    @DisplayName("RBAC permissions catalog parity: Exactly 20 standard permissions")
    void testPermissionsCatalog() {
        assertEquals(20, RbacService.ALL_PERMISSIONS.size());
        assertTrue(RbacService.ALL_PERMISSIONS.contains("accounts_manage"));
        assertTrue(RbacService.ALL_PERMISSIONS.contains("vouchers_create"));
        assertTrue(RbacService.ALL_PERMISSIONS.contains("vouchers_approve"));
        assertTrue(RbacService.ALL_PERMISSIONS.contains("reports_view"));
        assertTrue(RbacService.ALL_PERMISSIONS.contains("invoices_manage"));
        assertTrue(RbacService.ALL_PERMISSIONS.contains("inventory_manage"));
        assertTrue(RbacService.ALL_PERMISSIONS.contains("payroll_manage"));
        assertTrue(RbacService.ALL_PERMISSIONS.contains("banking_manage"));
        assertTrue(RbacService.ALL_PERMISSIONS.contains("users_manage"));
        assertTrue(RbacService.ALL_PERMISSIONS.contains("audit_view"));
        assertTrue(RbacService.ALL_PERMISSIONS.contains("dashboard_view"));
        assertTrue(RbacService.ALL_PERMISSIONS.contains("locations_manage"));
        assertTrue(RbacService.ALL_PERMISSIONS.contains("stock_transfers_manage"));
        assertTrue(RbacService.ALL_PERMISSIONS.contains("orders_manage"));
        assertTrue(RbacService.ALL_PERMISSIONS.contains("notes_manage"));
        assertTrue(RbacService.ALL_PERMISSIONS.contains("assets_manage"));
        assertTrue(RbacService.ALL_PERMISSIONS.contains("budgets_manage"));
        assertTrue(RbacService.ALL_PERMISSIONS.contains("compliance_manage"));
        assertTrue(RbacService.ALL_PERMISSIONS.contains("attachments_manage"));
        assertTrue(RbacService.ALL_PERMISSIONS.contains("financial_model_view"));
    }
}

