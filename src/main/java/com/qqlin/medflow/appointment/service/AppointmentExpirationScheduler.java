package com.qqlin.medflow.appointment.service;

import com.qqlin.medflow.appointment.domain.AppointmentExpirationBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "medflow.appointment.expiration", name = "enabled", matchIfMissing = true)
public class AppointmentExpirationScheduler {

    private static final Logger log = LoggerFactory.getLogger(AppointmentExpirationScheduler.class);
    private final AppointmentExpirationWorker worker;

    public AppointmentExpirationScheduler(AppointmentExpirationWorker worker) {
        this.worker = worker;
    }

    @Scheduled(
            fixedDelayString = "${medflow.appointment.expiration.poll-interval-ms:5000}",
            initialDelayString = "${medflow.appointment.expiration.initial-delay-ms:5000}"
    )
    public void expireDueAppointments() {
        try {
            AppointmentExpirationBatch batch = worker.runBatch();
            if (batch.scanned() > 0) {
                log.info("Expiration batch: scanned={}, expired={}, skipped={}, failed={}",
                        batch.scanned(), batch.expired(), batch.skipped(), batch.failed());
            }
        } catch (RuntimeException exception) {
            // A failed scan remains pending in the database for the next poll.
            log.error("Expiration scan failed; it will be retried on the next poll", exception);
        }
    }
}
