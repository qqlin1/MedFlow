-- Opt-in demonstration fixtures only. The registry remembers seeded identities
-- and dates so startup never restores a patient's or doctor's later actions.
CREATE TABLE med_demo_seed_registry (
    seed_key VARCHAR(160) NOT NULL,
    seed_date DATE NULL,
    entity_type VARCHAR(32) NOT NULL,
    entity_id BIGINT UNSIGNED NULL,
    note VARCHAR(255) NOT NULL DEFAULT '',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY(seed_key),
    KEY idx_demo_seed_date(seed_date,entity_type)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
