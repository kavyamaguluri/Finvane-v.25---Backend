package com.finvane.controller.invoices;

import com.finvane.dao.accounts.IAccountLedgerJpaAccess;
import com.finvane.dao.accounts.IVoucherItemJpaAccess;
import com.finvane.dao.accounts.IVoucherJpaAccess;
import com.finvane.dao.common.IEntityJpaAccess;
import com.finvane.dao.common.ILocationJpaAccess;
import com.finvane.dao.common.IPartyJpaAccess;
import com.finvane.dao.inventory.IItemJpaAccess;
import com.finvane.dao.invoices.IInvoiceJpaAccess;
import com.finvane.dao.invoices.IInvoiceLineJpaAccess;
import com.finvane.entity.accounts.AccountLedgerEntity;
import com.finvane.entity.accounts.VoucherEntity;
import com.finvane.entity.accounts.VoucherItemEntity;
import com.finvane.entity.common.EntityEntity;
import com.finvane.entity.common.LocationEntity;
import com.finvane.entity.common.PartyEntity;
import com.finvane.entity.inventory.ItemEntity;
import com.finvane.entity.invoices.InvoiceEntity;
import com.finvane.entity.invoices.InvoiceLineEntity;
import com.finvane.model.common.ApiResponse;
import com.finvane.model.common.TenantContext;
import com.finvane.model.invoices.InvoiceDTO;
import com.finvane.model.invoices.InvoiceLineDTO;
import com.finvane.service.posting.FifoValuationService;
import com.finvane.service.settings.TenantSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping({"/api/v1/invoices", "/api/v2/invoices"})
@RequiredArgsConstructor
public class InvoiceController {

    private final IInvoiceJpaAccess invoiceDao;
    private final IInvoiceLineJpaAccess invoiceLineDao;
    private final IPartyJpaAccess partyDao;
    private final ILocationJpaAccess locationDao;
    private final IItemJpaAccess itemDao;
    private final IVoucherJpaAccess voucherDao;
    private final IVoucherItemJpaAccess voucherItemDao;
    private final IAccountLedgerJpaAccess ledgerDao;
    private final IEntityJpaAccess entityDao;
    private final JdbcTemplate jdbcTemplate;
    private final TenantSettingsService tenantSettingsService;
    private final FifoValuationService fifoValuationService;

    @GetMapping("/form-options")
    public ApiResponse<Map<String, Object>> getFormOptions() {
        String tenantId = TenantContext.getTenantId();
        boolean gstRegistered = tenantSettingsService.isGstRegistered(tenantId);
        boolean inventoryEnabled = tenantSettingsService.isInventoryEnabled(tenantId);
        String homeState = "27";
        try {
            Optional<EntityEntity> curEntity = entityDao.findById(Long.parseLong(tenantId));
            if (curEntity.isPresent() && curEntity.get().getStateCode() != null && !curEntity.get().getStateCode().isBlank()) {
                homeState = curEntity.get().getStateCode();
            }
        } catch (Exception ignored) {}

        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("gstRegistered", gstRegistered);
        opts.put("inventoryEnabled", inventoryEnabled);
        opts.put("homeState", homeState);
        return ApiResponse.ok(opts);
    }

    @GetMapping
    public ApiResponse<List<InvoiceDTO>> getInvoices(@RequestParam(required = false, defaultValue = "sales") String type) {
        String tenantId = TenantContext.getTenantId();
        List<InvoiceEntity> list = invoiceDao.findByTenantIdAndInvoiceTypeOrderByIdDesc(tenantId, type.toLowerCase());

        Map<Long, PartyEntity> partyMap = partyDao.findByTenantIdAndIsActiveTrueOrderByNameAsc(tenantId).stream()
                .collect(Collectors.toMap(PartyEntity::getId, p -> p, (a, b) -> a));
        Map<Long, LocationEntity> locMap = locationDao.findByTenantIdAndIsActiveTrueOrderByNameAsc(tenantId).stream()
                .collect(Collectors.toMap(LocationEntity::getId, l -> l, (a, b) -> a));

        List<InvoiceDTO> dtos = list.stream().map(i -> {
            PartyEntity p = partyMap.get(i.getPartyId());
            LocationEntity l = locMap.get(i.getLocationId());
            return InvoiceDTO.builder()
                    .id(i.getId())
                    .invoiceNo(i.getInvoiceNo())
                    .invoiceType(i.getInvoiceType())
                    .partyId(i.getPartyId())
                    .partyName(p != null ? p.getName() : "Party #" + i.getPartyId())
                    .partyGstin(p != null ? p.getGstin() : "")
                    .locationId(i.getLocationId())
                    .locationCode(l != null ? l.getCode() : "")
                    .invoiceDate(i.getInvoiceDate())
                    .dueDate(i.getDueDate())
                    .placeOfSupply(i.getPlaceOfSupply())
                    .taxableValue(i.getTaxableValue())
                    .cgst(i.getCgst())
                    .sgst(i.getSgst())
                    .igst(i.getIgst())
                    .totalAmount(i.getTotalAmount())
                    .status(i.getStatus())
                    .voucherId(i.getVoucherId())
                    .irn(i.getIrn())
                    .irnAckNo(i.getIrnAckNo())
                    .irnAckDate(i.getIrnAckDate())
                    .irnQr(i.getIrnQr())
                    .ewbNo(i.getEwbNo())
                    .build();
        }).toList();

        return ApiResponse.ok(dtos);
    }

    @GetMapping("/{id}")
    public ApiResponse<InvoiceDTO> getInvoiceById(@PathVariable Long id) {
        String tenantId = TenantContext.getTenantId();
        InvoiceEntity i = invoiceDao.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found with ID: " + id));

        PartyEntity p = partyDao.findById(i.getPartyId()).orElse(null);
        LocationEntity l = i.getLocationId() != null ? locationDao.findById(i.getLocationId()).orElse(null) : null;
        List<InvoiceLineEntity> lines = invoiceLineDao.findByTenantIdAndInvoiceId(tenantId, id);
        Map<Long, ItemEntity> itemMap = itemDao.findByTenantIdOrderByNameAsc(tenantId).stream()
                .collect(Collectors.toMap(ItemEntity::getId, it -> it, (a, b) -> a));

        List<InvoiceLineDTO> lineDTOs = lines.stream().map(line -> {
            ItemEntity item = itemMap.get(line.getItemId());
            return InvoiceLineDTO.builder()
                    .id(line.getId())
                    .invoiceId(line.getInvoiceId())
                    .itemId(line.getItemId())
                    .itemName(item != null ? item.getName() : "Item #" + line.getItemId())
                    .itemType(item != null ? item.getItemType() : "")
                    .hsnSac(line.getHsnSac() != null ? line.getHsnSac() : (item != null ? item.getHsnSac() : ""))
                    .qty(line.getQty())
                    .rate(line.getRate())
                    .taxableValue(line.getTaxableValue())
                    .gstRate(line.getGstRate())
                    .cgst(line.getCgst())
                    .sgst(line.getSgst())
                    .igst(line.getIgst())
                    .amount(line.getAmount())
                    .description(line.getDescription())
                    .build();
        }).toList();

        InvoiceDTO dto = InvoiceDTO.builder()
                .id(i.getId())
                .invoiceNo(i.getInvoiceNo())
                .invoiceType(i.getInvoiceType())
                .partyId(i.getPartyId())
                .partyName(p != null ? p.getName() : "")
                .partyGstin(p != null ? p.getGstin() : "")
                .locationId(i.getLocationId())
                .locationCode(l != null ? l.getCode() : "")
                .invoiceDate(i.getInvoiceDate())
                .dueDate(i.getDueDate())
                .placeOfSupply(i.getPlaceOfSupply())
                .taxableValue(i.getTaxableValue())
                .cgst(i.getCgst())
                .sgst(i.getSgst())
                .igst(i.getIgst())
                .totalAmount(i.getTotalAmount())
                .status(i.getStatus())
                .voucherId(i.getVoucherId())
                .irn(i.getIrn())
                .irnAckNo(i.getIrnAckNo())
                .irnAckDate(i.getIrnAckDate())
                .irnQr(i.getIrnQr())
                .ewbNo(i.getEwbNo())
                .lines(lineDTOs)
                .build();

        return ApiResponse.ok(dto);
    }

    @PostMapping
    @Transactional
    public ApiResponse<InvoiceDTO> createInvoice(@RequestBody InvoiceDTO dto) {
        String tenantId = TenantContext.getTenantId();
        String type = (dto.getInvoiceType() != null && dto.getInvoiceType().equalsIgnoreCase("purchase")) ? "purchase" : "sales";

        PartyEntity party = partyDao.findById(dto.getPartyId())
                .orElseThrow(() -> new IllegalArgumentException("Party not found with ID: " + dto.getPartyId()));

        long count = invoiceDao.countByTenantIdAndInvoiceType(tenantId, type);
        String prefix = "sales".equals(type) ? "SI" : "PI";
        String invoiceNo = String.format("%s-%05d", prefix, count + 1);

        String homeState = "27"; // Default Maharashtra state code
        boolean gstRegistered = tenantSettingsService.isGstRegistered(tenantId);
        try {
            Optional<EntityEntity> curEntity = entityDao.findById(Long.parseLong(tenantId));
            if (curEntity.isPresent()) {
                if (curEntity.get().getStateCode() != null && !curEntity.get().getStateCode().isBlank()) {
                    homeState = curEntity.get().getStateCode();
                }
                if (curEntity.get().getGstRegistered() != null) {
                    gstRegistered = curEntity.get().getGstRegistered();
                }
            }
        } catch (Exception ignored) {}

        String pos = dto.getPlaceOfSupply();
        if (pos == null || pos.isBlank()) {
            pos = party.getStateCode() != null ? party.getStateCode() : homeState;
        }

        BigDecimal totalTaxable = BigDecimal.ZERO;
        BigDecimal totalCgst = BigDecimal.ZERO;
        BigDecimal totalSgst = BigDecimal.ZERO;
        BigDecimal totalIgst = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;

        List<InvoiceLineEntity> computedLines = new ArrayList<>();
        List<ItemEntity> lineItems = new ArrayList<>();
        if (dto.getLines() != null) {
            for (InvoiceLineDTO l : dto.getLines()) {
                if (l.getItemId() == null || l.getQty() == null || l.getQty().compareTo(BigDecimal.ZERO) <= 0) continue;
                ItemEntity item = itemDao.findById(l.getItemId()).orElse(null);
                BigDecimal rate = l.getRate() != null ? l.getRate() : (item != null && item.getSalePrice() != null ? item.getSalePrice() : BigDecimal.ZERO);
                BigDecimal taxable = l.getQty().multiply(rate).setScale(2, RoundingMode.HALF_UP);
                BigDecimal gstRate = gstRegistered
                        ? (l.getGstRate() != null ? l.getGstRate() : (item != null && item.getGstRate() != null ? item.getGstRate() : BigDecimal.valueOf(18)))
                        : BigDecimal.ZERO;

                BigDecimal cgst = BigDecimal.ZERO;
                BigDecimal sgst = BigDecimal.ZERO;
                BigDecimal igst = BigDecimal.ZERO;

                if (gstRegistered && gstRate.compareTo(BigDecimal.ZERO) > 0) {
                    boolean isIntraState = homeState.equalsIgnoreCase(pos);
                    if (isIntraState) {
                        BigDecimal halfRate = gstRate.divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
                        cgst = taxable.multiply(halfRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                        sgst = taxable.multiply(halfRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                    } else {
                        igst = taxable.multiply(gstRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                    }
                }
                BigDecimal lineTotal = taxable.add(cgst).add(sgst).add(igst);

                totalTaxable = totalTaxable.add(taxable);
                totalCgst = totalCgst.add(cgst);
                totalSgst = totalSgst.add(sgst);
                totalIgst = totalIgst.add(igst);
                totalAmount = totalAmount.add(lineTotal);

                computedLines.add(InvoiceLineEntity.builder()
                        .tenantId(tenantId)
                        .itemId(l.getItemId())
                        .qty(l.getQty())
                        .rate(rate)
                        .taxableValue(taxable)
                        .gstRate(gstRate)
                        .cgst(cgst)
                        .sgst(sgst)
                        .igst(igst)
                        .amount(lineTotal)
                        .hsnSac(item != null ? item.getHsnSac() : "")
                        .build());
                lineItems.add(item);
            }
        }

        LocalDate invDate = (dto.getInvoiceDate() != null && !dto.getInvoiceDate().isBlank())
                ? LocalDate.parse(dto.getInvoiceDate())
                : LocalDate.now();

        // Post auto-journal voucher
        String voucherNo = String.format("%s-JV-%s", prefix, invoiceNo);
        VoucherEntity v = VoucherEntity.builder()
                .tenantId(tenantId)
                .voucherNumber(voucherNo)
                .voucherDate(invDate)
                .voucherType("INVOICE")
                .narration("Auto-posted from invoice " + invoiceNo)
                .totalAmount(totalAmount)
                .build();
        VoucherEntity savedVoucher = voucherDao.save(v);
        try {
            jdbcTemplate.update(
                "UPDATE vouchers SET voucher_no = ?, status = 'posted', source = 'invoice', source_ref = ? WHERE id = ?",
                voucherNo, invoiceNo, savedVoucher.getId()
            );
        } catch (Exception ignored) {}

        AccountLedgerEntity debtors = "sales".equals(type)
                ? resolveLedgerById(tenantSettingsService.resolvePartyLedgerId(tenantId, party.getId(), party.getName(), true))
                : null;
        AccountLedgerEntity creditors = "purchase".equals(type)
                ? resolveLedgerById(tenantSettingsService.resolvePartyLedgerId(tenantId, party.getId(), party.getName(), false))
                : null;
        AccountLedgerEntity salesAcc = "sales".equals(type)
                ? resolveLedgerById(tenantSettingsService.resolveOrCreateLedgerId(
                        tenantId, "4000", List.of("sales", "sales revenue", "sales account"),
                        "Sales", "Sales Accounts", "INCOME"))
                : null;
        AccountLedgerEntity purchaseAcc = "purchase".equals(type)
                ? resolveLedgerById(tenantSettingsService.resolveOrCreateLedgerId(
                        tenantId, "5000", List.of("purchases", "purchase", "purchase account", "cost of goods sold"),
                        "Purchases", "Purchase Accounts", "EXPENSE"))
                : null;
        AccountLedgerEntity outCgst = ("sales".equals(type) && totalCgst.compareTo(BigDecimal.ZERO) > 0)
                ? resolveLedgerById(tenantSettingsService.resolveOrCreateLedgerId(
                        tenantId, "2200", List.of("output cgst", "cgst payable", "cgst output"),
                        "Output CGST", "Duties & Taxes", "LIABILITY"))
                : null;
        AccountLedgerEntity outSgst = ("sales".equals(type) && totalSgst.compareTo(BigDecimal.ZERO) > 0)
                ? resolveLedgerById(tenantSettingsService.resolveOrCreateLedgerId(
                        tenantId, "2201", List.of("output sgst", "sgst payable", "sgst output"),
                        "Output SGST", "Duties & Taxes", "LIABILITY"))
                : null;
        AccountLedgerEntity outIgst = ("sales".equals(type) && totalIgst.compareTo(BigDecimal.ZERO) > 0)
                ? resolveLedgerById(tenantSettingsService.resolveOrCreateLedgerId(
                        tenantId, "2202", List.of("output igst", "igst payable", "igst output"),
                        "Output IGST", "Duties & Taxes", "LIABILITY"))
                : null;
        AccountLedgerEntity inCgst = ("purchase".equals(type) && totalCgst.compareTo(BigDecimal.ZERO) > 0)
                ? resolveLedgerById(tenantSettingsService.resolveOrCreateLedgerId(
                        tenantId, "1400", List.of("input cgst", "cgst input", "input tax credit cgst"),
                        "Input CGST", "Input Tax Credit", "ASSET"))
                : null;
        AccountLedgerEntity inSgst = ("purchase".equals(type) && totalSgst.compareTo(BigDecimal.ZERO) > 0)
                ? resolveLedgerById(tenantSettingsService.resolveOrCreateLedgerId(
                        tenantId, "1401", List.of("input sgst", "sgst input", "input tax credit sgst"),
                        "Input SGST", "Input Tax Credit", "ASSET"))
                : null;
        AccountLedgerEntity inIgst = ("purchase".equals(type) && totalIgst.compareTo(BigDecimal.ZERO) > 0)
                ? resolveLedgerById(tenantSettingsService.resolveOrCreateLedgerId(
                        tenantId, "1402", List.of("input igst", "igst input", "input tax credit igst"),
                        "Input IGST", "Input Tax Credit", "ASSET"))
                : null;

        List<VoucherItemEntity> vItems = new ArrayList<>();
        if ("sales".equals(type)) {
            if (debtors != null) {
                vItems.add(VoucherItemEntity.builder().voucher(savedVoucher).ledgerId(debtors.getId()).entryType("DEBIT").amount(totalAmount).lineNarration("Debtor balance").build());
                insertVoucherLine(savedVoucher.getId(), debtors.getId(), totalAmount, BigDecimal.ZERO, "Sales Invoice " + invoiceNo, party.getId());
            }
            if (salesAcc != null) {
                vItems.add(VoucherItemEntity.builder().voucher(savedVoucher).ledgerId(salesAcc.getId()).entryType("CREDIT").amount(totalTaxable).lineNarration("Sales Revenue").build());
                insertVoucherLine(savedVoucher.getId(), salesAcc.getId(), BigDecimal.ZERO, totalTaxable, "Sales Revenue", null);
            }
            if (outCgst != null && totalCgst.compareTo(BigDecimal.ZERO) > 0) {
                vItems.add(VoucherItemEntity.builder().voucher(savedVoucher).ledgerId(outCgst.getId()).entryType("CREDIT").amount(totalCgst).lineNarration("Output CGST").build());
                insertVoucherLine(savedVoucher.getId(), outCgst.getId(), BigDecimal.ZERO, totalCgst, "Output CGST", null);
            }
            if (outSgst != null && totalSgst.compareTo(BigDecimal.ZERO) > 0) {
                vItems.add(VoucherItemEntity.builder().voucher(savedVoucher).ledgerId(outSgst.getId()).entryType("CREDIT").amount(totalSgst).lineNarration("Output SGST").build());
                insertVoucherLine(savedVoucher.getId(), outSgst.getId(), BigDecimal.ZERO, totalSgst, "Output SGST", null);
            }
            if (outIgst != null && totalIgst.compareTo(BigDecimal.ZERO) > 0) {
                vItems.add(VoucherItemEntity.builder().voucher(savedVoucher).ledgerId(outIgst.getId()).entryType("CREDIT").amount(totalIgst).lineNarration("Output IGST").build());
                insertVoucherLine(savedVoucher.getId(), outIgst.getId(), BigDecimal.ZERO, totalIgst, "Output IGST", null);
            }
        } else {
            if (purchaseAcc != null) {
                vItems.add(VoucherItemEntity.builder().voucher(savedVoucher).ledgerId(purchaseAcc.getId()).entryType("DEBIT").amount(totalTaxable).lineNarration("Purchase Expense").build());
                insertVoucherLine(savedVoucher.getId(), purchaseAcc.getId(), totalTaxable, BigDecimal.ZERO, "Purchase Expense", null);
            }
            if (inCgst != null && totalCgst.compareTo(BigDecimal.ZERO) > 0) {
                vItems.add(VoucherItemEntity.builder().voucher(savedVoucher).ledgerId(inCgst.getId()).entryType("DEBIT").amount(totalCgst).lineNarration("Input CGST").build());
                insertVoucherLine(savedVoucher.getId(), inCgst.getId(), totalCgst, BigDecimal.ZERO, "Input CGST", null);
            }
            if (inSgst != null && totalSgst.compareTo(BigDecimal.ZERO) > 0) {
                vItems.add(VoucherItemEntity.builder().voucher(savedVoucher).ledgerId(inSgst.getId()).entryType("DEBIT").amount(totalSgst).lineNarration("Input SGST").build());
                insertVoucherLine(savedVoucher.getId(), inSgst.getId(), totalSgst, BigDecimal.ZERO, "Input SGST", null);
            }
            if (inIgst != null && totalIgst.compareTo(BigDecimal.ZERO) > 0) {
                vItems.add(VoucherItemEntity.builder().voucher(savedVoucher).ledgerId(inIgst.getId()).entryType("DEBIT").amount(totalIgst).lineNarration("Input IGST").build());
                insertVoucherLine(savedVoucher.getId(), inIgst.getId(), totalIgst, BigDecimal.ZERO, "Input IGST", null);
            }
            if (creditors != null) {
                vItems.add(VoucherItemEntity.builder().voucher(savedVoucher).ledgerId(creditors.getId()).entryType("CREDIT").amount(totalAmount).lineNarration("Creditor balance").build());
                insertVoucherLine(savedVoucher.getId(), creditors.getId(), BigDecimal.ZERO, totalAmount, "Purchase Invoice " + invoiceNo, party.getId());
            }
        }
        voucherItemDao.saveAll(vItems);

        Long resolvedLocId = dto.getLocationId();
        if (resolvedLocId == null) {
            List<LocationEntity> locs = locationDao.findByTenantIdAndIsActiveTrueOrderByNameAsc(tenantId);
            if (!locs.isEmpty()) resolvedLocId = locs.get(0).getId();
        }

        InvoiceEntity invoice = InvoiceEntity.builder()
                .tenantId(tenantId)
                .invoiceNo(invoiceNo)
                .invoiceType(type)
                .partyId(party.getId())
                .locationId(resolvedLocId)
                .invoiceDate(invDate.toString())
                .dueDate(dto.getDueDate() != null && !dto.getDueDate().isBlank() ? dto.getDueDate() : invDate.toString())
                .placeOfSupply(pos)
                .taxableValue(totalTaxable)
                .cgst(totalCgst)
                .sgst(totalSgst)
                .igst(totalIgst)
                .totalAmount(totalAmount)
                .status("posted")
                .voucherId(savedVoucher.getId())
                .build();
        InvoiceEntity savedInvoice = invoiceDao.save(invoice);

        for (int idx = 0; idx < computedLines.size(); idx++) {
            InvoiceLineEntity line = computedLines.get(idx);
            ItemEntity item = lineItems.get(idx);
            line.setInvoiceId(savedInvoice.getId());

            boolean isGoods = item != null && !"service".equalsIgnoreCase(item.getItemType());
            if (isGoods && resolvedLocId != null) {
                if ("purchase".equals(type)) {
                    recordStockMovement(tenantId, item, resolvedLocId, "purchase", line.getQty(), line.getRate(), "invoice", savedInvoice.getId());
                    try {
                        fifoValuationService.createLot(item.getId(), resolvedLocId, "purchase", savedInvoice.getId(), party.getId(), invDate, line.getQty(), line.getRate());
                    } catch (Exception ignored) {}
                } else {
                    recordStockMovement(tenantId, item, resolvedLocId, "sale", line.getQty().negate(), line.getRate(), "invoice", savedInvoice.getId());
                    try {
                        fifoValuationService.consumeFifo(item.getId(), resolvedLocId, line.getQty(), "sale", savedInvoice.getId(), invDate, line.getRate(), party.getId());
                    } catch (Exception ignored) {}
                }
            }
        }
        invoiceLineDao.saveAll(computedLines);

        dto.setId(savedInvoice.getId());
        dto.setInvoiceNo(invoiceNo);
        dto.setLocationId(resolvedLocId);
        dto.setTaxableValue(totalTaxable);
        dto.setCgst(totalCgst);
        dto.setSgst(totalSgst);
        dto.setIgst(totalIgst);
        dto.setTotalAmount(totalAmount);
        dto.setStatus("posted");
        dto.setVoucherId(savedVoucher.getId());

        return ApiResponse.ok("Invoice created successfully", dto);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ApiResponse<Void> deleteInvoice(@PathVariable Long id) {
        String tenantId = TenantContext.getTenantId();
        InvoiceEntity entity = invoiceDao.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found with ID: " + id));

        try {
            jdbcTemplate.update("DELETE FROM stock_ledger WHERE ref_type = 'invoice' AND ref_id = ?", id);
        } catch (Exception ignored) {}
        if (entity.getVoucherId() != null) {
            try {
                jdbcTemplate.update("DELETE FROM voucher_lines WHERE voucher_id = ?", entity.getVoucherId());
            } catch (Exception ignored) {}
            voucherDao.deleteById(entity.getVoucherId());
        }
        invoiceLineDao.deleteByTenantIdAndInvoiceId(tenantId, id);
        invoiceDao.deleteById(id);
        return ApiResponse.ok("Invoice deleted successfully", null);
    }

    private void insertVoucherLine(Long voucherId, Long accountId, BigDecimal debit, BigDecimal credit, String narration, Long partyId) {
        try {
            jdbcTemplate.update(
                "INSERT INTO voucher_lines (voucher_id, account_id, debit, credit, line_narration, party_id) VALUES (?, ?, ?, ?, ?, ?)",
                voucherId, accountId,
                debit != null ? debit.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO,
                credit != null ? credit.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO,
                narration, partyId
            );
        } catch (Exception ignored) {}
    }

    private void recordStockMovement(String tenantId, ItemEntity item, Long locationId, String txnType, BigDecimal deltaQty, BigDecimal rate, String refType, Long refId) {
        try {
            List<BigDecimal> balList = jdbcTemplate.query(
                "SELECT balance_qty FROM stock_ledger WHERE tenant_id = ? AND item_id = ? AND (location_id = ? OR (? IS NULL AND location_id IS NULL)) ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> rs.getBigDecimal("balance_qty"),
                tenantId, item.getId(), locationId, locationId
            );
            BigDecimal currentBal = balList.isEmpty() || balList.get(0) == null
                ? (item.getOpeningStock() != null ? item.getOpeningStock() : BigDecimal.ZERO)
                : balList.get(0);
            BigDecimal newBal = currentBal.add(deltaQty).setScale(4, RoundingMode.HALF_UP);
            jdbcTemplate.update(
                "INSERT INTO stock_ledger (tenant_id, item_id, location_id, txn_type, qty, rate, ref_type, ref_id, balance_qty) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tenantId, item.getId(), locationId, txnType, deltaQty.setScale(4, RoundingMode.HALF_UP),
                rate != null ? rate.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO,
                refType, refId, newBal
            );
        } catch (Exception ignored) {}
    }

    private AccountLedgerEntity resolveLedgerById(Long ledgerId) {
        if (ledgerId == null) return null;
        return ledgerDao.findById(ledgerId).orElse(null);
    }
}
