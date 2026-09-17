package com.qqlin.medflow.patient.domain;

import java.time.LocalDate;

/**
 * A fictional patient used by the appointment domain. It is intentionally
 * distinct from the login account that owns it.
 */
public record Patient(
        long id,
        long ownerUserId,
        String name,
        PatientGender gender,
        LocalDate birthDate,
        String phone,
        String relationship
) {
}
