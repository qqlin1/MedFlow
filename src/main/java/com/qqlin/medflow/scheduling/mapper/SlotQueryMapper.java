package com.qqlin.medflow.scheduling.mapper;

import com.qqlin.medflow.scheduling.domain.AvailableSlot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SlotQueryMapper {

    LocalDateTime currentDatabaseTime();

    long countAvailable(
            @Param("workDate") LocalDate workDate,
            @Param("departmentId") Long departmentId,
            @Param("doctorId") Long doctorId,
            @Param("now") LocalDateTime now
    );

    List<AvailableSlot> findAvailablePage(
            @Param("workDate") LocalDate workDate,
            @Param("departmentId") Long departmentId,
            @Param("doctorId") Long doctorId,
            @Param("now") LocalDateTime now,
            @Param("offset") long offset,
            @Param("limit") int limit
    );
}
