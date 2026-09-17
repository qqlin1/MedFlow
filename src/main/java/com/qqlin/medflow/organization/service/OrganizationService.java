package com.qqlin.medflow.organization.service;

import com.qqlin.medflow.identity.domain.UserAccount;
import com.qqlin.medflow.identity.domain.UserRole;
import com.qqlin.medflow.identity.domain.UserStatus;
import com.qqlin.medflow.identity.repository.UserRepository;
import com.qqlin.medflow.organization.domain.ClinicRoom;
import com.qqlin.medflow.organization.domain.CreateDoctorCommand;
import com.qqlin.medflow.organization.domain.Department;
import com.qqlin.medflow.organization.domain.Doctor;
import com.qqlin.medflow.organization.domain.ResourceStatus;
import com.qqlin.medflow.organization.repository.OrganizationRepository;
import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;

    public OrganizationService(
            OrganizationRepository organizationRepository,
            UserRepository userRepository
    ) {
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
    }

    public Department createDepartment(String name) {
        String normalizedName = normalizeName(name);

        try {
            long departmentId =
                    organizationRepository.insertDepartment(
                            normalizedName
                    );

            return new Department(
                    departmentId,
                    normalizedName,
                    ResourceStatus.ENABLED
            );
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(
                    ErrorCode.DEPARTMENT_NAME_DUPLICATE
            );
        }
    }

    public ClinicRoom createClinicRoom(
            long departmentId,
            String name
    ) {
        Department department = getEnabledDepartment(
                departmentId
        );
        String normalizedName = normalizeName(name);

        try {
            long clinicRoomId =
                    organizationRepository.insertClinicRoom(
                            department.id(),
                            normalizedName
                    );

            return new ClinicRoom(
                    clinicRoomId,
                    department.id(),
                    normalizedName,
                    ResourceStatus.ENABLED
            );
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(
                    ErrorCode.CLINIC_ROOM_NAME_DUPLICATE
            );
        }
    }

    public Doctor bindDoctor(CreateDoctorCommand command) {
        if (command == null
                || command.departmentId() <= 0) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED
            );
        }

        String username = normalizeName(command.username());
        String doctorName = normalizeName(command.doctorName());

        UserAccount account = userRepository
                .findByUsername(username)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.DOCTOR_ACCOUNT_NOT_FOUND
                ));

        if (account.role() != UserRole.DOCTOR) {
            throw new BusinessException(
                    ErrorCode.DOCTOR_ACCOUNT_ROLE_INVALID
            );
        }

        if (account.status() != UserStatus.ENABLED) {
            throw new BusinessException(
                    ErrorCode.DOCTOR_ACCOUNT_UNAVAILABLE
            );
        }

        Department department = getEnabledDepartment(
                command.departmentId()
        );

        try {
            long doctorId = organizationRepository.insertDoctor(
                    account.id(),
                    department.id(),
                    doctorName
            );

            return new Doctor(
                    doctorId,
                    account.id(),
                    department.id(),
                    doctorName,
                    ResourceStatus.ENABLED
            );
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(
                    ErrorCode.DOCTOR_ALREADY_BOUND
            );
        }
    }

    public List<Department> findEnabledDepartments() {
        return organizationRepository.findEnabledDepartments();
    }

    public List<Doctor> findEnabledDoctorsByDepartmentId(
            long departmentId
    ) {
        getEnabledDepartment(departmentId);

        return organizationRepository
                .findEnabledDoctorsByDepartmentId(
                        departmentId
                );
    }

    public List<ClinicRoom> findEnabledClinicRoomsByDepartmentId(
            long departmentId
    ) {
        getEnabledDepartment(departmentId);

        return organizationRepository
                .findEnabledClinicRoomsByDepartmentId(
                        departmentId
                );
    }

    private Department getEnabledDepartment(long departmentId) {
        Department department = organizationRepository
                .findDepartmentById(departmentId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.DEPARTMENT_NOT_FOUND
                ));

        if (department.status() != ResourceStatus.ENABLED) {
            throw new BusinessException(
                    ErrorCode.DEPARTMENT_DISABLED
            );
        }

        return department;
    }

    private String normalizeName(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED
            );
        }

        return value.trim();
    }
}
