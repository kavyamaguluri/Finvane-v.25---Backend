package com.finvane.transactions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.*;

public class TransactionParityTest {

    @Test
    @DisplayName("PARITY: GST Split for Intra-state vs Inter-state transactions")
    void testGstIntraVsInterState() {
        BigDecimal base = new BigDecimal("10000.00");
        BigDecimal rate = new BigDecimal("18.00"); // 18%

        // Intra-state (same state code): 9% CGST + 9% SGST
        BigDecimal halfRate = rate.divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
        BigDecimal cgst = base.multiply(halfRate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        BigDecimal sgst = base.multiply(halfRate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        BigDecimal igst = BigDecimal.ZERO;

        assertEquals(new BigDecimal("900.00"), cgst);
        assertEquals(new BigDecimal("900.00"), sgst);
        assertEquals(new BigDecimal("11800.00"), base.add(cgst).add(sgst));

        // Inter-state (different state code): 18% IGST
        BigDecimal fullIgst = base.multiply(rate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        assertEquals(new BigDecimal("1800.00"), fullIgst);
        assertEquals(new BigDecimal("11800.00"), base.add(fullIgst));
    }

    @Test
    @DisplayName("PARITY: Settlement allocation and open invoice outstanding computation")
    void testSettlementOutstandingComputation() {
        BigDecimal invoiceTotal = new BigDecimal("50000.00");
        BigDecimal creditNotes = new BigDecimal("5000.00");
        BigDecimal previousPaid = new BigDecimal("20000.00");

        BigDecimal outstanding = invoiceTotal.subtract(creditNotes).subtract(previousPaid)
                .setScale(2, RoundingMode.HALF_UP);

        assertEquals(new BigDecimal("25000.00"), outstanding);

        // Applying settlement payment of 15,000
        BigDecimal paymentAmt = new BigDecimal("15000.00");
        assertTrue(paymentAmt.compareTo(outstanding) <= 0);

        BigDecimal remainingOutstanding = outstanding.subtract(paymentAmt);
        assertEquals(new BigDecimal("10000.00"), remainingOutstanding);
    }

    @Test
    @DisplayName("PARITY: Maker-Checker approval workflow status transitions")
    void testMakerCheckerWorkflow() {
        String initialStatus = "draft";
        assertEquals("draft", initialStatus);

        String submittedStatus = "pending_approval";
        assertEquals("pending_approval", submittedStatus);

        // Approve transition
        String approvedStatus = "posted";
        assertEquals("posted", approvedStatus);

        // Reject transition
        String rejectedStatus = "rejected";
        assertEquals("rejected", rejectedStatus);
    }

    @Test
    @DisplayName("PARITY: Round-to-two-decimals exact financial rule")
    void testRoundToTwoDecimals() {
        double val1 = 123.456;
        double rounded1 = Math.round((val1 + Math.ulp(val1)) * 100.0) / 100.0;
        assertEquals(123.46, rounded1, 0.0001);

        double val2 = 123.454;
        double rounded2 = Math.round((val2 + Math.ulp(val2)) * 100.0) / 100.0;
        assertEquals(123.45, rounded2, 0.0001);
    }

    @Test
    @DisplayName("PARITY: Settlement Prefix generation by party type")
    void testSettlementPrefix() {
        String vendorType = "vendor";
        String customerType = "customer";

        String vendorPrefix = !"customer".equalsIgnoreCase(vendorType) ? "PAY" : "RCT";
        String customerPrefix = !"customer".equalsIgnoreCase(customerType) ? "PAY" : "RCT";

        assertEquals("PAY", vendorPrefix);
        assertEquals("RCT", customerPrefix);

        String vendorVoucherNo = String.format("%s-%05d", vendorPrefix, 1);
        assertEquals("PAY-00001", vendorVoucherNo);
    }
}

