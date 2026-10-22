-- Only new appointments created through the application receive these values.
-- Existing rows deliberately remain NULL: current directory data is not a historical snapshot.
-- Logical references follow the existing no-physical-FK schema convention.
ALTER TABLE med_appointment
    ADD COLUMN source_encounter_id BIGINT UNSIGNED NULL COMMENT 'Owned completed encounter for the same patient',
    ADD COLUMN booking_patient_name VARCHAR(64) NULL COMMENT 'Patient name observed at booking',
    ADD COLUMN booking_doctor_name VARCHAR(64) NULL COMMENT 'Doctor name observed at booking',
    ADD COLUMN booking_doctor_title VARCHAR(100) NULL COMMENT 'Doctor title observed at booking, possibly absent',
    ADD COLUMN booking_department_name VARCHAR(64) NULL COMMENT 'Department name observed at booking',
    ADD COLUMN booking_clinic_room_name VARCHAR(64) NULL COMMENT 'Clinic room name observed at booking',
    ADD COLUMN booking_snapshot_at DATETIME(3) NULL COMMENT 'Snapshot captured in creation transaction; NULL means current-directory fallback',
    ADD KEY idx_appointment_source_encounter (source_encounter_id),
    ADD CONSTRAINT chk_appointment_source_encounter CHECK (source_encounter_id IS NULL OR source_encounter_id > 0);
