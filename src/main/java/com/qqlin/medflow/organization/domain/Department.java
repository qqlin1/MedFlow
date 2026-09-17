package com.qqlin.medflow.organization.domain;

public record Department(
        long id,
        String name,
        ResourceStatus status
) {
}
