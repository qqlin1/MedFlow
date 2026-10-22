package com.qqlin.medflow.appointment.service;

import com.qqlin.medflow.appointment.domain.AppointmentExpirationBatch;
import com.qqlin.medflow.appointment.domain.AppointmentExpirationCandidate;
import com.qqlin.medflow.appointment.repository.AppointmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Batch progress and failure isolation only. Real transaction rollback, row locks,
 * and competing workers are covered by the MySQL integration tests.
 */
@ExtendWith(MockitoExtension.class)
class AppointmentExpirationWorkerTest {

    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 10, 2, 10, 0);

    @Mock
    private AppointmentRepository repository;

    @Mock
    private AppointmentService service;

    @Test
    void emptyScanReturnsZeroCountsWithoutCallingExpiration() {
        AppointmentExpirationWorker worker = new AppointmentExpirationWorker(repository, service, 2);
        when(repository.findDuePendingAfter(null, 2)).thenReturn(List.of());

        assertEquals(new AppointmentExpirationBatch(0, 0, 0, 0), worker.runBatch());

        verify(repository).findDuePendingAfter(null, 2);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(service);
    }

    @Test
    void appointmentAlreadyChangedIsSkippedRatherThanCountedAsFailure() {
        AppointmentExpirationWorker worker = new AppointmentExpirationWorker(repository, service, 3);
        when(repository.findDuePendingAfter(null, 3)).thenReturn(List.of(candidate(1), candidate(2)));
        when(service.expirePending(1L)).thenReturn(true);
        when(service.expirePending(2L)).thenReturn(false);

        assertEquals(new AppointmentExpirationBatch(2, 1, 1, 0), worker.runBatch());

        verify(service).expirePending(1L);
        verify(service).expirePending(2L);
    }

    @Test
    void itemFailureDoesNotStopExpirationOfFollowingAppointments() {
        AppointmentExpirationWorker worker = new AppointmentExpirationWorker(repository, service, 4);
        when(repository.findDuePendingAfter(null, 4)).thenReturn(
                List.of(candidate(1), candidate(2), candidate(3), candidate(4)));
        when(service.expirePending(1L)).thenReturn(true);
        when(service.expirePending(2L)).thenThrow(new IllegalStateException("status history write failed"));
        when(service.expirePending(3L)).thenReturn(false);
        when(service.expirePending(4L)).thenReturn(true);

        assertEquals(new AppointmentExpirationBatch(4, 2, 1, 1), worker.runBatch());

        InOrder expirationOrder = inOrder(service);
        expirationOrder.verify(service).expirePending(1L);
        expirationOrder.verify(service).expirePending(2L);
        expirationOrder.verify(service).expirePending(3L);
        expirationOrder.verify(service).expirePending(4L);
        expirationOrder.verifyNoMoreInteractions();
    }

    @Test
    void fullBatchContinuesFromLastCandidateOnNextRun() {
        AppointmentExpirationWorker worker = new AppointmentExpirationWorker(repository, service, 2);
        AppointmentExpirationCandidate last = candidate(2);
        when(repository.findDuePendingAfter(null, 2)).thenReturn(List.of(candidate(1), last));
        when(repository.findDuePendingAfter(last, 2)).thenReturn(List.of(candidate(3)));
        when(service.expirePending(1L)).thenReturn(true);
        when(service.expirePending(2L)).thenReturn(true);
        when(service.expirePending(3L)).thenReturn(true);

        assertEquals(new AppointmentExpirationBatch(2, 2, 0, 0), worker.runBatch());
        assertEquals(new AppointmentExpirationBatch(1, 1, 0, 0), worker.runBatch());

        InOrder scans = inOrder(repository);
        scans.verify(repository).findDuePendingAfter(null, 2);
        scans.verify(repository).findDuePendingAfter(last, 2);
        scans.verifyNoMoreInteractions();
    }

    @Test
    void shortBatchResetsCursorSoLaterRunRechecksEarlierDeadlines() {
        AppointmentExpirationWorker worker = new AppointmentExpirationWorker(repository, service, 2);
        AppointmentExpirationCandidate lastFullBatch = candidate(2);
        when(repository.findDuePendingAfter(null, 2)).thenReturn(
                List.of(candidate(1), lastFullBatch), List.of(candidate(1)));
        when(repository.findDuePendingAfter(lastFullBatch, 2)).thenReturn(List.of(candidate(3)));
        when(service.expirePending(1L)).thenReturn(false, true);
        when(service.expirePending(2L)).thenReturn(true);
        when(service.expirePending(3L)).thenReturn(true);

        assertEquals(new AppointmentExpirationBatch(2, 1, 1, 0), worker.runBatch());
        assertEquals(new AppointmentExpirationBatch(1, 1, 0, 0), worker.runBatch());
        assertEquals(new AppointmentExpirationBatch(1, 1, 0, 0), worker.runBatch());

        InOrder scans = inOrder(repository);
        scans.verify(repository).findDuePendingAfter(null, 2);
        scans.verify(repository).findDuePendingAfter(lastFullBatch, 2);
        scans.verify(repository).findDuePendingAfter(null, 2);
        scans.verifyNoMoreInteractions();
    }

    @Test
    void reachingEndRescansFromStartOnceAndLeavesEmptyRoundReset() {
        AppointmentExpirationWorker worker = new AppointmentExpirationWorker(repository, service, 1);
        AppointmentExpirationCandidate last = candidate(1);
        when(repository.findDuePendingAfter(null, 1)).thenReturn(List.of(last), List.of(), List.of());
        when(repository.findDuePendingAfter(last, 1)).thenReturn(List.of());
        when(service.expirePending(1L)).thenReturn(true);

        assertEquals(new AppointmentExpirationBatch(1, 1, 0, 0), worker.runBatch());
        assertEquals(new AppointmentExpirationBatch(0, 0, 0, 0), worker.runBatch());
        assertEquals(new AppointmentExpirationBatch(0, 0, 0, 0), worker.runBatch());

        InOrder scans = inOrder(repository);
        scans.verify(repository).findDuePendingAfter(null, 1);
        scans.verify(repository).findDuePendingAfter(last, 1);
        scans.verify(repository, times(2)).findDuePendingAfter(null, 1);
        scans.verifyNoMoreInteractions();
        verify(service).expirePending(1L);
        verifyNoMoreInteractions(service);
    }

    @Test
    void fullyFailedBatchDoesNotStarveFollowingBatchAndIsRetriedAfterWraparound() {
        AppointmentExpirationWorker worker = new AppointmentExpirationWorker(repository, service, 2);
        AppointmentExpirationCandidate failedHead = candidate(1);
        AppointmentExpirationCandidate lastFirstBatch = candidate(2);
        AppointmentExpirationCandidate lastSecondBatch = candidate(4);
        when(repository.findDuePendingAfter(null, 2)).thenReturn(
                List.of(failedHead, lastFirstBatch), List.of(failedHead, lastFirstBatch));
        when(repository.findDuePendingAfter(lastFirstBatch, 2)).thenReturn(
                List.of(candidate(3), lastSecondBatch));
        when(repository.findDuePendingAfter(lastSecondBatch, 2)).thenReturn(List.of());
        when(service.expirePending(1L)).thenThrow(new IllegalStateException("transient failure"))
                .thenReturn(true);
        when(service.expirePending(2L)).thenThrow(new IllegalStateException("transient failure"))
                .thenReturn(true);
        when(service.expirePending(3L)).thenReturn(true);
        when(service.expirePending(4L)).thenReturn(true);

        assertEquals(new AppointmentExpirationBatch(2, 0, 0, 2), worker.runBatch());
        assertEquals(new AppointmentExpirationBatch(2, 2, 0, 0), worker.runBatch());
        assertEquals(new AppointmentExpirationBatch(2, 2, 0, 0), worker.runBatch());

        InOrder scans = inOrder(repository);
        scans.verify(repository).findDuePendingAfter(null, 2);
        scans.verify(repository).findDuePendingAfter(lastFirstBatch, 2);
        scans.verify(repository).findDuePendingAfter(lastSecondBatch, 2);
        scans.verify(repository).findDuePendingAfter(null, 2);
        scans.verifyNoMoreInteractions();
        verify(service, times(2)).expirePending(1L);
        verify(service, times(2)).expirePending(2L);
        verify(service).expirePending(3L);
        verify(service).expirePending(4L);
    }

    @Test
    void batchSizeMustStayWithinBoundedRangeIncludingBothEndpoints() {
        assertAll(
                () -> assertDoesNotThrow(() -> new AppointmentExpirationWorker(repository, service, 1)),
                () -> assertDoesNotThrow(() -> new AppointmentExpirationWorker(repository, service, 1000)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AppointmentExpirationWorker(repository, service, 0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AppointmentExpirationWorker(repository, service, -1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AppointmentExpirationWorker(repository, service, 1001))
        );
        verifyNoInteractions(repository, service);
    }

    private static AppointmentExpirationCandidate candidate(long id) {
        return new AppointmentExpirationCandidate(id, DEADLINE.plusSeconds(id));
    }
}
