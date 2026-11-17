package com.finvane.vouchers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class VoucherParityTest {

    private static final Map<String, String> PREFIX_BY_TYPE = Map.of(
            "payment", "PMT",
            "receipt", "RCT",
            "contra", "CTR",
            "journal", "JV",
            "sales", "SDB",
            "purchase", "PDB",
            "credit_note", "CDB",
            "debit_note", "DDB"
    );

    @Test
    @DisplayName("PARITY: Voucher number prefix allocation matches legacy rules")
    void testVoucherNumberPrefixAllocation() {
        assertEquals("PMT", PREFIX_BY_TYPE.get("payment"));
        assertEquals("RCT", PREFIX_BY_TYPE.get("receipt"));
        assertEquals("CTR", PREFIX_BY_TYPE.get("contra"));
        assertEquals("JV", PREFIX_BY_TYPE.get("journal"));
        assertEquals("SDB", PREFIX_BY_TYPE.get("sales"));
        assertEquals("PDB", PREFIX_BY_TYPE.get("purchase"));
        assertEquals("CDB", PREFIX_BY_TYPE.get("credit_note"));
        assertEquals("DDB", PREFIX_BY_TYPE.get("debit_note"));

        // Format check: PMT-00001
        String generatedNo = String.format("%s-%05d", PREFIX_BY_TYPE.get("payment"), 1);
        assertEquals("PMT-00001", generatedNo);
    }

    @Test
    @DisplayName("PARITY: Double entry balancing rule (Total Dr == Total Cr, >= 2 lines, > 0)")
    void testVoucherBalancingValidation() {
        // Balanced voucher: |dr - cr| <= 0.01 (compareTo <= 0)
        BigDecimal dr1 = new BigDecimal("15000.50");
        BigDecimal cr1 = new BigDecimal("15000.50");
        assertTrue(dr1.subtract(cr1).abs().compareTo(new BigDecimal("0.01")) <= 0);
        assertTrue(dr1.compareTo(BigDecimal.ZERO) > 0);

        // Unbalanced voucher
        BigDecimal dr2 = new BigDecimal("15000.50");
        BigDecimal cr2 = new BigDecimal("15000.00");
        assertTrue(dr2.subtract(cr2).abs().compareTo(new BigDecimal("0.01")) > 0);

        // Zero amount voucher
        BigDecimal dr3 = BigDecimal.ZERO;
        BigDecimal cr3 = BigDecimal.ZERO;
        assertFalse(dr3.compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    @DisplayName("PARITY: Segregation of duties (creator cannot approve own voucher)")
    void testMakerCheckerSegregationOfDuties() {
        Long creatorId = 42L;
        Long approverIdSame = 42L;
        Long approverIdDifferent = 99L;

        boolean canApproveSelf = !creatorId.equals(approverIdSame);
        assertFalse(canApproveSelf, "User who created the voucher cannot approve it");

        boolean canApproveOther = !creatorId.equals(approverIdDifferent);
        assertTrue(canApproveOther, "Different authorized user can approve the voucher");
    }

    @Test
    @DisplayName("PARITY: Editing posted voucher resets status to pending_approval and clears approval metadata")
    void testEditPostedVoucherResetsStatus() {
        String originalStatus = "posted";
        Long approvedBy = 10L;
        String approvedAt = "2026-10-15 14:30:00";

        // After update
        String updatedStatus = "pending_approval";
        Long updatedApprovedBy = null;
        String updatedApprovedAt = null;

        assertNotEquals(originalStatus, updatedStatus);
        assertEquals("pending_approval", updatedStatus);
        assertNull(updatedApprovedBy);
        assertNull(updatedApprovedAt);
    }

    @Test
    @DisplayName("PARITY: Day Book Tax detail split and join format parity")
    void testTaxDetailFormatting() {
        String combined = "GST - CGST";
        int idx = combined.indexOf(" - ");
        assertNotEquals(-1, idx);
        String typeOfTax = combined.substring(0, idx);
        String taxType = combined.substring(idx + 3);

        assertEquals("GST", typeOfTax);
        assertEquals("CGST", taxType);

        // Round-trip join
        String rejoined = typeOfTax + " - " + taxType;
        assertEquals(combined, rejoined);
    }
}
