package com.qqlin.medflow.organization.repository;

import com.qqlin.medflow.organization.domain.ClinicRoom;
import com.qqlin.medflow.organization.domain.Department;
import com.qqlin.medflow.organization.domain.Doctor;
import com.qqlin.medflow.organization.domain.ResourceStatus;
import com.qqlin.medflow.organization.mapper.ClinicRoomInsertParam;
import com.qqlin.medflow.organization.mapper.DepartmentInsertParam;
import com.qqlin.medflow.organization.mapper.DoctorInsertParam;
import com.qqlin.medflow.organization.mapper.OrganizationMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Keeps domain services independent of MyBatis parameter objects and SQL.
 */
@Repository
public class OrganizationRepository {

    private final OrganizationMapper organizationMapper;

    public OrganizationRepository(OrganizationMapper organizationMapper) {
        this.organizationMapper = organizationMapper;
    }

    public Optional<Department> findDepartmentById(long departmentId) {
        return Optional.ofNullable(
                organizationMapper.findDepartmentById(departmentId)
        );
    }

    public List<Department> findEnabledDepartments() {
        return organizationMapper.findEnabledDepartments();
    }

    public List<Doctor> findEnabledDoctorsByDepartmentId(
            long departmentId
    ) {
        return organizationMapper
                .findEnabledDoctorsByDepartmentId(departmentId);
    }

    public List<ClinicRoom> findEnabledClinicRoomsByDepartmentId(
            long departmentId
    ) {
        return organizationMapper
                .findEnabledClinicRoomsByDepartmentId(departmentId);
    }

    public Optional<Doctor> lockDoctorById(long doctorId) {
        return Optional.ofNullable(
                organizationMapper.lockDoctorById(doctorId)
        );
    }

    public Optional<ClinicRoom> lockClinicRoomById(
            long clinicRoomId
    ) {
        return Optional.ofNullable(
                organizationMapper.lockClinicRoomById(clinicRoomId)
        );
    }

    public long insertDepartment(String name) {
        DepartmentInsertParam parameter = new DepartmentInsertParam(
                name,
                ResourceStatus.ENABLED
        );

        int affectedRows = organizationMapper.insertDepartment(parameter);

        return requireGeneratedId(
                affectedRows,
                parameter.getId(),
                "创建组织资源后没有获得主键"
        );
    }

    public long insertClinicRoom(
            long departmentId,
            String name
    ) {
        ClinicRoomInsertParam parameter = new ClinicRoomInsertParam(
                departmentId,
                name,
                ResourceStatus.ENABLED
        );

        int affectedRows = organizationMapper.insertClinicRoom(parameter);

        return requireGeneratedId(
                affectedRows,
                parameter.getId(),
                "创建组织资源后没有获得主键"
        );
    }

    public long insertDoctor(
            long userId,
            long departmentId,
            String name
    ) {
        DoctorInsertParam parameter = new DoctorInsertParam(
                userId,
                departmentId,
                name,
                ResourceStatus.ENABLED
        );

        int affectedRows = organizationMapper.insertDoctor(parameter);

        return requireGeneratedId(
                affectedRows,
                parameter.getId(),
                "创建组织资源后没有获得主键"
        );
    }

    private long requireGeneratedId(
            int affectedRows,
            Long id,
            String message
    ) {
        if (affectedRows != 1 || id == null) {
            throw new IllegalStateException(message);
        }

        return id;
    }
}
