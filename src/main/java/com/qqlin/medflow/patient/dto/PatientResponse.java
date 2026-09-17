package com.qqlin.medflow.patient.dto;

import com.qqlin.medflow.patient.domain.Patient;
import com.qqlin.medflow.patient.domain.PatientGender;

import java.time.LocalDate;

/**
 * The normal API view must not expose a full contact number.
 */
public record PatientResponse(
        long id,
        String name,
        PatientGender gender,
        LocalDate birthDate,
        String maskedPhone,
        String relationship
) {

    public static PatientResponse from(Patient patient) {
        return new PatientResponse(
                patient.id(),
                patient.name(),
                patient.gender(),
                patient.birthDate(),
                maskPhone(patient.phone()),
                patient.relationship()
        );
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "****";
        }

        return phone.substring(0, 3)
                + "****"
                + phone.substring(phone.length() - 4);
    }
}
