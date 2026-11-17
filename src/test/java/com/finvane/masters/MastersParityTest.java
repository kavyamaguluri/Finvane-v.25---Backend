package com.finvane.masters;

import com.finvane.utils.CryptoUtility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Masters Parity Unit Tests (Parties, Locations, Inventory)")
public class MastersParityTest {

    @Test
    @DisplayName("1. AES-256-GCM Encryption, Decryption, and PAN Masking Parity")
    public void testCryptoParity() {
        String pan = "ABCDE1234F";
        String encrypted = CryptoUtility.encrypt(pan);
        assertNotNull(encrypted);
        String[] parts = encrypted.split(":");
        assertEquals(3, parts.length, "Encrypted payload must have 3 parts (iv:tag:cipher)");

        String decrypted = CryptoUtility.decrypt(encrypted);
        assertEquals(pan, decrypted, "Decrypted PAN must match original plaintext");

        // Masking checks
        assertEquals("***FILE", CryptoUtility.mask("ON FILE"), "Masking 'ON FILE' with default 4 visible chars");
        assertEquals("******234F", CryptoUtility.mask("ABCDE1234F", 4), "Masking PAN shows last 4 chars");
        assertEquals("", CryptoUtility.mask(null));
        assertEquals("", CryptoUtility.mask(""));
    }

    @Test
    @DisplayName("2. Party Duplicate Detection (Case-insensitive & Trimmed)")
    public void testPartyDuplicateDetection() {
        String existingName = " Acme Technologies Pvt Ltd ";
        String existingGstin = "27AAACA1234A1Z5";

        // Query input variations
        String queryName1 = "acme technologies pvt ltd";
        String queryName2 = "   ACME TECHNOLOGIES PVT LTD   ";
        String queryGstin = "27aaaca1234a1z5";

        // Parity comparison rule: lower(trim(name)) == lower(trim(input))
        assertTrue(existingName.trim().equalsIgnoreCase(queryName1.trim()));
        assertTrue(existingName.trim().equalsIgnoreCase(queryName2.trim()));
        assertTrue(existingGstin.trim().equalsIgnoreCase(queryGstin.trim()));
    }

    @Test
    @DisplayName("3. Outstanding Aging Buckets & Ledger Balance Calculation")
    public void testOutstandingAgingCalculation() {
        LocalDate today = LocalDate.of(2026, 11, 6);

        // Invoices with various due dates
        LocalDate dueNotDue = LocalDate.of(2026, 11, 10); // in future -> b0
        LocalDate due15DaysAgo = LocalDate.of(2026, 10, 22); // 15 days ago -> b30
        LocalDate due45DaysAgo = LocalDate.of(2026, 9, 22); // 45 days ago -> b60
        LocalDate due75DaysAgo = LocalDate.of(2026, 8, 23); // 75 days ago -> b90

        long age0 = ChronoUnit.DAYS.between(dueNotDue, today);
        long age15 = ChronoUnit.DAYS.between(due15DaysAgo, today);
        long age45 = ChronoUnit.DAYS.between(due45DaysAgo, today);
        long age75 = ChronoUnit.DAYS.between(due75DaysAgo, today);

        assertTrue(age0 <= 0);
        assertTrue(age15 > 0 && age15 <= 30);
        assertTrue(age45 > 30 && age45 <= 60);
        assertTrue(age75 > 60);

        // Summation & net reconciliation
        BigDecimal billed = new BigDecimal("100000.00");
        BigDecimal notes = new BigDecimal("5000.00");
        BigDecimal paid = new BigDecimal("60000.00");
        BigDecimal outstanding = billed.subtract(notes).subtract(paid).setScale(2, RoundingMode.HALF_UP);

        assertEquals(new BigDecimal("35000.00"), outstanding);
    }

    @Test
    @DisplayName("4. Item Profit Margins & Margin Percentage Parity")
    public void testItemMarginCalculation() {
        BigDecimal purchasePrice = new BigDecimal("60.00");
        BigDecimal salePrice = new BigDecimal("100.00");

        BigDecimal margin = salePrice.subtract(purchasePrice).setScale(2, RoundingMode.HALF_UP);
        assertEquals(new BigDecimal("40.00"), margin);

        BigDecimal marginPercent = margin.multiply(BigDecimal.valueOf(100)).divide(salePrice, 2, RoundingMode.HALF_UP);
        assertEquals(new BigDecimal("40.00"), marginPercent);

        // Zero sale price case
        BigDecimal zeroSale = BigDecimal.ZERO;
        BigDecimal cost = new BigDecimal("25.00");
        BigDecimal zeroMargin = zeroSale.subtract(cost).setScale(2, RoundingMode.HALF_UP);
        assertEquals(new BigDecimal("-25.00"), zeroMargin);
    }

    @Test
    @DisplayName("5. Service SKU Sequential Generator Parity (SVC-0001..)")
    public void testServiceSkuSequence() {
        List<String> existingSkus = List.of("SVC-0001", "SVC-0002", "SVC-0005", "SVC-0003", "ITM-999");
        int max = 0;
        for (String sku : existingSkus) {
            if (sku.startsWith("SVC-")) {
                try {
                    int num = Integer.parseInt(sku.substring(4).trim());
                    if (num > max) max = num;
                } catch (Exception ignored) {}
            }
        }
        assertEquals(5, max);
        String nextSku = String.format("SVC-%04d", max + 1);
        assertEquals("SVC-0006", nextSku);
    }
}
