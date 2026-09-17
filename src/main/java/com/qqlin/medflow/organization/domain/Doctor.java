package com.qqlin.medflow.organization.domain;

public record Doctor(
        long id,
        long userId,
        long departmentId,
        String name,
        ResourceStatus status
) {
}
