package com.finvane.service.payroll;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PayrollCalculationServiceTest {

    private PayrollCalculationService service;

    @BeforeEach
    void setUp() {
        service = new PayrollCalculationService();
    }

    @Test
    @DisplayName("Test CTC 15,000 structure: Basic 50%, HRA 20%, Allowances 30%, PF 12%")
    void testStructure15k() {
        PayrollCalculationService.SalaryStructure s = service.computeSalaryStructure(new BigDecimal("15000"));
        assertEquals(new BigDecimal("7500.00"), s.basic());
        assertEquals(new BigDecimal("3000.00"), s.hra());
        assertEquals(new BigDecimal("4500.00"), s.allowances());
        assertEquals(new BigDecimal("15000.00"), s.gross());
        assertEquals(new BigDecimal("900.00"), s.pf());
    }

    @Test
    @DisplayName("Test CTC 21,000 structure")
    void testStructure21k() {
        PayrollCalculationService.SalaryStructure s = service.computeSalaryStructure(new BigDecimal("21000"));
        assertEquals(new BigDecimal("10500.00"), s.basic());
        assertEquals(new BigDecimal("4200.00"), s.hra());
        assertEquals(new BigDecimal("6300.00"), s.allowances());
        assertEquals(new BigDecimal("21000.00"), s.gross());
        assertEquals(new BigDecimal("1260.00"), s.pf());
    }

    @Test
    @DisplayName("Test CTC 35,000 structure")
    void testStructure35k() {
        PayrollCalculationService.SalaryStructure s = service.computeSalaryStructure(new BigDecimal("35000"));
        assertEquals(new BigDecimal("17500.00"), s.basic());
        assertEquals(new BigDecimal("7000.00"), s.hra());
        assertEquals(new BigDecimal("10500.00"), s.allowances());
        assertEquals(new BigDecimal("35000.00"), s.gross());
        assertEquals(new BigDecimal("2100.00"), s.pf());
    }

    @Test
    @DisplayName("Test CTC 50,000 structure")
    void testStructure50k() {
        PayrollCalculationService.SalaryStructure s = service.computeSalaryStructure(new BigDecimal("50000"));
        assertEquals(new BigDecimal("25000.00"), s.basic());
        assertEquals(new BigDecimal("10000.00"), s.hra());
        assertEquals(new BigDecimal("15000.00"), s.allowances());
        assertEquals(new BigDecimal("50000.00"), s.gross());
        assertEquals(new BigDecimal("3000.00"), s.pf());
    }

    @Test
    @DisplayName("Test CTC 1,20,000 structure")
    void testStructure120k() {
        PayrollCalculationService.SalaryStructure s = service.computeSalaryStructure(new BigDecimal("120000"));
        assertEquals(new BigDecimal("60000.00"), s.basic());
        assertEquals(new BigDecimal("24000.00"), s.hra());
        assertEquals(new BigDecimal("36000.00"), s.allowances());
        assertEquals(new BigDecimal("120000.00"), s.gross());
        assertEquals(new BigDecimal("7200.00"), s.pf());
    }

    @Test
    @DisplayName("Test Section 87A rebate (Annual income <= 7,00,000 has 0 TDS)")
    void testSection87aRebate() {
        // Monthly 50,000 -> Annual 6,00,000 <= 7,00,000 -> 0 TDS
        assertEquals(new BigDecimal("0.00"), service.computeMonthlyTds(new BigDecimal("50000")));
    }
}
