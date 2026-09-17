package com.qqlin.medflow.organization.domain;

public record CreateDoctorCommand(
        String username,
        long departmentId,
        String doctorName
) {
}
