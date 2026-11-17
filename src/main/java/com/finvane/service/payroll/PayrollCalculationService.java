package com.finvane.service.payroll;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class PayrollCalculationService {

    public record SalaryStructure(
        BigDecimal basic,
        BigDecimal hra,
        BigDecimal allowances,
        BigDecimal gross,
        BigDecimal pf
    ) {}

    public SalaryStructure computeSalaryStructure(BigDecimal monthlyCtc) {
        if (monthlyCtc == null) monthlyCtc = BigDecimal.ZERO;

        BigDecimal basic = monthlyCtc.multiply(BigDecimal.valueOf(0.5)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal hra = monthlyCtc.multiply(BigDecimal.valueOf(0.2)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal allowances = monthlyCtc.subtract(basic).subtract(hra).setScale(2, RoundingMode.HALF_UP);
        BigDecimal gross = basic.add(hra).add(allowances).setScale(2, RoundingMode.HALF_UP);
        BigDecimal pf = basic.multiply(BigDecimal.valueOf(0.12)).setScale(2, RoundingMode.HALF_UP);

        return new SalaryStructure(basic, hra, allowances, gross, pf);
    }

    public BigDecimal computeMonthlyTds(BigDecimal monthlyGross) {
        if (monthlyGross == null || monthlyGross.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal annualIncome = monthlyGross.multiply(BigDecimal.valueOf(12)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal annualTax = annualTdsNewRegime(annualIncome);
        return annualTax.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
    }

    public BigDecimal annualTdsNewRegime(BigDecimal annualIncome) {
        // Section 87A rebate: no tax if annual income <= 7,00,000
        if (annualIncome.compareTo(BigDecimal.valueOf(700000)) <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        double income = annualIncome.doubleValue();
        double[][] slabs = {
            {300000, 0.0},
            {700000, 0.05},
            {1000000, 0.10},
            {1200000, 0.15},
            {1500000, 0.20},
            {Double.MAX_VALUE, 0.30}
        };

        double tax = 0.0;
        double lower = 0.0;
        for (double[] slab : slabs) {
            double upper = slab[0];
            double rate = slab[1];
            if (income > lower) {
                double taxableInSlab = Math.min(income, upper) - lower;
                tax += taxableInSlab * rate;
                lower = upper;
            } else {
                break;
            }
        }

        return BigDecimal.valueOf(tax).setScale(2, RoundingMode.HALF_UP);
    }
}

