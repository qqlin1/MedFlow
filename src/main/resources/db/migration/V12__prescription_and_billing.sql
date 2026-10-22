-- Price snapshots are immutable once issued. These tables model simulation only:
-- no inventory, pharmacy fulfillment, real payment channel or refund lifecycle.
CREATE TABLE med_prescription (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    encounter_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    total_amount DECIMAL(12,2) NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    issued_at DATETIME(3) NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_prescription_encounter (encounter_id),
    KEY idx_prescription_status_created (status, created_at, id),
    CONSTRAINT chk_prescription_status CHECK (status IN ('DRAFT','ISSUED','DISCARDED')),
    CONSTRAINT chk_prescription_amount CHECK (
        (status='DISCARDED' AND total_amount=0) OR (status IN ('DRAFT','ISSUED') AND total_amount>0)
    ),
    CONSTRAINT chk_prescription_issued CHECK (
        (status IN ('DRAFT','DISCARDED') AND issued_at IS NULL) OR (status='ISSUED' AND issued_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE med_prescription_item (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    prescription_id BIGINT UNSIGNED NOT NULL,
    drug_id BIGINT UNSIGNED NOT NULL,
    drug_code VARCHAR(32) NOT NULL,
    drug_name VARCHAR(100) NOT NULL,
    specification VARCHAR(100) NOT NULL,
    unit VARCHAR(20) NOT NULL,
    quantity INT UNSIGNED NOT NULL,
    instructions VARCHAR(1000) NOT NULL DEFAULT '',
    unit_price DECIMAL(8,2) NOT NULL,
    line_amount DECIMAL(12,2) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_prescription_item_drug (prescription_id,drug_id),
    KEY idx_prescription_item_drug (drug_id),
    CONSTRAINT chk_prescription_quantity CHECK (quantity BETWEEN 1 AND 1000),
    CONSTRAINT chk_prescription_item_price CHECK (unit_price > 0 AND line_amount = unit_price * quantity)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE med_bill (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    prescription_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'UNPAID',
    total_amount DECIMAL(12,2) NOT NULL,
    paid_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_bill_prescription (prescription_id),
    CONSTRAINT chk_bill_status CHECK (status IN ('UNPAID','PAID')),
    CONSTRAINT chk_bill_amount CHECK (total_amount > 0),
    CONSTRAINT chk_bill_paid CHECK (
        (status='UNPAID' AND paid_at IS NULL) OR (status='PAID' AND paid_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE med_mock_payment (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    bill_id BIGINT UNSIGNED NOT NULL,
    paid_by_user_id BIGINT UNSIGNED NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    paid_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mock_payment_bill (bill_id),
    CONSTRAINT chk_mock_payment_amount CHECK (amount > 0)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
