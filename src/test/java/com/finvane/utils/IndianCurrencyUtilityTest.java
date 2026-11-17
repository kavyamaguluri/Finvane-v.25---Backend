package com.finvane.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class IndianCurrencyUtilityTest {

    @Test
    @DisplayName("Test Zero amount in words")
    void testZero() {
        assertEquals("Rupees Zero Only", IndianCurrencyUtility.amountToWords(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("Test Single rupee in words")
    void testOne() {
        assertEquals("Rupees One Only", IndianCurrencyUtility.amountToWords(BigDecimal.ONE));
    }

    @Test
    @DisplayName("Test amount with paise (15.50)")
    void testFifteenPointFifty() {
        assertEquals("Rupees Fifteen and Fifty Paise Only", IndianCurrencyUtility.amountToWords(new BigDecimal("15.50")));
    }

    @Test
    @DisplayName("Test Hundred")
    void testHundred() {
        assertEquals("Rupees One Hundred Only", IndianCurrencyUtility.amountToWords(new BigDecimal("100")));
    }

    @Test
    @DisplayName("Test Thousand")
    void testThousand() {
        assertEquals("Rupees One Thousand Only", IndianCurrencyUtility.amountToWords(new BigDecimal("1000")));
    }

    @Test
    @DisplayName("Test Ten Thousand Five Hundred with Paise (10500.75)")
    void testTenThousandFiveHundredAndPaise() {
        assertEquals("Rupees Ten Thousand Five Hundred and Seventy Five Paise Only", IndianCurrencyUtility.amountToWords(new BigDecimal("10500.75")));
    }

    @Test
    @DisplayName("Test One Lakh (100000)")
    void testOneLakh() {
        assertEquals("Rupees One Lakh Only", IndianCurrencyUtility.amountToWords(new BigDecimal("100000")));
    }

    @Test
    @DisplayName("Test complex lakhs amount (1234567.89)")
    void testComplexLakhs() {
        assertEquals("Rupees Twelve Lakh Thirty Four Thousand Five Hundred Sixty Seven and Eighty Nine Paise Only",
            IndianCurrencyUtility.amountToWords(new BigDecimal("1234567.89")));
    }

    @Test
    @DisplayName("Test One Crore (10000000)")
    void testOneCrore() {
        assertEquals("Rupees One Crore Only", IndianCurrencyUtility.amountToWords(new BigDecimal("10000000")));
    }

    @Test
    @DisplayName("Test Ninety Eight Crore (987654321.50)")
    void testLargeCrore() {
        assertEquals("Rupees Ninety Eight Crore Seventy Six Lakh Fifty Four Thousand Three Hundred Twenty One and Fifty Paise Only",
            IndianCurrencyUtility.amountToWords(new BigDecimal("987654321.50")));
    }

    @Test
    @DisplayName("Test Indian currency formatting")
    void testCurrencyFormat() {
        assertEquals("₹0.00", IndianCurrencyUtility.formatCurrency(BigDecimal.ZERO));
        assertEquals("₹1,000.00", IndianCurrencyUtility.formatCurrency(new BigDecimal("1000")));
        assertEquals("₹1,00,000.00", IndianCurrencyUtility.formatCurrency(new BigDecimal("100000")));
        assertEquals("₹12,34,567.89", IndianCurrencyUtility.formatCurrency(new BigDecimal("1234567.89")));
    }
}

