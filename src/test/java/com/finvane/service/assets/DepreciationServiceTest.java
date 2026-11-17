package com.finvane.service.assets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DepreciationServiceTest {

    private DepreciationService service;

    @BeforeEach
    void setUp() {
        service = new DepreciationService();
    }

    @Test
    @DisplayName("Test WDV rate formula parity with Companies Act 2013 / legacy Node formula")
    void testWdvRateParity() {
        BigDecimal cost = new BigDecimal("1000.00");
        BigDecimal salvage = new BigDecimal("50.00"); // 5% residual

        // Life = 3 years -> 63.16%
        assertEquals(new BigDecimal("63.16"), service.computeWdvRateFromFormula(cost, salvage, 3));

        // Life = 5 years -> 45.07%
        assertEquals(new BigDecimal("45.07"), service.computeWdvRateFromFormula(cost, salvage, 5));

        // Life = 8 years -> 31.23%
        assertEquals(new BigDecimal("31.23"), service.computeWdvRateFromFormula(cost, salvage, 8));

        // Life = 10 years -> 25.89%
        assertEquals(new BigDecimal("25.89"), service.computeWdvRateFromFormula(cost, salvage, 10));

        // Life = 15 years -> 18.10%
        assertEquals(new BigDecimal("18.10"), service.computeWdvRateFromFormula(cost, salvage, 15));

        // Life = 30 years -> 9.50%
        assertEquals(new BigDecimal("9.50"), service.computeWdvRateFromFormula(cost, salvage, 30));

        // Life = 60 years -> 4.87%
        assertEquals(new BigDecimal("4.87"), service.computeWdvRateFromFormula(cost, salvage, 60));
    }

    @Test
    @DisplayName("Test SLM annual depreciation formula: (Cost - Salvage) / UsefulLife")
    void testSlmAnnualDepreciation() {
        BigDecimal cost = new BigDecimal("100000.00");
        BigDecimal salvage = new BigDecimal("5000.00");
        int usefulLife = 5;

        // (100000 - 5000) / 5 = 19000.00
        assertEquals(new BigDecimal("19000.00"), service.computeSlmAnnualDepreciation(cost, salvage, usefulLife));
    }

    @Test
    @DisplayName("Test zero or invalid cost / life")
    void testEdgeCases() {
        assertEquals(new BigDecimal("0.00"), service.computeWdvRateFromFormula(BigDecimal.ZERO, BigDecimal.ZERO, 5));
        assertEquals(new BigDecimal("0.00"), service.computeWdvRateFromFormula(new BigDecimal("1000"), BigDecimal.ZERO, 0));
        assertEquals(new BigDecimal("0.00"), service.computeSlmAnnualDepreciation(BigDecimal.ZERO, BigDecimal.ZERO, 5));
        assertEquals(new BigDecimal("0.00"), service.computeSlmAnnualDepreciation(new BigDecimal("1000"), BigDecimal.ZERO, 0));
    }
}

