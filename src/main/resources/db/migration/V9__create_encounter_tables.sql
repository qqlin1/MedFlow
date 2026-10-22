-- Appointment reserves capacity; encounter records actual attendance. No terminal
-- encounter transition returns that already used/missed capacity to the slot.
CREATE TABLE med_encounter (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    appointment_id BIGINT UNSIGNED NOT NULL,
    doctor_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'WAITING',
    checked_in_at DATETIME(3) NOT NULL,
    started_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    attending_doctor_id BIGINT UNSIGNED GENERATED ALWAYS AS (
        CASE WHEN status = 'IN_PROGRESS' THEN doctor_id ELSE NULL END
    ) VIRTUAL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_encounter_appointment (appointment_id),
    UNIQUE KEY uk_encounter_attending_doctor (attending_doctor_id),
    KEY idx_encounter_doctor_status (doctor_id, status, checked_in_at, id),
    CONSTRAINT chk_encounter_status CHECK (status IN ('WAITING','IN_PROGRESS','COMPLETED','SKIPPED')),
    CONSTRAINT chk_encounter_times CHECK (
        (status = 'WAITING' AND started_at IS NULL AND completed_at IS NULL)
        OR (status = 'SKIPPED' AND started_at IS NULL AND completed_at IS NULL)
        OR (status = 'IN_PROGRESS' AND started_at IS NOT NULL AND completed_at IS NULL)
        OR (status = 'COMPLETED' AND started_at IS NOT NULL AND completed_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE med_medical_record (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    encounter_id BIGINT UNSIGNED NOT NULL,
    chief_complaint TEXT NOT NULL,
    present_illness TEXT NOT NULL,
    diagnosis TEXT NOT NULL,
    advice TEXT NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_medical_record_encounter (encounter_id)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE med_encounter_status_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    encounter_id BIGINT UNSIGNED NOT NULL,
    from_status VARCHAR(16) NULL,
    to_status VARCHAR(16) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    actor_user_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_encounter_log (encounter_id, created_at, id),
    CONSTRAINT chk_encounter_log_to CHECK (to_status IN ('WAITING','IN_PROGRESS','COMPLETED','SKIPPED')),
    CONSTRAINT chk_encounter_log_from CHECK (from_status IS NULL OR from_status IN ('WAITING','IN_PROGRESS','COMPLETED','SKIPPED'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
