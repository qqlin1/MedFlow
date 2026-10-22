package com.qqlin.medflow.appointment.domain;

/** A scan candidate can expire, lose a state race, or fail its transaction. */
public record AppointmentExpirationBatch(int scanned, int expired, int skipped, int failed) {
}
