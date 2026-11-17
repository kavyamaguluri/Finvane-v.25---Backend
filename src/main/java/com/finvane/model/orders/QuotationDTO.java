package com.finvane.model.orders;

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
public class QuotationDTO {
    private Long id;
    private String quoteNo;
    private Long partyId;
    private String partyName;
    private String partyGstin;
    private String partyAddress;
    private String partyStateCode;
    private String partyEmail;
    private String partyPhone;
    private Long locationId;
    private String locationCode;
    private String locationName;
    private String locationAddress;
    private String locationGstin;
    private String quoteDate;
    private String validUntil;
    private String placeOfSupply;
    private BigDecimal taxableValue;
    private BigDecimal cgst;
    private BigDecimal sgst;
    private BigDecimal igst;
    private BigDecimal totalAmount;
    private String status;
    private String terms;
    private String notes;
    private Long salesOrderId;
    private Long invoiceId;
    private List<QuotationLineDTO> lines;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuotationLineDTO {
        private Long id;
        private Long quotationId;
        private Long itemId;
        private String itemName;
        private String itemType;
        private String hsnSac;
        private String uom;
        private BigDecimal qty;
        private BigDecimal rate;
        private BigDecimal discountPct;
        private BigDecimal taxableValue;
        private BigDecimal gstRate;
        private BigDecimal cgst;
        private BigDecimal sgst;
        private BigDecimal igst;
        private BigDecimal amount;
    }
}
