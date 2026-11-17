-- Fin Vane ERP: Control Database Schema (finvane_control)
-- Generated from Data Model - Columns.csv specification
SET FOREIGN_KEY_CHECKS = 0;

CREATE TABLE IF NOT EXISTS `entities` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `name` VARCHAR(255) NOT NULL,
  `legal_name` VARCHAR(255),
  `sector` VARCHAR(255),
  `industry` VARCHAR(255),
  `business_type` VARCHAR(255) NOT NULL DEFAULT 'goods',
  `pan` VARCHAR(50),
  `gstin` VARCHAR(50),
  `address` LONGTEXT,
  `state_code` VARCHAR(50),
  `country` VARCHAR(255) NOT NULL DEFAULT 'India',
  `date_of_commencement` VARCHAR(255),
  `books_start_date` DATE,
  `fy_start_month` BIGINT NOT NULL DEFAULT 4,
  `is_active` TINYINT(1) NOT NULL DEFAULT 1,
  `created_by` BIGINT,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `constitution` VARCHAR(255),
  `bank_name` VARCHAR(255),
  `bank_account_name` VARCHAR(255),
  `bank_account_no` VARCHAR(255),
  `bank_ifsc` VARCHAR(255),
  `bank_branch` VARCHAR(255),
  `upi_id` BIGINT,
  `gst_registered` TINYINT(1) NOT NULL DEFAULT 1,
  `deactivated_at` DATETIME(3),
  UNIQUE KEY `uk_entities_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `users` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `name` VARCHAR(255) NOT NULL,
  `email` VARCHAR(191) NOT NULL,
  `password_hash` VARCHAR(255) NOT NULL,
  `role` VARCHAR(255) NOT NULL,
  `is_active` TINYINT(1) NOT NULL DEFAULT 1,
  `failed_attempts` TINYINT(1) NOT NULL DEFAULT 0,
  `locked_until` VARCHAR(255),
  `totp_secret` VARCHAR(255),
  `totp_enabled` TINYINT(1) NOT NULL DEFAULT 0,
  `must_change_password` TINYINT(1) NOT NULL DEFAULT 0,
  `password_changed_at` DATETIME(3),
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `email_verified` TINYINT(1) NOT NULL DEFAULT 1,
  `verify_token` VARCHAR(255),
  `verify_expires` VARCHAR(255),
  `mobile` VARCHAR(255),
  `created_by` BIGINT,
  `self_registered` TINYINT(1) NOT NULL DEFAULT 0,
  `designation` VARCHAR(255),
  `permissions_json` LONGTEXT,
  UNIQUE KEY `uk_users_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `audit_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `business_entity_id` BIGINT,
  `user_id` BIGINT,
  `user_email` VARCHAR(255),
  `action` VARCHAR(255) NOT NULL,
  `entity` VARCHAR(255) NOT NULL,
  `entity_id` BIGINT,
  `before_json` LONGTEXT,
  `after_json` LONGTEXT,
  `ip` VARCHAR(255),
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `entity_logos` (
  `entity_id` BIGINT NOT NULL PRIMARY KEY,
  `mime` VARCHAR(255) NOT NULL,
  `data` LONGBLOB NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `control_meta` (
  `key` VARCHAR(255),
  `value` VARCHAR(255),
  PRIMARY KEY (`key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `user_entities` (
  `user_id` BIGINT NOT NULL,
  `entity_id` BIGINT NOT NULL,
  `granted_by` BIGINT,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE IF NOT EXISTS `config_menu_items` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `menu_key` VARCHAR(100) NOT NULL UNIQUE,
  `parent_key` VARCHAR(100),
  `label` VARCHAR(150) NOT NULL,
  `url` VARCHAR(255) NOT NULL,
  `icon` VARCHAR(100),
  `permission_key` VARCHAR(100),
  `sort_order` INT NOT NULL DEFAULT 0,
  `is_active` TINYINT(1) NOT NULL DEFAULT 1,
  `service_key` VARCHAR(100),
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `config_roles` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `role_key` VARCHAR(100) NOT NULL UNIQUE,
  `name` VARCHAR(150) NOT NULL,
  `description` VARCHAR(255),
  `is_system` TINYINT(1) NOT NULL DEFAULT 1,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `config_permissions` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `permission_key` VARCHAR(100) NOT NULL UNIQUE,
  `menu_group` VARCHAR(100) NOT NULL,
  `description` VARCHAR(255) NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `config_role_permissions` (
  `role_id` BIGINT NOT NULL,
  `permission_id` BIGINT NOT NULL,
  PRIMARY KEY (`role_id`, `permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `config_states` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `code` VARCHAR(2) NOT NULL UNIQUE,
  `name` VARCHAR(100) NOT NULL,
  `is_active` TINYINT(1) NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `config_ui_labels` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `label_key` VARCHAR(150) NOT NULL UNIQUE,
  `label_value` LONGTEXT NOT NULL,
  `category` VARCHAR(100) NOT NULL DEFAULT 'general'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET FOREIGN_KEY_CHECKS = 1;
