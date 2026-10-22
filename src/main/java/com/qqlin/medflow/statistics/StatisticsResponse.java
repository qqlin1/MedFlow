package com.qqlin.medflow.statistics;

import java.time.LocalDate;
import java.util.List;

public record StatisticsResponse(
        LocalDate from,
        LocalDate to,
        List<AppointmentPoint> appointmentTrend,
        List<DepartmentVisits> departmentVisits,
        List<DoctorWorkload> doctorWorkload
) {
    public record AppointmentPoint(LocalDate date, long count) {}
    public record DepartmentVisits(long departmentId, String departmentName, long count) {}
    public record DoctorWorkload(long doctorId, String doctorName, String departmentName, long count) {}
}
