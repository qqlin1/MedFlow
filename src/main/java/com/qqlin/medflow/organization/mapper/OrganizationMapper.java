package com.qqlin.medflow.organization.mapper;

import com.qqlin.medflow.organization.domain.ClinicRoom;
import com.qqlin.medflow.organization.domain.Department;
import com.qqlin.medflow.organization.domain.Doctor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OrganizationMapper {

    Department findDepartmentById(
            @Param("departmentId") long departmentId
    );

    List<Department> findEnabledDepartments();

    List<Doctor> findEnabledDoctorsByDepartmentId(
            @Param("departmentId") long departmentId
    );

    List<ClinicRoom> findEnabledClinicRoomsByDepartmentId(
            @Param("departmentId") long departmentId
    );

    Doctor lockDoctorById(@Param("doctorId") long doctorId);

    ClinicRoom lockClinicRoomById(
            @Param("clinicRoomId") long clinicRoomId
    );

    int insertDepartment(DepartmentInsertParam parameter);

    int insertClinicRoom(ClinicRoomInsertParam parameter);

    int insertDoctor(DoctorInsertParam parameter);
}
