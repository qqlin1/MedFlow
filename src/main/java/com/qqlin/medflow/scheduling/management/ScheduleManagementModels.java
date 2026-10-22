package com.qqlin.medflow.scheduling.management;

import com.qqlin.medflow.scheduling.domain.ScheduleStatus;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.List;

/** Query contracts contain display information, never authentication identity. */
public final class ScheduleManagementModels {
    private ScheduleManagementModels() {}

    public record ScheduleItem(long id, long doctorId, String doctorName,
            long departmentId, String departmentName, long clinicRoomId,
            String clinicRoomName, Long shiftTemplateId, String shiftName,
            LocalDate workDate, LocalTime startTime, LocalTime endTime,
            ScheduleStatus status, long totalCapacity, long remainingCapacity,
            long activeAppointmentCount, String cancelReason) {}
    public record SlotItem(long id, LocalTime startTime, LocalTime endTime,
            int totalCapacity, int remainingCapacity, String status,
            LocalDateTime bookingDeadline) {}
    public record ScheduleDetail(ScheduleItem schedule, List<SlotItem> slots) {}
    public record Page<T>(List<T> items, long total, int page, int size) {}
    public record BatchRequest(@Positive long doctorId, @Positive long clinicRoomId,
            @Positive long shiftTemplateId,
            @NotNull @Size(min = 1, max = 7) List<@NotNull LocalDate> workDates) {}
    public record BatchItem(long scheduleId, ScheduleStatus status) {}
    public record BatchResult(List<BatchItem> items, int count) {}
    public record CancelRequest(@NotBlank @Size(max = 200) String reason) {}
    public record LockedSchedule(long id, LocalDate workDate,
            LocalTime startTime, ScheduleStatus status) {}
    public record Query(LocalDate from, LocalDate to, Long doctorId,
            Long departmentId, ScheduleStatus status, int limit, long offset) {}
    public record DoctorAppointment(long appointmentId, long patientId,
            String patientName, long slotId, String doctorName,
            String departmentName, String clinicRoomName, LocalDate workDate,
            LocalTime startTime, LocalTime endTime, String status,
            LocalDateTime confirmDeadline, LocalDateTime cancelDeadline,
            Long encounterId, String encounterStatus) {}
}
