package com.qqlin.medflow.appointment.service;

import com.qqlin.medflow.appointment.domain.AppointmentExpirationBatch;
import com.qqlin.medflow.appointment.domain.AppointmentExpirationCandidate;
import com.qqlin.medflow.appointment.repository.AppointmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AppointmentExpirationWorker {

    private static final Logger log = LoggerFactory.getLogger(AppointmentExpirationWorker.class);

    private final AppointmentRepository appointmentRepository;
    private final AppointmentService appointmentService;
    private final int batchSize;
    private AppointmentExpirationCandidate lastScanned;

    public AppointmentExpirationWorker(
            AppointmentRepository appointmentRepository,
            AppointmentService appointmentService,
            @Value("${medflow.appointment.expiration.batch-size:100}") int batchSize
    ) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Expiration batch size must be between 1 and 1000");
        }
        this.appointmentRepository = appointmentRepository;
        this.appointmentService = appointmentService;
        this.batchSize = batchSize;
    }

    /**
     * Keep the scan outside a business transaction. Each call through the
     * injected AppointmentService proxy opens and finishes its own transaction.
     * A cursor advances past failures; reaching the end retries them from the
     * beginning. The database, rather than this in-memory cursor, owns the work.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public synchronized AppointmentExpirationBatch runBatch() {
        List<AppointmentExpirationCandidate> candidates =
                appointmentRepository.findDuePendingAfter(lastScanned, batchSize);
        if (candidates.isEmpty() && lastScanned != null) {
            lastScanned = null;
            candidates = appointmentRepository.findDuePendingAfter(null, batchSize);
        }

        int expired = 0;
        int skipped = 0;
        int failed = 0;
        for (AppointmentExpirationCandidate candidate : candidates) {
            try {
                if (appointmentService.expirePending(candidate.id())) {
                    expired++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException exception) {
                failed++;
                log.error("Expiration transaction failed for appointment {}", candidate.id(), exception);
            }
        }

        lastScanned = candidates.size() == batchSize ? candidates.getLast() : null;
        return new AppointmentExpirationBatch(candidates.size(), expired, skipped, failed);
    }
}
