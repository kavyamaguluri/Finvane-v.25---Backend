-- Fin Vane ERP: Tenant Business Database Schema (fv_<entity_id>)
-- Generated from Data Model - Columns.csv specification
SET FOREIGN_KEY_CHECKS = 0;

CREATE TABLE IF NOT EXISTS `locations` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `code` VARCHAR(50) NOT NULL,
  `name` VARCHAR(255) NOT NULL,
  `location_type` VARCHAR(255) NOT NULL DEFAULT 'warehouse',
  `address` LONGTEXT,
  `state_code` VARCHAR(50) NOT NULL,
  `gstin` VARCHAR(50),
  `is_active` TINYINT(1) NOT NULL DEFAULT 1,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY `uk_locations_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `account_groups` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `name` VARCHAR(255) NOT NULL,
  `parent_id` BIGINT,
  `nature` VARCHAR(255) NOT NULL,
  `affects_gross_profit` TINYINT(1) NOT NULL DEFAULT 0,
  `cash_flow_category` VARCHAR(255) NOT NULL DEFAULT 'operating',
  `is_system` TINYINT(1) NOT NULL DEFAULT 1,
  `sort_order` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `accounts` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `code` VARCHAR(50) NOT NULL,
  `name` VARCHAR(255) NOT NULL,
  `type` VARCHAR(50) NOT NULL,
  `group_id` BIGINT,
  `parent_id` BIGINT,
  `opening_balance` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `is_active` TINYINT(1) NOT NULL DEFAULT 1,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `ledger_code` VARCHAR(255),
  `ledger_type_code` VARCHAR(255),
  `ledger_group_3` VARCHAR(255),
  `ledger_group_4` VARCHAR(255),
  `pl_type` VARCHAR(255),
  `gst_applicable` VARCHAR(255),
  `tds_applicable` VARCHAR(255),
  `tcs_applicable` VARCHAR(255),
  `gst_type_of_supply` VARCHAR(255),
  `gst_nature_of_supply` VARCHAR(255),
  `tds_section_number` VARCHAR(255),
  `tcs_section_number` VARCHAR(255),
  `hsn_code` VARCHAR(255),
  `igst_rate` DECIMAL(15, 2),
  `cgst_rate` DECIMAL(15, 2),
  `sgst_rate` DECIMAL(15, 2),
  `cess_rate` DECIMAL(15, 2),
  `rcm_applicable` VARCHAR(255),
  `itc_eligible` VARCHAR(255),
  `branch_name` VARCHAR(255),
  `sector_line` VARCHAR(255),
  UNIQUE KEY `uk_accounts_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `vouchers` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `voucher_no` VARCHAR(255) NOT NULL,
  `voucher_date` DATE NOT NULL,
  `voucher_type` VARCHAR(255) NOT NULL DEFAULT 'journal',
  `narration` VARCHAR(255),
  `status` VARCHAR(50) NOT NULL DEFAULT 'pending_approval',
  `source` VARCHAR(255) DEFAULT 'manual',
  `source_ref` VARCHAR(255),
  `created_by` BIGINT,
  `approved_by` BIGINT,
  `approved_at` DATETIME(3),
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX `idx_vouchers_date` (`voucher_date`),
  INDEX `idx_vouchers_type` (`voucher_type`),
  INDEX `idx_vouchers_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `voucher_lines` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `voucher_id` BIGINT NOT NULL,
  `account_id` BIGINT NOT NULL,
  `debit` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `credit` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `line_narration` VARCHAR(255),
  `party_id` BIGINT,
  `tax_type` VARCHAR(255),
  `bank_account_number` VARCHAR(255),
  `gstin` VARCHAR(50),
  INDEX `idx_voucher_lines_voucher` (`voucher_id`),
  INDEX `idx_voucher_lines_account` (`account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `parties` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `name` VARCHAR(255) NOT NULL,
  `party_type` VARCHAR(255) NOT NULL,
  `gstin` VARCHAR(50),
  `pan_enc` VARCHAR(255),
  `state_code` VARCHAR(50),
  `address` LONGTEXT,
  `email` VARCHAR(191),
  `phone` VARCHAR(50),
  `is_active` TINYINT(1) NOT NULL DEFAULT 1,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `items` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `sku` VARCHAR(50) NOT NULL,
  `name` VARCHAR(255) NOT NULL,
  `item_type` VARCHAR(255) NOT NULL DEFAULT 'goods',
  `category` VARCHAR(255),
  `brand` VARCHAR(255),
  `hsn_sac` VARCHAR(50),
  `uom` VARCHAR(50) DEFAULT 'NOS',
  `purchase_price` DECIMAL(15, 2) DEFAULT 0,
  `sale_price` DECIMAL(15, 2) DEFAULT 0,
  `gst_rate` DECIMAL(15, 2) DEFAULT 18,
  `opening_stock` DECIMAL(12, 4) DEFAULT 0,
  `reorder_level` DECIMAL(15, 2) DEFAULT 0,
  `is_active` TINYINT(1) NOT NULL DEFAULT 1,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `item_class` VARCHAR(255),
  UNIQUE KEY `uk_items_sku` (`sku`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `stock_ledger` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `item_id` BIGINT NOT NULL,
  `location_id` BIGINT,
  `txn_type` VARCHAR(255) NOT NULL,
  `qty` DECIMAL(12, 4) NOT NULL,
  `rate` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `ref_type` VARCHAR(255),
  `ref_id` BIGINT,
  `balance_qty` DECIMAL(12, 4) NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX `idx_stock_ledger_item` (`item_id`),
  INDEX `idx_stock_ledger_location` (`location_id`),
  INDEX `idx_stock_ledger_date` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `invoices` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `invoice_no` VARCHAR(255) NOT NULL,
  `invoice_type` VARCHAR(255) NOT NULL,
  `party_id` BIGINT NOT NULL,
  `location_id` BIGINT,
  `invoice_date` DATE NOT NULL,
  `place_of_supply` VARCHAR(255),
  `taxable_value` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `cgst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `sgst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `igst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `total_amount` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `status` VARCHAR(50) NOT NULL DEFAULT 'posted',
  `voucher_id` BIGINT,
  `created_by` BIGINT,
  `due_date` DATE,
  `paid_amount` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `irn` VARCHAR(255),
  `irn_ack_no` VARCHAR(255),
  `irn_ack_date` DATE,
  `irn_qr` VARCHAR(255),
  `ewb_no` VARCHAR(255),
  `ewb_valid_upto` VARCHAR(255),
  `ewb_transporter` VARCHAR(255),
  `ewb_vehicle_no` VARCHAR(255),
  `ewb_distance_km` DECIMAL(15, 2),
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `vendor_bill_no` VARCHAR(255),
  `narration` VARCHAR(255),
  `discount_total` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `rcm_tax` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `itc_voucher_id` BIGINT,
  `rcm_voucher_id` BIGINT,
  `emailed_at` DATETIME(3),
  `emailed_to` VARCHAR(255),
  INDEX `idx_invoices_party` (`party_id`),
  INDEX `idx_invoices_date` (`invoice_date`),
  INDEX `idx_invoices_type` (`invoice_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `invoice_lines` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `invoice_id` BIGINT NOT NULL,
  `item_id` BIGINT NOT NULL,
  `qty` DECIMAL(12, 4) NOT NULL,
  `rate` DECIMAL(15, 2) NOT NULL,
  `taxable_value` DECIMAL(15, 2) NOT NULL,
  `gst_rate` DECIMAL(15, 2) NOT NULL,
  `cgst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `sgst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `igst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `amount` DECIMAL(15, 2) NOT NULL,
  `description` LONGTEXT,
  `hsn_sac` VARCHAR(50),
  `discount` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `ledger_account_id` BIGINT,
  `rcm` TINYINT(1) NOT NULL DEFAULT 0,
  `is_import` TINYINT(1) NOT NULL DEFAULT 0,
  `itc_eligible` TINYINT(1) NOT NULL DEFAULT 1,
  `itc_reason` VARCHAR(255),
  INDEX `idx_invoice_lines_invoice` (`invoice_id`),
  INDEX `idx_invoice_lines_item` (`item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `employees` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `name` VARCHAR(255) NOT NULL,
  `email` VARCHAR(191),
  `designation` VARCHAR(255),
  `gender` VARCHAR(255),
  `pan_enc` VARCHAR(255),
  `bank_account_enc` VARCHAR(255),
  `ifsc` VARCHAR(255),
  `monthly_ctc` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `date_of_joining` VARCHAR(255),
  `date_of_exit` VARCHAR(255),
  `is_active` TINYINT(1) NOT NULL DEFAULT 1,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `aadhaar_enc` VARCHAR(255),
  `uan` VARCHAR(255),
  `esi_ip_no` VARCHAR(255),
  `date_of_birth` VARCHAR(255),
  `exit_type` VARCHAR(255),
  `exit_reason` VARCHAR(255),
  `pf_applicable` TINYINT(1) NOT NULL DEFAULT 1,
  `esi_applicable` TINYINT(1) NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `payroll_runs` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `period_month` BIGINT NOT NULL,
  `period_year` BIGINT NOT NULL,
  `status` VARCHAR(50) NOT NULL DEFAULT 'draft',
  `voucher_id` BIGINT,
  `processed_by` BIGINT,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `statutory_voucher_id` BIGINT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `payslips` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `payroll_run_id` BIGINT NOT NULL,
  `employee_id` BIGINT NOT NULL,
  `basic` DECIMAL(15, 2) NOT NULL,
  `hra` DECIMAL(15, 2) NOT NULL,
  `allowances` DECIMAL(15, 2) NOT NULL,
  `gross` DECIMAL(15, 2) NOT NULL,
  `pf` DECIMAL(15, 2) NOT NULL,
  `tds` DECIMAL(15, 2) NOT NULL,
  `net_pay` DECIMAL(15, 2) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `app_settings` (
  `key` VARCHAR(255),
  `value` VARCHAR(255),
  PRIMARY KEY (`key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `bank_accounts` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `name` VARCHAR(255) NOT NULL,
  `account_no_enc` VARCHAR(255),
  `ifsc` VARCHAR(255),
  `opening_balance` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `account_id` BIGINT,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `bank_transactions` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `bank_account_id` BIGINT NOT NULL,
  `txn_date` DATE NOT NULL,
  `description` LONGTEXT,
  `debit` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `credit` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `reconciled` TINYINT(1) NOT NULL DEFAULT 0,
  `matched_voucher_id` BIGINT,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `stock_lots` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `item_id` BIGINT NOT NULL,
  `location_id` BIGINT,
  `source_type` VARCHAR(255) NOT NULL,
  `source_ref_id` BIGINT,
  `vendor_party_id` BIGINT,
  `lot_date` DATE NOT NULL,
  `qty_in` DECIMAL(12, 4) NOT NULL,
  `qty_remaining` DECIMAL(12, 4) NOT NULL,
  `rate` DECIMAL(15, 2) NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `stock_lot_consumptions` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `lot_id` BIGINT,
  `item_id` BIGINT NOT NULL,
  `consumed_qty` DECIMAL(12, 4) NOT NULL,
  `sale_ref_type` VARCHAR(255) NOT NULL,
  `sale_ref_id` BIGINT,
  `sale_date` DATE NOT NULL,
  `sale_rate` DECIMAL(15, 2) NOT NULL,
  `purchase_rate` DECIMAL(15, 2) NOT NULL,
  `profit` DECIMAL(15, 2) NOT NULL,
  `customer_party_id` BIGINT,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `stock_transfers` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `transfer_no` VARCHAR(255) NOT NULL,
  `transfer_date` DATE NOT NULL,
  `from_location_id` BIGINT NOT NULL,
  `to_location_id` BIGINT NOT NULL,
  `transfer_kind` VARCHAR(255) NOT NULL,
  `taxable_value` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `igst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `total_value` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `status` VARCHAR(50) NOT NULL DEFAULT 'posted',
  `voucher_id` BIGINT,
  `created_by` BIGINT,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `stock_transfer_lines` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `transfer_id` BIGINT NOT NULL,
  `item_id` BIGINT NOT NULL,
  `qty` DECIMAL(12, 4) NOT NULL,
  `rate` DECIMAL(15, 2) NOT NULL,
  `taxable_value` DECIMAL(15, 2) NOT NULL,
  `igst` DECIMAL(15, 2) NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `purchase_orders` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `po_no` VARCHAR(255) NOT NULL,
  `vendor_id` BIGINT NOT NULL,
  `location_id` BIGINT,
  `po_date` DATE NOT NULL,
  `expected_date` DATE,
  `status` VARCHAR(50) NOT NULL DEFAULT 'open',
  `taxable_value` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `cgst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `sgst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `igst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `total_amount` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `invoice_id` BIGINT,
  `created_by` BIGINT,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `purchase_order_lines` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `po_id` BIGINT NOT NULL,
  `item_id` BIGINT NOT NULL,
  `qty` DECIMAL(12, 4) NOT NULL,
  `rate` DECIMAL(15, 2) NOT NULL,
  `taxable_value` DECIMAL(15, 2) NOT NULL,
  `gst_rate` DECIMAL(15, 2) NOT NULL,
  `cgst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `sgst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `igst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `amount` DECIMAL(15, 2) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `sales_orders` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `so_no` VARCHAR(255) NOT NULL,
  `customer_id` BIGINT NOT NULL,
  `location_id` BIGINT,
  `so_date` DATE NOT NULL,
  `expected_date` DATE,
  `status` VARCHAR(50) NOT NULL DEFAULT 'open',
  `taxable_value` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `cgst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `sgst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `igst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `total_amount` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `invoice_id` BIGINT,
  `created_by` BIGINT,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `sales_order_lines` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `so_id` BIGINT NOT NULL,
  `item_id` BIGINT NOT NULL,
  `qty` DECIMAL(12, 4) NOT NULL,
  `rate` DECIMAL(15, 2) NOT NULL,
  `taxable_value` DECIMAL(15, 2) NOT NULL,
  `gst_rate` DECIMAL(15, 2) NOT NULL,
  `cgst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `sgst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `igst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `amount` DECIMAL(15, 2) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `quotations` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `quote_no` VARCHAR(255) NOT NULL,
  `customer_id` BIGINT NOT NULL,
  `location_id` BIGINT,
  `quote_date` DATE NOT NULL,
  `valid_until` VARCHAR(255),
  `place_of_supply` VARCHAR(255),
  `status` VARCHAR(50) NOT NULL DEFAULT 'open',
  `taxable_value` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `cgst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `sgst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `igst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `total_amount` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `terms` VARCHAR(255),
  `notes` LONGTEXT,
  `sales_order_id` BIGINT,
  `invoice_id` BIGINT,
  `created_by` BIGINT,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `quotation_lines` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `quotation_id` BIGINT NOT NULL,
  `item_id` BIGINT NOT NULL,
  `hsn_sac` VARCHAR(50),
  `uom` VARCHAR(50),
  `qty` DECIMAL(12, 4) NOT NULL,
  `rate` DECIMAL(15, 2) NOT NULL,
  `discount_pct` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `taxable_value` DECIMAL(15, 2) NOT NULL,
  `gst_rate` DECIMAL(15, 2) NOT NULL,
  `cgst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `sgst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `igst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `amount` DECIMAL(15, 2) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `credit_notes` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `cn_no` VARCHAR(255) NOT NULL,
  `invoice_id` BIGINT,
  `party_id` BIGINT NOT NULL,
  `location_id` BIGINT,
  `cn_date` DATE NOT NULL,
  `reason` VARCHAR(255),
  `taxable_value` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `cgst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `sgst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `igst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `total_amount` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `status` VARCHAR(50) NOT NULL DEFAULT 'posted',
  `voucher_id` BIGINT,
  `created_by` BIGINT,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `credit_note_lines` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `cn_id` BIGINT NOT NULL,
  `item_id` BIGINT NOT NULL,
  `qty` DECIMAL(12, 4) NOT NULL,
  `rate` DECIMAL(15, 2) NOT NULL,
  `taxable_value` DECIMAL(15, 2) NOT NULL,
  `gst_rate` DECIMAL(15, 2) NOT NULL,
  `cgst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `sgst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `igst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `amount` DECIMAL(15, 2) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `debit_notes` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `dn_no` VARCHAR(255) NOT NULL,
  `invoice_id` BIGINT,
  `party_id` BIGINT NOT NULL,
  `location_id` BIGINT,
  `dn_date` DATE NOT NULL,
  `reason` VARCHAR(255),
  `taxable_value` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `cgst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `sgst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `igst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `total_amount` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `status` VARCHAR(50) NOT NULL DEFAULT 'posted',
  `voucher_id` BIGINT,
  `created_by` BIGINT,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `debit_note_lines` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `dn_id` BIGINT NOT NULL,
  `item_id` BIGINT NOT NULL,
  `qty` DECIMAL(12, 4) NOT NULL,
  `rate` DECIMAL(15, 2) NOT NULL,
  `taxable_value` DECIMAL(15, 2) NOT NULL,
  `gst_rate` DECIMAL(15, 2) NOT NULL,
  `cgst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `sgst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `igst` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `amount` DECIMAL(15, 2) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `fixed_assets` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `asset_code` VARCHAR(255) NOT NULL,
  `name` VARCHAR(255) NOT NULL,
  `category` VARCHAR(255),
  `department` VARCHAR(255),
  `branch_name` VARCHAR(255),
  `location_id` BIGINT,
  `purchase_date` DATE NOT NULL,
  `date_put_to_use` VARCHAR(255),
  `cost` DECIMAL(15, 2) NOT NULL,
  `salvage_value` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `useful_life_years` DECIMAL(15, 2) NOT NULL,
  `depreciation_method` VARCHAR(255) NOT NULL DEFAULT 'straight_line',
  `wdv_rate` DECIMAL(15, 2),
  `accumulated_depreciation` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `co_act_block` VARCHAR(255),
  `it_act_block` VARCHAR(255),
  `co_act_accum_depreciation` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `it_act_accum_depreciation` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `status` VARCHAR(50) NOT NULL DEFAULT 'active',
  `disposed_date` DATE,
  `disposal_amount` DECIMAL(15, 2),
  `payment_mode` VARCHAR(255) DEFAULT 'bank',
  `acquisition_voucher_id` BIGINT,
  `created_by` BIGINT,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `asset_transfers` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `asset_id` BIGINT NOT NULL,
  `from_location_id` BIGINT,
  `to_location_id` BIGINT NOT NULL,
  `transfer_date` DATE NOT NULL,
  `remarks` VARCHAR(255),
  `transferred_by` BIGINT,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `asset_depreciation_runs` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `period_month` BIGINT NOT NULL,
  `period_year` BIGINT NOT NULL,
  `voucher_id` BIGINT,
  `total_amount` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `run_by` BIGINT,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `asset_depreciation_lines` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `run_id` BIGINT NOT NULL,
  `asset_id` BIGINT NOT NULL,
  `amount` DECIMAL(15, 2) NOT NULL,
  `book_value_after` DECIMAL(15, 2) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `gstr2_reconciliation_runs` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `run_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `run_by` BIGINT,
  `uploaded_count` BIGINT NOT NULL DEFAULT 0,
  `matched_count` BIGINT NOT NULL DEFAULT 0,
  `mismatched_count` BIGINT NOT NULL DEFAULT 0,
  `only_in_2a_count` BIGINT NOT NULL DEFAULT 0,
  `only_in_books_count` BIGINT NOT NULL DEFAULT 0,
  `results_json` LONGTEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `budgets` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `account_id` BIGINT NOT NULL,
  `period_start` VARCHAR(255) NOT NULL,
  `period_end` VARCHAR(255) NOT NULL,
  `amount` DECIMAL(15, 2) NOT NULL,
  `notes` LONGTEXT,
  `created_by` BIGINT,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `direct_stock_entries` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `period_month` BIGINT NOT NULL,
  `period_year` BIGINT NOT NULL,
  `item_name` VARCHAR(255) NOT NULL,
  `opening_qty` DECIMAL(12, 4),
  `opening_value` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `closing_qty` DECIMAL(12, 4),
  `closing_value` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `notes` LONGTEXT,
  `created_by` BIGINT,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `sector_group_rules` (
  `group_id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `line` VARCHAR(255) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `attachments` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `entity_type` VARCHAR(255) NOT NULL,
  `entity_id` BIGINT NOT NULL,
  `filename` VARCHAR(255) NOT NULL,
  `stored_name` VARCHAR(255) NOT NULL,
  `mime_type` VARCHAR(255),
  `size_bytes` BIGINT,
  `uploaded_by` BIGINT,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `payment_allocations` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `voucher_id` BIGINT NOT NULL,
  `invoice_id` BIGINT NOT NULL,
  `party_id` BIGINT NOT NULL,
  `amount` DECIMAL(15, 2) NOT NULL,
  `created_at` DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `tax_adjustments` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `fy_start` BIGINT NOT NULL,
  `kind` VARCHAR(255) NOT NULL,
  `description` LONGTEXT NOT NULL,
  `section` VARCHAR(255),
  `amount` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `created_at` DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `tax_params` (
  `fy_start` BIGINT NOT NULL,
  `key` VARCHAR(255) NOT NULL,
  `value` VARCHAR(255)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `boms` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `bom_code` VARCHAR(255) NOT NULL,
  `item_id` BIGINT NOT NULL,
  `level` VARCHAR(255) NOT NULL,
  `batch_qty` DECIMAL(12, 4) NOT NULL DEFAULT 1,
  `labour_per_batch` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `overhead_mode` VARCHAR(255) NOT NULL DEFAULT 'per_unit',
  `overhead_value` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `packing_per_unit` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `freight_in_pct` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `scrap_pct` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `notes` LONGTEXT,
  `is_active` TINYINT(1) NOT NULL DEFAULT 1,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `bom_lines` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `bom_id` BIGINT NOT NULL,
  `component_item_id` BIGINT NOT NULL,
  `qty` DECIMAL(12, 4) NOT NULL,
  `wastage_pct` DECIMAL(15, 2) NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `production_orders` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `order_no` VARCHAR(255) NOT NULL,
  `order_date` DATE NOT NULL,
  `bom_id` BIGINT NOT NULL,
  `output_item_id` BIGINT NOT NULL,
  `location_id` BIGINT,
  `qty_produced` DECIMAL(12, 4) NOT NULL,
  `std_material` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `std_conversion` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `material_cost` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `freight_cost` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `labour_cost` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `overhead_cost` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `packing_cost` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `total_cost` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `unit_cost` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `std_unit_cost` DECIMAL(15, 2) NOT NULL DEFAULT 0,
  `notes` LONGTEXT,
  `created_by` BIGINT,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `production_order_lines` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `order_id` BIGINT NOT NULL,
  `item_id` BIGINT NOT NULL,
  `qty` DECIMAL(12, 4) NOT NULL,
  `amount` DECIMAL(15, 2) NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE IF NOT EXISTS `config_lookups` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `category` VARCHAR(100) NOT NULL,
  `code` VARCHAR(100) NOT NULL,
  `label` VARCHAR(150) NOT NULL,
  `sort_order` INT NOT NULL DEFAULT 0,
  `is_active` TINYINT(1) NOT NULL DEFAULT 1,
  `is_system` TINYINT(1) NOT NULL DEFAULT 0,
  UNIQUE KEY `uk_lookup_cat_code` (`category`, `code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `config_gst_rates` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `rate_pct` DECIMAL(5, 2) NOT NULL UNIQUE,
  `label` VARCHAR(50) NOT NULL,
  `is_active` TINYINT(1) NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `config_tds_sections` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `section_code` VARCHAR(50) NOT NULL UNIQUE,
  `description` VARCHAR(255) NOT NULL,
  `rate_individual` DECIMAL(5, 2) NOT NULL,
  `rate_company` DECIMAL(5, 2) NOT NULL,
  `threshold_amount` DECIMAL(15, 2) NOT NULL DEFAULT 0.00,
  `is_active` TINYINT(1) NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `config_voucher_series` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `doc_type` VARCHAR(100) NOT NULL UNIQUE,
  `prefix` VARCHAR(50) NOT NULL,
  `suffix` VARCHAR(50),
  `next_number` BIGINT NOT NULL DEFAULT 1,
  `zero_pad_length` INT NOT NULL DEFAULT 4,
  `fy_based` TINYINT(1) NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `config_services` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `service_key` VARCHAR(100) NOT NULL UNIQUE,
  `service_group` VARCHAR(100) NOT NULL,
  `label` VARCHAR(150) NOT NULL,
  `hint` VARCHAR(255),
  `is_active` TINYINT(1) NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `config_service_periods` (
  `service_key` VARCHAR(100) NOT NULL PRIMARY KEY,
  `is_enabled` TINYINT(1) NOT NULL DEFAULT 1,
  `period_from` DATE,
  `period_to` DATE,
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `config_document_templates` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `template_type` VARCHAR(100) NOT NULL UNIQUE,
  `header_title` VARCHAR(255),
  `terms_and_conditions` LONGTEXT,
  `footer_note` VARCHAR(255),
  `show_qr` TINYINT(1) NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET FOREIGN_KEY_CHECKS = 1;
