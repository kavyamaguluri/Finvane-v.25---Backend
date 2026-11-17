package com.finvane.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

public class GstCalculationUtilityTest {

    @Test
    @DisplayName("Test intra-state GST calculation (Maharashtra to Maharashtra)")
    void testIntraStateStandard() {
        GstCalculationUtility.GstBreakup result = GstCalculationUtility.calculateTax(
            "27", "27", new BigDecimal("1000.00"), new BigDecimal("18.00")
        );

        assertTrue(result.isIntraState());
        assertEquals(new BigDecimal("90.00"), result.cgstAmount());
        assertEquals(new BigDecimal("90.00"), result.sgstAmount());
        assertEquals(new BigDecimal("0.00"), result.igstAmount());
        assertEquals(new BigDecimal("180.00"), result.totalGst());
        assertEquals(new BigDecimal("1180.00"), result.totalAmount());
    }

    @Test
    @DisplayName("Test inter-state GST calculation (Maharashtra to Delhi)")
    void testInterStateStandard() {
        GstCalculationUtility.GstBreakup result = GstCalculationUtility.calculateTax(
            "27", "07", new BigDecimal("1000.00"), new BigDecimal("18.00")
        );

        assertFalse(result.isIntraState());
        assertEquals(new BigDecimal("0.00"), result.cgstAmount());
        assertEquals(new BigDecimal("0.00"), result.sgstAmount());
        assertEquals(new BigDecimal("180.00"), result.igstAmount());
        assertEquals(new BigDecimal("180.00"), result.totalGst());
        assertEquals(new BigDecimal("1180.00"), result.totalAmount());
    }

    @Test
    @DisplayName("Test odd decimal half-penny split parity (taxable 5555.55 at 12%)")
    void testHalfPennySplit() {
        GstCalculationUtility.GstBreakup result = GstCalculationUtility.calculateTax(
            "27", "27", new BigDecimal("5555.55"), new BigDecimal("12.00")
        );

        assertTrue(result.isIntraState());
        assertEquals(new BigDecimal("333.34"), result.cgstAmount());
        assertEquals(new BigDecimal("333.33"), result.sgstAmount());
        assertEquals(new BigDecimal("0.00"), result.igstAmount());
        assertEquals(new BigDecimal("666.67"), result.totalGst());
        assertEquals(new BigDecimal("6222.22"), result.totalAmount());
    }

    @Test
    @DisplayName("Test 5% rate split on 5555.55")
    void testFivePercentRate() {
        GstCalculationUtility.GstBreakup result = GstCalculationUtility.calculateTax(
            "27", "27", new BigDecimal("5555.55"), new BigDecimal("5.00")
        );

        assertTrue(result.isIntraState());
        assertEquals(new BigDecimal("138.89"), result.cgstAmount());
        assertEquals(new BigDecimal("138.89"), result.sgstAmount());
        assertEquals(new BigDecimal("0.00"), result.igstAmount());
        assertEquals(new BigDecimal("277.78"), result.totalGst());
        assertEquals(new BigDecimal("5833.33"), result.totalAmount());
    }

    @Test
    @DisplayName("Test 28% rate split on 9999.99")
    void testTwentyEightPercentRate() {
        GstCalculationUtility.GstBreakup result = GstCalculationUtility.calculateTax(
            "27", "27", new BigDecimal("9999.99"), new BigDecimal("28.00")
        );

        assertTrue(result.isIntraState());
        assertEquals(new BigDecimal("1400.00"), result.cgstAmount());
        assertEquals(new BigDecimal("1400.00"), result.sgstAmount());
        assertEquals(new BigDecimal("0.00"), result.igstAmount());
        assertEquals(new BigDecimal("2800.00"), result.totalGst());
        assertEquals(new BigDecimal("12799.99"), result.totalAmount());
    }

    @Test
    @DisplayName("Test inter-state high value 123456.78 at 18%")
    void testHighValueInterState() {
        GstCalculationUtility.GstBreakup result = GstCalculationUtility.calculateTax(
            "27", "29", new BigDecimal("123456.78"), new BigDecimal("18.00")
        );

        assertFalse(result.isIntraState());
        assertEquals(new BigDecimal("0.00"), result.cgstAmount());
        assertEquals(new BigDecimal("0.00"), result.sgstAmount());
        assertEquals(new BigDecimal("22222.22"), result.igstAmount());
        assertEquals(new BigDecimal("22222.22"), result.totalGst());
        assertEquals(new BigDecimal("145679.00"), result.totalAmount());
    }

    @Test
    @DisplayName("Test zero taxable value")
    void testZeroTaxable() {
        GstCalculationUtility.GstBreakup result = GstCalculationUtility.calculateTax(
            "27", "27", BigDecimal.ZERO, new BigDecimal("18.00")
        );

        assertEquals(new BigDecimal("0.00"), result.cgstAmount());
        assertEquals(new BigDecimal("0.00"), result.sgstAmount());
        assertEquals(new BigDecimal("0.00"), result.igstAmount());
        assertEquals(new BigDecimal("0.00"), result.totalGst());
        assertEquals(new BigDecimal("0.00"), result.totalAmount());
    }
}

