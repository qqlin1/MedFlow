-- Creation of an appointment is a state transition worth preserving as an
-- immutable fact. Later confirmation, cancellation and expiration append more
-- rows rather than overwriting this history.
CREATE TABLE med_appointment_status_log (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    appointment_id  BIGINT UNSIGNED NOT NULL,
    from_status     VARCHAR(32) NULL
        COMMENT 'NULL for initial creation',
    to_status       VARCHAR(32) NOT NULL,
    event_type      VARCHAR(32) NOT NULL
        COMMENT 'CREATED/CONFIRMED/CANCELLED/EXPIRED',
    actor_user_id   BIGINT UNSIGNED NULL
        COMMENT 'NULL is reserved for a later system timeout worker',
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    KEY idx_appointment_status_log (
        appointment_id,
        created_at,
        id
    ),

    CONSTRAINT chk_appointment_status_log_to_status CHECK (
        to_status IN (
            'PENDING_CONFIRMATION',
            'BOOKED',
            'CANCELLED',
            'EXPIRED'
        )
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Immutable appointment state transition history';

-- One row represents one client command, not one appointment business rule.
-- The unique key distinguishes a retry of K1 from a new command using K2.
CREATE TABLE sys_idempotency_record (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id             BIGINT UNSIGNED NOT NULL,
    operation_type      VARCHAR(32) NOT NULL,
    idempotency_key     VARCHAR(64) NOT NULL,
    request_hash        CHAR(64) NOT NULL
        COMMENT 'SHA-256 of normalized request content',
    status              VARCHAR(16) NOT NULL
        COMMENT 'PROCESSING/SUCCESS',
    appointment_id      BIGINT UNSIGNED NULL,
    result_json         JSON NULL
        COMMENT 'Frozen successful response for retry replay',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency_user_operation_key (
        user_id,
        operation_type,
        idempotency_key
    ),
    KEY idx_idempotency_created_at (created_at),

    CONSTRAINT chk_idempotency_status CHECK (
        status IN ('PROCESSING', 'SUCCESS')
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Idempotent command record for write APIs';
