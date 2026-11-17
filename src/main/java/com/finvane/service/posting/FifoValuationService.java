package com.finvane.service.posting;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@Service
public class FifoValuationService {

    private final JdbcTemplate jdbcTemplate;

    public FifoValuationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ConsumptionRecord(
        Long lotId,
        Long itemId,
        BigDecimal consumedQty,
        BigDecimal purchaseRate,
        BigDecimal saleRate,
        BigDecimal profit
    ) {}

    /**
     * Opens a new stock lot for FIFO tracking (e.g. Purchase, Inward Transfer, Opening Balance).
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Long createLot(long itemId,
                          Long locationId,
                          String sourceType,
                          Long sourceRefId,
                          Long vendorPartyId,
                          LocalDate lotDate,
                          BigDecimal qty,
                          BigDecimal rate) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) return null;
        if (rate == null) rate = BigDecimal.ZERO;

        jdbcTemplate.update("""
            INSERT INTO stock_lots (item_id, location_id, source_type, source_ref_id, vendor_party_id, lot_date, qty_in, qty_remaining, rate)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, itemId, locationId, sourceType, sourceRefId, vendorPartyId, lotDate, qty, qty, rate);

        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /**
     * Consumes quantity from oldest open lots (FIFO) using SELECT ... FOR UPDATE row locks.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public List<ConsumptionRecord> consumeFifo(long itemId,
                                               Long locationId,
                                               BigDecimal qtyToConsume,
                                               String saleRefType,
                                               Long saleRefId,
                                               LocalDate saleDate,
                                               BigDecimal saleRate,
                                               Long customerPartyId) {
        if (qtyToConsume == null || qtyToConsume.compareTo(BigDecimal.ZERO) <= 0) {
            return Collections.emptyList();
        }
        if (saleRate == null) saleRate = BigDecimal.ZERO;

        List<Map<String, Object>> openLots = jdbcTemplate.queryForList("""
            SELECT id, qty_remaining, rate
            FROM stock_lots
            WHERE item_id = ? AND (location_id = ? OR (? IS NULL AND location_id IS NULL)) AND qty_remaining > 0
            ORDER BY lot_date ASC, id ASC
            FOR UPDATE
        """, itemId, locationId, locationId);

        BigDecimal remaining = qtyToConsume;
        List<ConsumptionRecord> consumptions = new ArrayList<>();

        for (Map<String, Object> lot : openLots) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;

            long lotId = ((Number) lot.get("id")).longValue();
            BigDecimal lotRemaining = ((BigDecimal) lot.get("qty_remaining")).setScale(4, RoundingMode.HALF_UP);
            BigDecimal lotRate = ((BigDecimal) lot.get("rate")).setScale(2, RoundingMode.HALF_UP);

            BigDecimal take = remaining.min(lotRemaining);
            BigDecimal newLotRemaining = lotRemaining.subtract(take);

            jdbcTemplate.update("UPDATE stock_lots SET qty_remaining = ? WHERE id = ?", newLotRemaining, lotId);

            BigDecimal profit = saleRate.subtract(lotRate).multiply(take).setScale(2, RoundingMode.HALF_UP);

            jdbcTemplate.update("""
                INSERT INTO stock_lot_consumptions (lot_id, item_id, consumed_qty, sale_ref_type, sale_ref_id, sale_date, sale_rate, purchase_rate, profit, customer_party_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, lotId, itemId, take, saleRefType, saleRefId, saleDate, saleRate, lotRate, profit, customerPartyId);

            consumptions.add(new ConsumptionRecord(lotId, itemId, take, lotRate, saleRate, profit));
            remaining = remaining.subtract(take);
        }

        // Shortfall fallback against phantom zero-cost lot if data drift
        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal profit = saleRate.multiply(remaining).setScale(2, RoundingMode.HALF_UP);
            jdbcTemplate.update("""
                INSERT INTO stock_lot_consumptions (lot_id, item_id, consumed_qty, sale_ref_type, sale_ref_id, sale_date, sale_rate, purchase_rate, profit, customer_party_id)
                VALUES (NULL, ?, ?, ?, ?, ?, ?, 0.00, ?, ?)
            """, itemId, remaining, saleRefType, saleRefId, saleDate, saleRate, profit, customerPartyId);

            consumptions.add(new ConsumptionRecord(null, itemId, remaining, BigDecimal.ZERO, saleRate, profit));
        }

        return consumptions;
    }

    /**
     * Unconsumes lots on document edit / deletion.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void unconsumeFifo(String saleRefType, Long saleRefId) {
        List<Map<String, Object>> consumptions = jdbcTemplate.queryForList("""
            SELECT lot_id, consumed_qty
            FROM stock_lot_consumptions
            WHERE sale_ref_type = ? AND sale_ref_id = ? AND lot_id IS NOT NULL
            FOR UPDATE
        """, saleRefType, saleRefId);

        for (Map<String, Object> c : consumptions) {
            long lotId = ((Number) c.get("lot_id")).longValue();
            BigDecimal consumedQty = (BigDecimal) c.get("consumed_qty");
            jdbcTemplate.update("UPDATE stock_lots SET qty_remaining = qty_remaining + ? WHERE id = ?", consumedQty, lotId);
        }

        jdbcTemplate.update("DELETE FROM stock_lot_consumptions WHERE sale_ref_type = ? AND sale_ref_id = ?", saleRefType, saleRefId);
    }
}

