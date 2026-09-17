package com.qqlin.medflow.patient.repository;

import com.qqlin.medflow.patient.domain.Patient;
import com.qqlin.medflow.patient.mapper.PatientInsertParam;
import com.qqlin.medflow.patient.mapper.PatientMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Adapts immutable domain records to the mutable MyBatis insert parameter.
 */
@Repository
public class PatientRepository {

    private final PatientMapper patientMapper;

    public PatientRepository(PatientMapper patientMapper) {
        this.patientMapper = patientMapper;
    }

    public long insert(
            long ownerUserId,
            Patient patient
    ) {
        PatientInsertParam parameter = new PatientInsertParam(
                ownerUserId,
                patient
        );

        int affectedRows = patientMapper.insert(parameter);

        if (affectedRows != 1 || parameter.getId() == null) {
            throw new IllegalStateException(
                    "创建就诊人后没有获得主键"
            );
        }

        return parameter.getId();
    }

    public Optional<Patient> findByIdAndOwnerUserId(
            long patientId,
            long ownerUserId
    ) {
        return Optional.ofNullable(
                patientMapper.findByIdAndOwnerUserId(
                        patientId,
                        ownerUserId
                )
        );
    }

    public List<Patient> findByOwnerUserId(long ownerUserId) {
        return patientMapper.findByOwnerUserId(ownerUserId);
    }
}
