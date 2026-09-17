CREATE TABLE sys_user (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
        COMMENT 'Primary key',

    username        VARCHAR(64) NOT NULL
        COMMENT 'Login username',

    password_hash   VARCHAR(100) NOT NULL
        COMMENT 'BCrypt password hash',

    role            VARCHAR(16) NOT NULL
        COMMENT 'PATIENT/DOCTOR/ADMIN',

    status          VARCHAR(16) NOT NULL DEFAULT 'ENABLED'
        COMMENT 'ENABLED/DISABLED/LOCKED',

    token_version   INT UNSIGNED NOT NULL DEFAULT 0
        COMMENT 'Increment to invalidate issued tokens',

    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),

    UNIQUE KEY uk_sys_user_username (username),

    CONSTRAINT chk_sys_user_role CHECK (
        role IN ('PATIENT', 'DOCTOR', 'ADMIN')
    ),

    CONSTRAINT chk_sys_user_status CHECK (
        status IN ('ENABLED', 'DISABLED', 'LOCKED')
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'System login account';

INSERT INTO sys_user (
    username,
    password_hash,
    role,
    status,
    token_version
) VALUES (
    'admin',
    '$2a$10$7doVoaLkzj.wYStCaQ/ReeRR9gUqf1j95qVmJu6AOozXOCEIr6pKO',
    'ADMIN',
    'ENABLED',
    0
);