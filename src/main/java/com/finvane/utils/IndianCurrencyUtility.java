package com.finvane.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class IndianCurrencyUtility {

    private static final String[] ONES = {
        "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
        "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen"
    };

    private static final String[] TENS = {
        "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"
    };

    private IndianCurrencyUtility() {}

    private static String below100(long n) {
        if (n < 20) return ONES[(int) n];
        int tens = (int) (n / 10);
        int rem = (int) (n % 10);
        return TENS[tens] + (rem > 0 ? " " + ONES[rem] : "");
    }

    private static String below1000(long n) {
        if (n >= 100) {
            int hundreds = (int) (n / 100);
            int rem = (int) (n % 100);
            return ONES[hundreds] + " Hundred" + (rem > 0 ? " " + below100(rem) : "");
        }
        return below100(n);
    }

    public static String words(long n) {
        if (n == 0) return "Zero";
        StringBuilder parts = new StringBuilder();
        long crore = n / 10_000_000L;
        n %= 10_000_000L;
        long lakh = n / 100_000L;
        n %= 100_000L;
        long thousand = n / 1_000L;
        n %= 1_000L;

        if (crore > 0) parts.append(words(crore)).append(" Crore ");
        if (lakh > 0) parts.append(below100(lakh)).append(" Lakh ");
        if (thousand > 0) parts.append(below100(thousand)).append(" Thousand ");
        if (n > 0) parts.append(below1000(n));

        return parts.toString().trim();
    }

    public static String amountToWords(BigDecimal amount) {
        if (amount == null) amount = BigDecimal.ZERO;
        long total = amount.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValue();
        long rupees = total / 100;
        long paise = Math.abs(total % 100);

        StringBuilder sb = new StringBuilder("Rupees ");
        sb.append(words(rupees));
        if (paise > 0) {
            sb.append(" and ").append(below100(paise)).append(" Paise");
        }
        sb.append(" Only");
        return sb.toString();
    }

    public static String formatCurrency(BigDecimal amount) {
        if (amount == null) return "₹0.00";
        BigDecimal rounded = amount.setScale(2, RoundingMode.HALF_UP);
        boolean isNegative = rounded.compareTo(BigDecimal.ZERO) < 0;
        BigDecimal abs = rounded.abs();

        long integerPart = abs.longValue();
        int decimalPart = abs.remainder(BigDecimal.ONE).movePointRight(2).intValue();

        String intStr = String.valueOf(integerPart);
        StringBuilder sb = new StringBuilder();

        if (intStr.length() <= 3) {
            sb.append(intStr);
        } else {
            String lastThree = intStr.substring(intStr.length() - 3);
            String rest = intStr.substring(0, intStr.length() - 3);

            StringBuilder restFormatted = new StringBuilder();
            int count = 0;
            for (int i = rest.length() - 1; i >= 0; i--) {
                restFormatted.insert(0, rest.charAt(i));
                count++;
                if (count % 2 == 0 && i > 0) {
                    restFormatted.insert(0, ",");
                }
            }
            sb.append(restFormatted).append(",").append(lastThree);
        }

        return (isNegative ? "-₹" : "₹") + sb + "." + String.format("%02d", decimalPart);
    }
}
