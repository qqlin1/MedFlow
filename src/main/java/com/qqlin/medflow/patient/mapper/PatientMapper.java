package com.qqlin.medflow.patient.mapper;

import com.qqlin.medflow.patient.domain.Patient;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PatientMapper {

    int insert(PatientInsertParam parameter);

    Patient findByIdAndOwnerUserId(
            @Param("patientId") long patientId,
            @Param("ownerUserId") long ownerUserId
    );

    List<Patient> findByOwnerUserId(
            @Param("ownerUserId") long ownerUserId
    );
}
