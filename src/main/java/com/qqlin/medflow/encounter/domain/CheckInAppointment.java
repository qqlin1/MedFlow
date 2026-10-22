package com.qqlin.medflow.encounter.domain;

import java.time.LocalDate;
import java.time.LocalTime;

/** Locked appointment with the immutable slot identity needed for attendance. */
public record CheckInAppointment(long id, long slotId, String status, long doctorId,
        LocalDate workDate, LocalTime startTime, LocalTime endTime) {}
