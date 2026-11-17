package com.finvane.controller.orders;

import com.finvane.controller.invoices.InvoiceController;
import com.finvane.dao.common.IEntityJpaAccess;
import com.finvane.dao.common.ILocationJpaAccess;
import com.finvane.dao.common.IPartyJpaAccess;
import com.finvane.dao.inventory.IItemJpaAccess;
import com.finvane.dao.orders.IQuotationJpaAccess;
import com.finvane.dao.orders.IQuotationLineJpaAccess;
import com.finvane.entity.common.EntityEntity;
import com.finvane.entity.common.LocationEntity;
import com.finvane.entity.common.PartyEntity;
import com.finvane.entity.inventory.ItemEntity;
import com.finvane.entity.orders.QuotationEntity;
import com.finvane.entity.orders.QuotationLineEntity;
import com.finvane.model.common.ApiResponse;
import com.finvane.model.common.TenantContext;
import com.finvane.model.invoices.InvoiceDTO;
import com.finvane.model.invoices.InvoiceLineDTO;
import com.finvane.model.orders.OrderDTO;
import com.finvane.model.orders.QuotationDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping({"/api/v1/quotations", "/api/v2/quotations"})
@RequiredArgsConstructor
public class QuotationController {

    private final IQuotationJpaAccess quotationDao;
    private final IQuotationLineJpaAccess quotationLineDao;
    private final IPartyJpaAccess partyDao;
    private final IItemJpaAccess itemDao;
    private final ILocationJpaAccess locationDao;
    private final IEntityJpaAccess entityDao;
    private final OrderController orderController;
    private final InvoiceController invoiceController;

    private String resolveHomeState(String tenantId) {
        try {
            return entityDao.findById(Long.parseLong(tenantId))
                    .map(EntityEntity::getStateCode)
                    .filter(s -> s != null && !s.isBlank())
                    .orElse("27");
        } catch (Exception ignored) {
            return "27";
        }
    }

    private QuotationDTO toDto(QuotationEntity q, PartyEntity p, LocationEntity loc, List<QuotationDTO.QuotationLineDTO> lineDTOs) {
        return QuotationDTO.builder()
                .id(q.getId())
                .quoteNo(q.getQuoteNo())
                .partyId(q.getPartyId())
                .partyName(p != null ? p.getName() : "Party #" + q.getPartyId())
                .partyGstin(p != null && p.getGstin() != null ? p.getGstin() : "")
                .partyAddress(p != null ? p.getAddress() : null)
                .partyStateCode(p != null ? p.getStateCode() : null)
                .partyEmail(p != null ? p.getEmail() : null)
                .partyPhone(p != null ? p.getPhone() : null)
                .locationId(q.getLocationId())
                .locationCode(loc != null ? loc.getCode() : null)
                .locationName(loc != null ? loc.getName() : null)
                .locationAddress(loc != null ? loc.getAddress() : null)
                .locationGstin(loc != null ? loc.getGstin() : null)
                .quoteDate(q.getQuoteDate())
                .validUntil(q.getValidUntil())
                .placeOfSupply(q.getPlaceOfSupply())
                .taxableValue(q.getTaxableValue())
                .cgst(q.getCgst())
                .sgst(q.getSgst())
                .igst(q.getIgst())
                .totalAmount(q.getTotalAmount())
                .status(q.getStatus())
                .terms(q.getTerms())
                .notes(q.getNotes())
                .salesOrderId(q.getSalesOrderId())
                .invoiceId(q.getInvoiceId())
                .lines(lineDTOs)
                .build();
    }

    private QuotationEntity getTenantQuotation(Long id, String tenantId) {
        QuotationEntity q = quotationDao.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Quotation not found with ID: " + id));
        if (!Objects.equals(tenantId, q.getTenantId())) {
            throw new IllegalArgumentException("Quotation not found with ID: " + id);
        }
        return q;
    }

    @GetMapping
    public ApiResponse<List<QuotationDTO>> getQuotations() {
        String tenantId = TenantContext.getTenantId();
        List<QuotationEntity> list = quotationDao.findByTenantIdOrderByIdDesc(tenantId);
        Map<Long, PartyEntity> partyMap = partyDao.findByTenantIdOrderByNameAsc(tenantId).stream()
                .collect(Collectors.toMap(PartyEntity::getId, p -> p, (a, b) -> a));
        Map<Long, LocationEntity> locMap = locationDao.findByTenantIdAndIsActiveTrueOrderByNameAsc(tenantId).stream()
                .collect(Collectors.toMap(LocationEntity::getId, l -> l, (a, b) -> a));

        List<QuotationDTO> dtos = list.stream()
                .map(q -> toDto(q, partyMap.get(q.getPartyId()), q.getLocationId() != null ? locMap.get(q.getLocationId()) : null, null))
                .toList();
        return ApiResponse.ok(dtos);
    }

    @GetMapping("/{id}")
    public ApiResponse<QuotationDTO> getQuotationById(@PathVariable Long id) {
        String tenantId = TenantContext.getTenantId();
        QuotationEntity q = getTenantQuotation(id, tenantId);

        PartyEntity p = partyDao.findById(q.getPartyId()).orElse(null);
        LocationEntity loc = q.getLocationId() != null ? locationDao.findById(q.getLocationId()).orElse(null) : null;
        List<QuotationLineEntity> lines = quotationLineDao.findByTenantIdAndQuotationId(tenantId, id);
        Map<Long, ItemEntity> itemMap = itemDao.findByTenantIdOrderByNameAsc(tenantId).stream()
                .collect(Collectors.toMap(ItemEntity::getId, it -> it, (a, b) -> a));

        List<QuotationDTO.QuotationLineDTO> lineDTOs = lines.stream().map(l -> {
            ItemEntity item = itemMap.get(l.getItemId());
            return QuotationDTO.QuotationLineDTO.builder()
                    .id(l.getId())
                    .quotationId(l.getQuotationId())
                    .itemId(l.getItemId())
                    .itemName(item != null ? item.getName() : "Item #" + l.getItemId())
                    .itemType(item != null ? item.getItemType() : "goods")
                    .hsnSac(l.getHsnSac() != null ? l.getHsnSac() : (item != null ? item.getHsnSac() : null))
                    .uom(l.getUom() != null ? l.getUom() : (item != null ? item.getUom() : null))
                    .qty(l.getQty())
                    .rate(l.getRate())
                    .discountPct(l.getDiscountPct() != null ? l.getDiscountPct() : BigDecimal.ZERO)
                    .taxableValue(l.getTaxableValue())
                    .gstRate(l.getGstRate())
                    .cgst(l.getCgst())
                    .sgst(l.getSgst())
                    .igst(l.getIgst())
                    .amount(l.getAmount())
                    .build();
        }).toList();

        return ApiResponse.ok(toDto(q, p, loc, lineDTOs));
    }

    private void computeLines(String tenantId, PartyEntity party, QuotationDTO dto, QuotationEntity target, List<QuotationLineEntity> linesToSave) {
        String homeState = resolveHomeState(tenantId);
        String pos = party.getStateCode() != null && !party.getStateCode().isBlank() ? party.getStateCode().trim() : homeState;
        if (dto.getPlaceOfSupply() != null && !dto.getPlaceOfSupply().isBlank()) {
            pos = dto.getPlaceOfSupply().trim();
        }
        boolean isIntra = homeState.equalsIgnoreCase(pos);

        BigDecimal totalTaxable = BigDecimal.ZERO;
        BigDecimal totalCgst = BigDecimal.ZERO;
        BigDecimal totalSgst = BigDecimal.ZERO;
        BigDecimal totalIgst = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        boolean hasGoods = false;

        if (dto.getLines() != null) {
            for (QuotationDTO.QuotationLineDTO l : dto.getLines()) {
                if (l.getItemId() == null || l.getQty() == null || l.getQty().compareTo(BigDecimal.ZERO) <= 0) continue;
                ItemEntity item = itemDao.findById(l.getItemId()).orElse(null);
                if (item != null && "goods".equalsIgnoreCase(item.getItemType())) {
                    hasGoods = true;
                }
                BigDecimal rate = l.getRate() != null ? l.getRate() : (item != null && item.getSalePrice() != null ? item.getSalePrice() : BigDecimal.ZERO);
                BigDecimal discPct = l.getDiscountPct() != null ? l.getDiscountPct() : BigDecimal.ZERO;
                if (discPct.compareTo(BigDecimal.ZERO) < 0 || discPct.compareTo(BigDecimal.valueOf(100)) > 0) {
                    throw new IllegalArgumentException("Discount must be between 0 and 100%.");
                }
                BigDecimal multiplier = BigDecimal.ONE.subtract(discPct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
                BigDecimal taxable = l.getQty().multiply(rate).multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
                BigDecimal gstRate = l.getGstRate() != null ? l.getGstRate() : (item != null && item.getGstRate() != null ? item.getGstRate() : BigDecimal.valueOf(18));

                BigDecimal cgst = BigDecimal.ZERO;
                BigDecimal sgst = BigDecimal.ZERO;
                BigDecimal igst = BigDecimal.ZERO;
                if (isIntra) {
                    BigDecimal halfRate = gstRate.divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
                    cgst = taxable.multiply(halfRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                    sgst = taxable.multiply(halfRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                } else {
                    igst = taxable.multiply(gstRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                }
                BigDecimal lineTotal = taxable.add(cgst).add(sgst).add(igst);

                totalTaxable = totalTaxable.add(taxable);
                totalCgst = totalCgst.add(cgst);
                totalSgst = totalSgst.add(sgst);
                totalIgst = totalIgst.add(igst);
                totalAmount = totalAmount.add(lineTotal);

                linesToSave.add(QuotationLineEntity.builder()
                        .tenantId(tenantId)
                        .itemId(l.getItemId())
                        .hsnSac(item != null ? item.getHsnSac() : l.getHsnSac())
                        .uom(item != null ? item.getUom() : l.getUom())
                        .qty(l.getQty())
                        .rate(rate)
                        .discountPct(discPct)
                        .taxableValue(taxable)
                        .gstRate(gstRate)
                        .cgst(cgst)
                        .sgst(sgst)
                        .igst(igst)
                        .amount(lineTotal)
                        .build());
            }
        }
        if (linesToSave.isEmpty()) {
            throw new IllegalArgumentException("Add at least one item with a positive quantity.");
        }
        if (hasGoods && dto.getLocationId() == null) {
            throw new IllegalArgumentException("Select a location: this quotation has goods lines (needed when converted).");
        }

        target.setPartyId(party.getId());
        target.setLocationId(dto.getLocationId());
        target.setQuoteDate(dto.getQuoteDate() != null && !dto.getQuoteDate().isBlank() ? dto.getQuoteDate() : LocalDate.now().toString());
        target.setValidUntil(dto.getValidUntil());
        target.setPlaceOfSupply(pos);
        target.setTaxableValue(totalTaxable);
        target.setCgst(totalCgst);
        target.setSgst(totalSgst);
        target.setIgst(totalIgst);
        target.setTotalAmount(totalAmount);
        target.setTerms(dto.getTerms());
        target.setNotes(dto.getNotes());
    }

    @PostMapping
    @Transactional
    public ApiResponse<QuotationDTO> createQuotation(@RequestBody QuotationDTO dto) {
        String tenantId = TenantContext.getTenantId();
        long count = quotationDao.countByTenantId(tenantId);
        LocalDate qDate = dto.getQuoteDate() != null && !dto.getQuoteDate().isBlank() ? LocalDate.parse(dto.getQuoteDate()) : LocalDate.now();
        int fy = qDate.getMonthValue() >= 4 ? qDate.getYear() : qDate.getYear() - 1;
        String quoteNo = String.format("QT/%02d-%02d/%04d", fy % 100, (fy + 1) % 100, count + 1);

        PartyEntity party = partyDao.findById(dto.getPartyId())
                .orElseThrow(() -> new IllegalArgumentException("Customer not found with ID: " + dto.getPartyId()));
        if (!Objects.equals(tenantId, party.getTenantId())) {
            throw new IllegalArgumentException("Customer does not belong to the active business.");
        }

        QuotationEntity entity = QuotationEntity.builder()
                .tenantId(tenantId)
                .quoteNo(quoteNo)
                .status("open")
                .build();
        List<QuotationLineEntity> linesToSave = new ArrayList<>();
        computeLines(tenantId, party, dto, entity, linesToSave);

        QuotationEntity saved = quotationDao.save(entity);
        linesToSave.forEach(line -> line.setQuotationId(saved.getId()));
        quotationLineDao.saveAll(linesToSave);

        LocationEntity loc = saved.getLocationId() != null ? locationDao.findById(saved.getLocationId()).orElse(null) : null;
        return ApiResponse.ok("Quotation created successfully", toDto(saved, party, loc, null));
    }

    @PutMapping("/{id}")
    @Transactional
    public ApiResponse<QuotationDTO> updateQuotation(@PathVariable Long id, @RequestBody QuotationDTO dto) {
        String tenantId = TenantContext.getTenantId();
        QuotationEntity existing = getTenantQuotation(id, tenantId);
        if (!"open".equalsIgnoreCase(existing.getStatus()) && !"draft".equalsIgnoreCase(existing.getStatus())) {
            throw new IllegalStateException("Only open quotations can be edited.");
        }
        PartyEntity party = partyDao.findById(dto.getPartyId())
                .orElseThrow(() -> new IllegalArgumentException("Customer not found with ID: " + dto.getPartyId()));

        List<QuotationLineEntity> linesToSave = new ArrayList<>();
        computeLines(tenantId, party, dto, existing, linesToSave);
        QuotationEntity saved = quotationDao.save(existing);

        quotationLineDao.deleteByTenantIdAndQuotationId(tenantId, id);
        linesToSave.forEach(line -> line.setQuotationId(saved.getId()));
        quotationLineDao.saveAll(linesToSave);

        LocationEntity loc = saved.getLocationId() != null ? locationDao.findById(saved.getLocationId()).orElse(null) : null;
        return ApiResponse.ok("Quotation updated successfully", toDto(saved, party, loc, null));
    }

    @PostMapping("/{id}/convert-order")
    @Transactional
    public ApiResponse<Map<String, Object>> convertToOrder(@PathVariable Long id) {
        String tenantId = TenantContext.getTenantId();
        QuotationEntity q = getTenantQuotation(id, tenantId);
        if ("converted".equalsIgnoreCase(q.getStatus())) {
            throw new IllegalStateException("Quotation is already converted.");
        }
        List<QuotationLineEntity> qLines = quotationLineDao.findByTenantIdAndQuotationId(tenantId, id);
        List<OrderDTO.OrderLineDTO> orderLines = qLines.stream().map(ql -> {
            BigDecimal netRate = (ql.getQty() != null && ql.getQty().compareTo(BigDecimal.ZERO) > 0 && ql.getTaxableValue() != null)
                    ? ql.getTaxableValue().divide(ql.getQty(), 2, RoundingMode.HALF_UP)
                    : ql.getRate();
            return OrderDTO.OrderLineDTO.builder()
                    .itemId(ql.getItemId())
                    .qty(ql.getQty())
                    .rate(netRate)
                    .gstRate(ql.getGstRate())
                    .build();
        }).toList();

        OrderDTO orderDto = OrderDTO.builder()
                .orderType("sales")
                .partyId(q.getPartyId())
                .locationId(q.getLocationId())
                .orderDate(LocalDate.now().toString())
                .notes("Converted from Quotation " + q.getQuoteNo())
                .lines(orderLines)
                .build();
        OrderDTO created = orderController.createOrder(orderDto).getData();

        q.setStatus("converted");
        q.setSalesOrderId(created.getId());
        quotationDao.save(q);

        return ApiResponse.ok("Converted to Sales Order " + created.getOrderNo(), Map.of("salesOrderId", created.getId(), "orderNo", created.getOrderNo()));
    }

    @PostMapping("/{id}/convert-invoice")
    @Transactional
    public ApiResponse<Map<String, Object>> convertToInvoice(@PathVariable Long id) {
        String tenantId = TenantContext.getTenantId();
        QuotationEntity q = getTenantQuotation(id, tenantId);
        if ("converted".equalsIgnoreCase(q.getStatus())) {
            throw new IllegalStateException("Quotation is already converted.");
        }
        List<QuotationLineEntity> qLines = quotationLineDao.findByTenantIdAndQuotationId(tenantId, id);
        List<InvoiceLineDTO> invLines = qLines.stream().map(ql -> {
            BigDecimal netRate = (ql.getQty() != null && ql.getQty().compareTo(BigDecimal.ZERO) > 0 && ql.getTaxableValue() != null)
                    ? ql.getTaxableValue().divide(ql.getQty(), 2, RoundingMode.HALF_UP)
                    : ql.getRate();
            return InvoiceLineDTO.builder()
                    .itemId(ql.getItemId())
                    .qty(ql.getQty())
                    .rate(netRate)
                    .gstRate(ql.getGstRate())
                    .build();
        }).toList();

        InvoiceDTO invDto = InvoiceDTO.builder()
                .invoiceType("sales")
                .partyId(q.getPartyId())
                .locationId(q.getLocationId())
                .invoiceDate(LocalDate.now().toString())
                .placeOfSupply(q.getPlaceOfSupply())
                .lines(invLines)
                .build();
        InvoiceDTO created = invoiceController.createInvoice(invDto).getData();

        q.setStatus("converted");
        q.setInvoiceId(created.getId());
        quotationDao.save(q);

        return ApiResponse.ok("Converted to Sales Invoice " + created.getInvoiceNo(), Map.of("invoiceId", created.getId(), "invoiceNo", created.getInvoiceNo()));
    }

    @PostMapping("/{id}/reject")
    @Transactional
    public ApiResponse<QuotationDTO> rejectQuotation(@PathVariable Long id) {
        String tenantId = TenantContext.getTenantId();
        QuotationEntity q = getTenantQuotation(id, tenantId);
        q.setStatus("rejected");
        quotationDao.save(q);
        PartyEntity p = partyDao.findById(q.getPartyId()).orElse(null);
        return ApiResponse.ok("Quotation marked rejected", toDto(q, p, null, null));
    }

    @PostMapping("/{id}/cancel")
    @Transactional
    public ApiResponse<QuotationDTO> cancelQuotation(@PathVariable Long id) {
        String tenantId = TenantContext.getTenantId();
        QuotationEntity q = getTenantQuotation(id, tenantId);
        q.setStatus("cancelled");
        quotationDao.save(q);
        PartyEntity p = partyDao.findById(q.getPartyId()).orElse(null);
        return ApiResponse.ok("Quotation cancelled", toDto(q, p, null, null));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ApiResponse<Void> deleteQuotation(@PathVariable Long id) {
        String tenantId = TenantContext.getTenantId();
        QuotationEntity q = getTenantQuotation(id, tenantId);
        if ("converted".equalsIgnoreCase(q.getStatus())) {
            throw new IllegalStateException("This quotation was converted to an order or invoice and cannot be deleted.");
        }
        quotationLineDao.deleteByTenantIdAndQuotationId(tenantId, id);
        quotationDao.deleteById(id);
        return ApiResponse.ok("Quotation deleted successfully", null);
    }
}
