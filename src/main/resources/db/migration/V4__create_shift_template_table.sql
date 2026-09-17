-- A reusable outpatient shift definition. A daily med_schedule stores a
-- snapshot of this template after an administrator selects it.
CREATE TABLE med_shift_template (
    id                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code                    VARCHAR(32) NOT NULL
        COMMENT 'Stable business code, for example MORNING',
    name                    VARCHAR(64) NOT NULL
        COMMENT 'Display name shown to administrators',
    start_time              TIME NOT NULL
        COMMENT 'Inclusive outpatient shift start',
    end_time                TIME NOT NULL
        COMMENT 'Exclusive outpatient shift end',
    slot_duration_minutes   SMALLINT UNSIGNED NOT NULL
        COMMENT 'Fixed duration of each generated slot',
    capacity_per_slot       INT UNSIGNED NOT NULL
        COMMENT 'Default appointment capacity of each generated slot',
    status                  VARCHAR(16) NOT NULL DEFAULT 'ENABLED'
        COMMENT 'ENABLED/DISABLED',
    created_at              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_shift_template_code (code),
    KEY idx_shift_template_status_start (status, start_time),

    CONSTRAINT chk_shift_template_time_range CHECK (
        start_time < end_time
    ),
    CONSTRAINT chk_shift_template_slot_duration CHECK (
        slot_duration_minutes > 0
    ),
    CONSTRAINT chk_shift_template_capacity CHECK (
        capacity_per_slot > 0
    ),
    CONSTRAINT chk_shift_template_status CHECK (
        status IN ('ENABLED', 'DISABLED')
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Reusable outpatient shift template';

INSERT INTO med_shift_template (
    code,
    name,
    start_time,
    end_time,
    slot_duration_minutes,
    capacity_per_slot,
    status
)
VALUES
    ('MORNING', '上午门诊', '08:00:00', '12:00:00', 30, 10, 'ENABLED'),
    ('AFTERNOON', '下午门诊', '13:00:00', '17:00:00', 30, 10, 'ENABLED');

-- Keep med_schedule as the dated, immutable execution record. The values
-- copied here prevent a later template edit from changing an existing draft.
ALTER TABLE med_schedule
    ADD COLUMN shift_template_id BIGINT UNSIGNED NULL
        COMMENT 'Logical med_shift_template.id; NULL supports future exception schedules'
        AFTER clinic_room_id,
    ADD COLUMN slot_duration_minutes SMALLINT UNSIGNED NULL
        COMMENT 'Snapshot copied from selected shift template'
        AFTER end_time,
    ADD COLUMN capacity_per_slot INT UNSIGNED NULL
        COMMENT 'Snapshot copied from selected shift template'
        AFTER slot_duration_minutes,
    ADD KEY idx_schedule_shift_template (shift_template_id);
