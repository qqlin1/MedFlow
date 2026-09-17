package com.qqlin.medflow.scheduling.mapper;

import com.qqlin.medflow.scheduling.domain.ScheduleForPublish;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalTime;

@Mapper
public interface ScheduleMapper {

    Integer findDoctorOverlap(
            @Param("doctorId") long doctorId,
            @Param("workDate") LocalDate workDate,
            @Param("endTime") LocalTime endTime,
            @Param("startTime") LocalTime startTime
    );

    Integer findClinicRoomOverlap(
            @Param("clinicRoomId") long clinicRoomId,
            @Param("workDate") LocalDate workDate,
            @Param("endTime") LocalTime endTime,
            @Param("startTime") LocalTime startTime
    );

    int insertDraft(ScheduleDraftInsertParam parameter);

    ScheduleForPublish lockScheduleForPublish(
            @Param("scheduleId") long scheduleId
    );

    int markPublishedIfDraft(
            @Param("scheduleId") long scheduleId,
            @Param("publishedStatus") String publishedStatus,
            @Param("draftStatus") String draftStatus
    );
}
