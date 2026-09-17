package com.qqlin.medflow.patient.mapper;

import com.qqlin.medflow.patient.domain.Patient;
import com.qqlin.medflow.patient.domain.PatientGender;

import java.time.LocalDate;

public class PatientInsertParam {

    private final long ownerUserId;
    private final String name;
    private final PatientGender gender;
    private final LocalDate birthDate;
    private final String phone;
    private final String relationship;
    private Long id;

    public PatientInsertParam(long ownerUserId, Patient patient) {
        this.ownerUserId = ownerUserId;
        this.name = patient.name();
        this.gender = patient.gender();
        this.birthDate = patient.birthDate();
        this.phone = patient.phone();
        this.relationship = patient.relationship();
    }

    public long getOwnerUserId() {
        return ownerUserId;
    }

    public String getName() {
        return name;
    }

    public PatientGender getGender() {
        return gender;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public String getPhone() {
        return phone;
    }

    public String getRelationship() {
        return relationship;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
}
