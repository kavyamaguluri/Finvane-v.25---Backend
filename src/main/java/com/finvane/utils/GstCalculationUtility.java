package com.finvane.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class GstCalculationUtility {

    private GstCalculationUtility() {}

    public record GstBreakup(
        boolean isIntraState,
        BigDecimal taxableValue,
        BigDecimal gstRate,
        BigDecimal cgstRate,
        BigDecimal cgstAmount,
        BigDecimal sgstRate,
        BigDecimal sgstAmount,
        BigDecimal igstRate,
        BigDecimal igstAmount,
        BigDecimal totalGst,
        BigDecimal totalAmount
    ) {}

    public static GstBreakup calculateTax(String supplierStateCode,
                                         String placeOfSupplyStateCode,
                                         BigDecimal taxableValue,
                                         BigDecimal gstRatePercent) {
        if (taxableValue == null) taxableValue = BigDecimal.ZERO;
        if (gstRatePercent == null) gstRatePercent = BigDecimal.ZERO;

        String suppState = (supplierStateCode == null || supplierStateCode.isBlank()) ? "27" : supplierStateCode.trim();
        String posState = (placeOfSupplyStateCode == null || placeOfSupplyStateCode.isBlank()) ? suppState : placeOfSupplyStateCode.trim();

        boolean isIntraState = suppState.equalsIgnoreCase(posState);

        BigDecimal rateFraction = gstRatePercent.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        BigDecimal totalTax = taxableValue.multiply(rateFraction).setScale(2, RoundingMode.HALF_UP);

        BigDecimal cgstRate = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal cgstAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal sgstRate = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal sgstAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal igstRate = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal igstAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        if (isIntraState) {
            cgstRate = gstRatePercent.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
            sgstRate = cgstRate;
            // Exact half penny allocation matching legacy round2(taxAmount / 2)
            cgstAmount = totalTax.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
            sgstAmount = totalTax.subtract(cgstAmount).setScale(2, RoundingMode.HALF_UP);
        } else {
            igstRate = gstRatePercent.setScale(2, RoundingMode.HALF_UP);
            igstAmount = totalTax.setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal totalAmount = taxableValue.add(totalTax).setScale(2, RoundingMode.HALF_UP);

        return new GstBreakup(
            isIntraState,
            taxableValue,
            gstRatePercent,
            cgstRate,
            cgstAmount,
            sgstRate,
            sgstAmount,
            igstRate,
            igstAmount,
            totalTax,
            totalAmount
        );
    }
}
