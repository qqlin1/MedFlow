package com.qqlin.medflow.scheduling.management;

import com.qqlin.medflow.scheduling.management.ScheduleManagementModels.*;
import org.springframework.stereotype.Repository;
import java.time.*;
import java.util.List;
import java.util.Optional;

@Repository
public class ScheduleManagementRepository {
    private final ScheduleManagementMapper mapper;
    public ScheduleManagementRepository(ScheduleManagementMapper mapper) { this.mapper = mapper; }
    public List<ScheduleItem> findPage(Query query) { return mapper.findPage(query); }
    public long count(Query query) { return mapper.count(query); }
    public Optional<ScheduleItem> findItem(long id) { return Optional.ofNullable(mapper.findItem(id)); }
    public List<SlotItem> findSlots(long id) { return mapper.findSlots(id); }
    public Optional<LockedSchedule> findState(long id) { return Optional.ofNullable(mapper.findState(id)); }
    public Optional<LockedSchedule> lockSchedule(long id) { return Optional.ofNullable(mapper.lockSchedule(id)); }
    public List<Long> findSlotIds(long id) { return mapper.findSlotIds(id); }
    public void lockSlots(List<Long> ids) { for (long id : ids) mapper.lockSlot(id); }
    public boolean hasActiveAppointments(List<Long> ids) {
        return !ids.isEmpty() && !mapper.lockActiveAppointments(ids).isEmpty();
    }
    public LocalDateTime databaseTime() { return mapper.databaseTime(); }
    public boolean cancel(long id, String reason, long operator, LocalDateTime now) {
        if (mapper.markCancelled(id, reason, operator, now) != 1) return false;
        mapper.cancelSlots(id);
        return true;
    }
    public Optional<Long> findDoctorId(long userId) { return Optional.ofNullable(mapper.findDoctorId(userId)); }
    public List<DoctorAppointment> findDoctorAppointments(long doctorId, LocalDate date, int size, long offset) {
        return mapper.findDoctorAppointments(doctorId, date, size, offset);
    }
    public long countDoctorAppointments(long doctorId, LocalDate date) {
        return mapper.countDoctorAppointments(doctorId, date);
    }
}
