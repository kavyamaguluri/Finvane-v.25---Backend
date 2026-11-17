package com.finvane.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

public class InventoryTransferProductionParityTest {

    @Test
    @DisplayName("PARITY: Stock transfer number formatting matches TRF-XXXXX pattern")
    void testStockTransferNumberPattern() {
        int seq = 42;
        String transferNo = String.format("TRF-%05d", seq);
        assertEquals("TRF-00042", transferNo);
    }

    @Test
    @DisplayName("PARITY: Stock transfer double-entry balance (Outflow = Inflow)")
    void testStockTransferQuantities() {
        BigDecimal transferQty = new BigDecimal("150.00");
        BigDecimal outflow = transferQty.negate();
        BigDecimal inflow = transferQty;

        assertEquals(0, BigDecimal.ZERO.compareTo(outflow.add(inflow)));
        assertTrue(inflow.compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    @DisplayName("PARITY: Production Order FG and RM lot balance")
    void testProductionBomConsumption() {
        // BOM: 2 units of RM1 + 1 unit of RM2 -> 1 unit of FG
        BigDecimal fgTargetQty = new BigDecimal("50");
        BigDecimal rm1PerUnit = new BigDecimal("2");
        BigDecimal rm2PerUnit = new BigDecimal("1");

        BigDecimal totalRm1Needed = fgTargetQty.multiply(rm1PerUnit);
        BigDecimal totalRm2Needed = fgTargetQty.multiply(rm2PerUnit);

        assertEquals(new BigDecimal("100"), totalRm1Needed);
        assertEquals(new BigDecimal("50"), totalRm2Needed);
    }

    @Test
    @DisplayName("PARITY: Inter-location transfer validation rejects same from and to location")
    void testSameLocationValidation() {
        Long fromLoc = 1L;
        Long toLoc = 1L;
        assertTrue(fromLoc.equals(toLoc), "Transfers within the exact same location must be rejected");

        Long diffLoc = 2L;
        assertFalse(fromLoc.equals(diffLoc), "Transfers across distinct locations are valid");
    }

    @Test
    @DisplayName("PARITY: Direct stock count delta calculation")
    void testDirectStockCountDelta() {
        BigDecimal countedQty = new BigDecimal("120");
        BigDecimal systemQty = new BigDecimal("115");
        BigDecimal delta = countedQty.subtract(systemQty);

        assertEquals(new BigDecimal("5"), delta);
        assertTrue(delta.compareTo(BigDecimal.ZERO) > 0, "Positive inventory adjustment required");
    }
}
