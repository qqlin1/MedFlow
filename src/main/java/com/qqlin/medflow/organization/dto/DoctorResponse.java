package com.qqlin.medflow.organization.dto;

import com.qqlin.medflow.organization.domain.Doctor;
import com.qqlin.medflow.organization.domain.ResourceStatus;

public record DoctorResponse(
        long id,
        long userId,
        long departmentId,
        String name,
        ResourceStatus status
) {

    public static DoctorResponse from(Doctor doctor) {
        return new DoctorResponse(
                doctor.id(),
                doctor.userId(),
                doctor.departmentId(),
                doctor.name(),
                doctor.status()
        );
    }
}
