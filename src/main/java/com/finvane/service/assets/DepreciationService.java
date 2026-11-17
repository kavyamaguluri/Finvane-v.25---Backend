package com.finvane.service.assets;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class DepreciationService {

    /**
     * Companies Act 2013 / Mathematical WDV rate formula:
     * Rate = 1 - (salvage / cost) ^ (1 / usefulLifeYears)
     * Returns rate percentage rounded to 2 decimal places.
     */
    public BigDecimal computeWdvRateFromFormula(BigDecimal cost, BigDecimal salvageValue, int usefulLifeYears) {
        if (cost == null || cost.compareTo(BigDecimal.ZERO) <= 0 || usefulLifeYears <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        double c = cost.doubleValue();
        double s = salvageValue != null ? Math.max(0, salvageValue.doubleValue()) : 0.0;
        double ratio = Math.min(1.0, s / c);

        double rate = 1.0 - Math.pow(ratio, 1.0 / usefulLifeYears);
        double ratePct = Math.min(100.0, Math.max(0.0, rate * 100.0));

        return BigDecimal.valueOf(ratePct).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Straight Line Method (SLM) Annual Depreciation:
     * (Cost - Salvage) / UsefulLifeYears
     */
    public BigDecimal computeSlmAnnualDepreciation(BigDecimal cost, BigDecimal salvageValue, int usefulLifeYears) {
        if (cost == null || cost.compareTo(BigDecimal.ZERO) <= 0 || usefulLifeYears <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal salvage = salvageValue != null ? salvageValue : BigDecimal.ZERO;
        BigDecimal depreciableAmount = cost.subtract(salvage).max(BigDecimal.ZERO);
        return depreciableAmount.divide(BigDecimal.valueOf(usefulLifeYears), 2, RoundingMode.HALF_UP);
    }
}

