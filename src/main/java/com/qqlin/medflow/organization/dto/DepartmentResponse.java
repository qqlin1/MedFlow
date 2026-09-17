package com.qqlin.medflow.organization.dto;

import com.qqlin.medflow.organization.domain.Department;
import com.qqlin.medflow.organization.domain.ResourceStatus;

public record DepartmentResponse(
        long id,
        String name,
        ResourceStatus status
) {

    public static DepartmentResponse from(Department department) {
        return new DepartmentResponse(
                department.id(),
                department.name(),
                department.status()
        );
    }
}
