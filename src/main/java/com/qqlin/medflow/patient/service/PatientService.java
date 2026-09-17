package com.qqlin.medflow.patient.service;

import com.qqlin.medflow.patient.domain.CreatePatientCommand;
import com.qqlin.medflow.patient.domain.Patient;
import com.qqlin.medflow.patient.repository.PatientRepository;
import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class PatientService {

    private final PatientRepository patientRepository;

    public PatientService(PatientRepository patientRepository) {
        this.patientRepository = patientRepository;
    }

    public Patient create(
            long ownerUserId,
            CreatePatientCommand command
    ) {
        if (ownerUserId <= 0 || command == null) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED
            );
        }

        Patient patient = new Patient(
                0,
                ownerUserId,
                normalize(command.name()),
                command.gender(),
                command.birthDate(),
                normalize(command.phone()),
                normalize(command.relationship())
        );

        validatePatient(patient);

        long patientId = patientRepository.insert(
                ownerUserId,
                patient
        );

        return new Patient(
                patientId,
                patient.ownerUserId(),
                patient.name(),
                patient.gender(),
                patient.birthDate(),
                patient.phone(),
                patient.relationship()
        );
    }

    public List<Patient> findOwnedPatients(long ownerUserId) {
        validateOwnerUserId(ownerUserId);

        return patientRepository.findByOwnerUserId(ownerUserId);
    }

    /**
     * An unowned patient is deliberately indistinguishable from a nonexistent
     * patient to the caller, so guessing identifiers reveals no ownership data.
     */
    public Patient getOwnedPatient(
            long ownerUserId,
            long patientId
    ) {
        validateOwnerUserId(ownerUserId);

        if (patientId <= 0) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED
            );
        }

        return patientRepository
                .findByIdAndOwnerUserId(patientId, ownerUserId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.PATIENT_NOT_FOUND
                ));
    }

    private void validateOwnerUserId(long ownerUserId) {
        if (ownerUserId <= 0) {
            throw new BusinessException(
                    ErrorCode.UNAUTHORIZED
            );
        }
    }

    private void validatePatient(Patient patient) {
        if (patient.gender() == null
                || patient.birthDate() == null
                || !patient.birthDate().isBefore(LocalDate.now())
                || patient.name().length() > 64
                || patient.phone().length() > 32
                || patient.relationship().length() > 32) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED
            );
        }
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED
            );
        }

        return value.trim();
    }
}
