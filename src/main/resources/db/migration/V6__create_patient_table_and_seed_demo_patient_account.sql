-- A patient is a business record owned by one PATIENT login account.
-- It deliberately has no foreign key: this project keeps relationship
-- columns indexed and enforces ownership/business rules in the application.
CREATE TABLE med_patient (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    owner_user_id   BIGINT UNSIGNED NOT NULL
        COMMENT 'sys_user.id of the PATIENT account that owns this record',
    name            VARCHAR(64) NOT NULL
        COMMENT 'Fictional patient name',
    gender          VARCHAR(16) NOT NULL
        COMMENT 'MALE/FEMALE/UNKNOWN',
    birth_date      DATE NOT NULL,
    phone           VARCHAR(32) NOT NULL
        COMMENT 'Fictional contact number; mask it in normal responses',
    relationship    VARCHAR(32) NOT NULL
        COMMENT 'Relationship between owner and patient, for example SELF',
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    KEY idx_patient_owner_id (owner_user_id, id),

    CONSTRAINT chk_patient_gender CHECK (
        gender IN ('MALE', 'FEMALE', 'UNKNOWN')
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Fictional appointment patient owned by one user account';

-- Development account used to exercise patient ownership APIs locally.
-- Production account provisioning will be separated before deployment.
INSERT INTO sys_user (
    username,
    password_hash,
    role,
    status,
    token_version
)
VALUES (
    'patient.li',
    '$2a$10$6PYDz9TC0ACcAXuuMqzhZujLRrCpVp.gP5teptSAfiFq37sJYrphi',
    'PATIENT',
    'ENABLED',
    0
);
