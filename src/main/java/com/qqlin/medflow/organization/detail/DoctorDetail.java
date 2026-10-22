package com.qqlin.medflow.organization.detail;

/** Public catalogue fields only: never cache credentials, user IDs or patient data. */
public record DoctorDetail(long id, long departmentId, String name, String departmentName,
        String title, String specialty, String introduction) {
    public DoctorDetail(long id, long departmentId, String name, String departmentName) {
        this(id, departmentId, name, departmentName, "", "", "");
    }
    public DoctorDetail {
        title = title == null ? "" : title;
        specialty = specialty == null ? "" : specialty;
        introduction = introduction == null ? "" : introduction;
    }
}
