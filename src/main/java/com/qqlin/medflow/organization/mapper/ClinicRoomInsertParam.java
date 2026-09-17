package com.qqlin.medflow.organization.mapper;

import com.qqlin.medflow.organization.domain.ResourceStatus;

public class ClinicRoomInsertParam {

    private final long departmentId;
    private final String name;
    private final ResourceStatus status;
    private Long id;

    public ClinicRoomInsertParam(
            long departmentId,
            String name,
            ResourceStatus status
    ) {
        this.departmentId = departmentId;
        this.name = name;
        this.status = status;
    }

    public long getDepartmentId() {
        return departmentId;
    }

    public String getName() {
        return name;
    }

    public ResourceStatus getStatus() {
        return status;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
}
