package com.finvane.model.reports;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashFlowDTO {
    private String fromDate;
    private String toDate;
    private BigDecimal netProfit;
    private BigDecimal operatingCashFlow;
    private BigDecimal investingCashFlow;
    private BigDecimal financingCashFlow;
    private BigDecimal netCashFlow;
    private BigDecimal openingCash;
    private BigDecimal closingCash;
    private List<CashFlowItem> operatingItems;
    private List<CashFlowItem> investingItems;
    private List<CashFlowItem> financingItems;
    private List<DirectCashEntry> directOperating;
    private List<DirectCashEntry> directInvesting;
    private List<DirectCashEntry> directFinancing;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CashFlowItem {
        private Long id;
        private String code;
        private String description;
        private BigDecimal amount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DirectCashEntry {
        private Long voucherId;
        private String voucherNo;
        private String date;
        private Long againstLedgerId;
        private String against;
        private String narration;
        private BigDecimal amount;
    }
}
