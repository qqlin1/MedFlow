-- Supports the owner-list fast path when one account owns exactly one patient.
-- The owner-wide multi-patient query retains its existing plan and semantics.
ALTER TABLE med_appointment
    ADD KEY idx_appointment_patient_created_id
        (patient_id, created_at DESC, id DESC);
