package com.qqlin.medflow.patient.domain;

import java.time.LocalDate;

public record CreatePatientCommand(
        String name,
        PatientGender gender,
        LocalDate birthDate,
        String phone,
        String relationship
) {
}
