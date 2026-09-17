package com.qqlin.medflow.organization.domain;

public record ClinicRoom(
        long id,
        long departmentId,
        String name,
        ResourceStatus status
) {
}
