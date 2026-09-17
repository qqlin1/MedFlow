CREATE TABLE med_department (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name        VARCHAR(64) NOT NULL
        COMMENT 'Department display name',
    status      VARCHAR(16) NOT NULL DEFAULT 'ENABLED'
        COMMENT 'ENABLED/DISABLED',
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),

    UNIQUE KEY uk_department_name (name),

    KEY idx_department_status_name (
        status,
        name
    ),

    CONSTRAINT chk_department_status CHECK (
        status IN ('ENABLED', 'DISABLED')
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Minimal outpatient department';


CREATE TABLE med_doctor (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id         BIGINT UNSIGNED NOT NULL
        COMMENT 'Logical sys_user.id; Service requires DOCTOR role',
    department_id   BIGINT UNSIGNED NOT NULL
        COMMENT 'Logical med_department.id',
    name            VARCHAR(64) NOT NULL
        COMMENT 'Doctor display name',
    status          VARCHAR(16) NOT NULL DEFAULT 'ENABLED'
        COMMENT 'ENABLED/DISABLED',
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),

    UNIQUE KEY uk_doctor_user_id (user_id),

    KEY idx_doctor_department_status_name (
        department_id,
        status,
        name
    ),

    CONSTRAINT chk_doctor_status CHECK (
        status IN ('ENABLED', 'DISABLED')
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Doctor business identity bound to one account and department';


CREATE TABLE med_clinic_room (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    department_id   BIGINT UNSIGNED NOT NULL
        COMMENT 'Logical med_department.id',
    name            VARCHAR(64) NOT NULL
        COMMENT 'Clinic room display name',
    status          VARCHAR(16) NOT NULL DEFAULT 'ENABLED'
        COMMENT 'ENABLED/DISABLED',
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),

    UNIQUE KEY uk_clinic_room_department_name (
        department_id,
        name
    ),

    KEY idx_clinic_room_department_status_name (
        department_id,
        status,
        name
    ),

    CONSTRAINT chk_clinic_room_status CHECK (
        status IN ('ENABLED', 'DISABLED')
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Clinic room belonging to one department';