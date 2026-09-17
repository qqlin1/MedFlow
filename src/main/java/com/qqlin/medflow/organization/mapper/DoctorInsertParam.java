package com.qqlin.medflow.organization.mapper;

import com.qqlin.medflow.organization.domain.ResourceStatus;

public class DoctorInsertParam {

    private final long userId;
    private final long departmentId;
    private final String name;
    private final ResourceStatus status;
    private Long id;

    public DoctorInsertParam(
            long userId,
            long departmentId,
            String name,
            ResourceStatus status
    ) {
        this.userId = userId;
        this.departmentId = departmentId;
        this.name = name;
        this.status = status;
    }

    public long getUserId() {
        return userId;
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
