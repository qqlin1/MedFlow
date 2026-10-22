package com.qqlin.medflow.scheduling.availability;

import java.time.LocalDate;

/** Public read models. These values describe a snapshot and reserve no capacity. */
public final class AvailabilityModels {
    private AvailabilityModels() {}

    public enum DayStatus { AVAILABLE, FULL, NO_SCHEDULE, CLOSED }

    public record AvailabilityDay(LocalDate workDate, DayStatus status,
            long availableSlotCount, long remainingCapacity, long doctorCount) {}

    public record AvailableDoctor(long id, long departmentId, String name, String departmentName,
            String title, String specialty, String introduction,
            long availableSlotCount, long remainingCapacity) {}

    record DayAggregate(LocalDate workDate, long publishedScheduleCount, long openFutureSlotCount,
            long availableSlotCount, long remainingCapacity, long doctorCount) {}

    record DoctorScope(long departmentId, String doctorStatus, String departmentStatus) {}
}
