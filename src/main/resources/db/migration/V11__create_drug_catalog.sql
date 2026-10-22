-- Catalog values are mutable; prescriptions keep their own price/name snapshots.
CREATE TABLE med_drug (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    specification VARCHAR(100) NOT NULL,
    unit VARCHAR(20) NOT NULL,
    unit_price DECIMAL(8,2) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_drug_code (code),
    KEY idx_drug_status_name (status, name, id),
    KEY idx_drug_name (name, id),
    CONSTRAINT chk_drug_price CHECK (unit_price > 0 AND unit_price <= 999999.99),
    CONSTRAINT chk_drug_status CHECK (status IN ('ENABLED', 'DISABLED')),
    CONSTRAINT chk_drug_required_text CHECK (
        CHAR_LENGTH(TRIM(code)) > 0 AND CHAR_LENGTH(TRIM(name)) > 0
        AND CHAR_LENGTH(TRIM(specification)) > 0 AND CHAR_LENGTH(TRIM(unit)) > 0
    )
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
