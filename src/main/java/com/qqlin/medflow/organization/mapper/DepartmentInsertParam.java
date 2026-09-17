package com.qqlin.medflow.organization.mapper;

import com.qqlin.medflow.organization.domain.ResourceStatus;

public class DepartmentInsertParam {

    private final String name;
    private final ResourceStatus status;
    private Long id;

    public DepartmentInsertParam(String name, ResourceStatus status) {
        this.name = name;
        this.status = status;
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
