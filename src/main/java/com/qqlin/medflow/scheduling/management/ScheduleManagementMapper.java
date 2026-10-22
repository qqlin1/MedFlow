package com.qqlin.medflow.scheduling.management;

import com.qqlin.medflow.scheduling.management.ScheduleManagementModels.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.*;
import java.util.List;

@Mapper
public interface ScheduleManagementMapper {
    List<ScheduleItem> findPage(@Param("query") Query query);
    long count(@Param("query") Query query);
    ScheduleItem findItem(long scheduleId);
    List<SlotItem> findSlots(long scheduleId);
    LockedSchedule findState(long scheduleId);
    LockedSchedule lockSchedule(long scheduleId);
    List<Long> findSlotIds(long scheduleId);
    Long lockSlot(long slotId);
    List<Long> lockActiveAppointments(@Param("slotIds") List<Long> slotIds);
    LocalDateTime databaseTime();
    int markCancelled(@Param("scheduleId") long scheduleId,
            @Param("reason") String reason, @Param("operatorId") long operatorId,
            @Param("now") LocalDateTime now);
    int cancelSlots(long scheduleId);
    Long findDoctorId(long userId);
    List<DoctorAppointment> findDoctorAppointments(@Param("doctorId") long doctorId,
            @Param("workDate") LocalDate workDate, @Param("limit") int limit,
            @Param("offset") long offset);
    long countDoctorAppointments(@Param("doctorId") long doctorId,
            @Param("workDate") LocalDate workDate);
}
