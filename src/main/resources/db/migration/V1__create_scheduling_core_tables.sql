-- MedFlow scheduling core schema.
-- This migration intentionally creates only schedule, slot, and appointment.
-- Identity, organization, status history, idempotency, and outbox tables will
-- be introduced by later migrations when their modules are implemented.
-- Foreign keys are intentionally omitted; relationship columns are indexed and
-- referential/business validity will be enforced by the application and tests.

CREATE TABLE med_schedule (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    doctor_id       BIGINT UNSIGNED NOT NULL COMMENT 'Doctor business identity id',
    clinic_room_id  BIGINT UNSIGNED NOT NULL COMMENT 'Clinic room id',
    work_date       DATE NOT NULL COMMENT 'Schedule date',
    start_time      TIME NOT NULL COMMENT 'Start time, inclusive',
    end_time        TIME NOT NULL COMMENT 'End time, exclusive',
    status          VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        COMMENT 'DRAFT/PUBLISHED/CLOSED/CANCELLED',
    active_flag     TINYINT UNSIGNED
        GENERATED ALWAYS AS (
            CASE
                WHEN status IN ('DRAFT', 'PUBLISHED') THEN 1
                ELSE NULL
            END
        ) VIRTUAL COMMENT '1 for an active schedule; NULL for a terminal schedule',
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_schedule_doctor_start_active
        (doctor_id, work_date, start_time, active_flag),
    UNIQUE KEY uk_schedule_room_start_active
        (clinic_room_id, work_date, start_time, active_flag),
    KEY idx_schedule_doctor_overlap
        (doctor_id, work_date, active_flag, start_time, end_time),
    KEY idx_schedule_room_overlap
        (clinic_room_id, work_date, active_flag, start_time, end_time),
    KEY idx_schedule_work_date (work_date, start_time),

    CONSTRAINT chk_schedule_time_range CHECK (start_time < end_time),
    CONSTRAINT chk_schedule_status CHECK (
        status IN ('DRAFT', 'PUBLISHED', 'CLOSED', 'CANCELLED')
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Doctor clinic schedule';

CREATE TABLE med_slot (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    schedule_id         BIGINT UNSIGNED NOT NULL COMMENT 'Owning med_schedule id',
    work_date           DATE NOT NULL COMMENT 'Immutable copy of schedule date for slot queries',
    start_time          TIME NOT NULL COMMENT 'Slot start time, inclusive',
    end_time            TIME NOT NULL COMMENT 'Slot end time, exclusive',
    booking_deadline    DATETIME(3) NOT NULL COMMENT 'Last time at which this slot can be booked',
    total_capacity      INT UNSIGNED NOT NULL COMMENT 'Total appointment capacity',
    remaining_capacity  INT UNSIGNED NOT NULL COMMENT 'Remaining appointment capacity',
    status              VARCHAR(16) NOT NULL DEFAULT 'OPEN'
        COMMENT 'OPEN/CLOSED/CANCELLED; full is represented by remaining_capacity=0',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_slot_schedule_time (schedule_id, start_time, end_time),
    KEY idx_slot_available (work_date, status, start_time),

    CONSTRAINT chk_slot_time_range CHECK (start_time < end_time),
    CONSTRAINT chk_slot_capacity CHECK (
        total_capacity > 0
        AND remaining_capacity <= total_capacity
    ),
    CONSTRAINT chk_slot_status CHECK (
        status IN ('OPEN', 'CLOSED', 'CANCELLED')
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Finite appointment slot';

CREATE TABLE med_appointment (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    patient_id        BIGINT UNSIGNED NOT NULL COMMENT 'Patient business identity id',
    slot_id           BIGINT UNSIGNED NOT NULL COMMENT 'Booked med_slot id',
    status            VARCHAR(32) NOT NULL DEFAULT 'PENDING_CONFIRMATION'
        COMMENT 'PENDING_CONFIRMATION/BOOKED/CANCELLED/EXPIRED',
    confirm_deadline  DATETIME(3) NOT NULL COMMENT 'Confirmation deadline',
    cancel_deadline   DATETIME(3) NOT NULL COMMENT 'Cancellation deadline',
    active_flag       TINYINT UNSIGNED
        GENERATED ALWAYS AS (
            CASE
                WHEN status IN ('PENDING_CONFIRMATION', 'BOOKED') THEN 1
                ELSE NULL
            END
        ) VIRTUAL COMMENT '1 for an active appointment; NULL for a terminal appointment',
    created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_appointment_patient_slot_active
        (patient_id, slot_id, active_flag),
    KEY idx_appointment_patient_status_created
        (patient_id, status, created_at),
    KEY idx_appointment_slot_created (slot_id, created_at),
    KEY idx_appointment_pending_timeout (status, confirm_deadline),

    CONSTRAINT chk_appointment_status CHECK (
        status IN ('PENDING_CONFIRMATION', 'BOOKED', 'CANCELLED', 'EXPIRED')
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Patient appointment and current lifecycle state';
