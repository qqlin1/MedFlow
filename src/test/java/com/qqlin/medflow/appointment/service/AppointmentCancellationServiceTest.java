package com.qqlin.medflow.appointment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqlin.medflow.appointment.domain.Appointment;
import com.qqlin.medflow.appointment.domain.AppointmentStatus;
import com.qqlin.medflow.appointment.repository.AppointmentRepository;
import com.qqlin.medflow.appointment.repository.IdempotencyRepository;
import com.qqlin.medflow.patient.service.PatientService;
import com.qqlin.medflow.scheduling.service.SlotBookingService;
import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyLong;

/**
 * Service-level state-machine tests for cancellation and expiration.
 *
 * <p>These tests prove branching and collaborator ordering only. Transaction
 * rollback, row locks and concurrent capacity restoration require the real
 * MySQL lifecycle integration test.</p>
 */
@ExtendWith(MockitoExtension.class)
class AppointmentCancellationServiceTest {

    private static final long CURRENT_USER_ID = 11L;
    private static final long APPOINTMENT_ID = 22L;
    private static final long PATIENT_ID = 33L;
    private static final long SLOT_ID = 44L;

    @Mock
    private PatientService patientService;

    @Mock
    private SlotBookingService slotBookingService;

    @Mock
    private AppointmentRepository appointmentRepository;

    @Mock
    private IdempotencyRepository idempotencyRepository;

    private AppointmentService appointmentService;

    @BeforeEach
    void setUp() {
        appointmentService = new AppointmentService(
                patientService,
                slotBookingService,
                appointmentRepository,
                idempotencyRepository,
                new ObjectMapper()
        );
    }

    @Test
    void cancelShouldRejectInvalidIdentityBeforeReadingRepository() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> appointmentService.cancel(0L, APPOINTMENT_ID)
        );

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
        verifyNoInteractions(
                appointmentRepository,
                slotBookingService,
                patientService,
                idempotencyRepository
        );
    }

    @Test
    void checkedInAppointmentCannotBeCancelledOrReturnCapacity() {
        Appointment booked = appointment(AppointmentStatus.BOOKED);
        when(appointmentRepository.findOwnedById(APPOINTMENT_ID,CURRENT_USER_ID)).thenReturn(Optional.of(booked));
        when(appointmentRepository.lockOwnedByIdForUpdate(APPOINTMENT_ID,CURRENT_USER_ID)).thenReturn(Optional.of(booked));
        when(appointmentRepository.hasEncounterForCancellation(APPOINTMENT_ID)).thenReturn(true);
        BusinessException error = assertThrows(BusinessException.class,
                () -> appointmentService.cancel(CURRENT_USER_ID,APPOINTMENT_ID));
        assertEquals(ErrorCode.APPOINTMENT_ALREADY_CHECKED_IN,error.getErrorCode());
        verify(slotBookingService,never()).returnOneCapacity(anyLong());
        verify(appointmentRepository,never()).markCancelledIfActiveBeforeDeadline(anyLong(),anyLong());
    }

    @Test
    void cancelShouldHideMissingOrUnownedAppointmentAsNotFound() {
        when(appointmentRepository.findOwnedById(
                APPOINTMENT_ID,
                CURRENT_USER_ID
        )).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> appointmentService.cancel(
                        CURRENT_USER_ID,
                        APPOINTMENT_ID
                )
        );

        assertEquals(ErrorCode.APPOINTMENT_NOT_FOUND, exception.getErrorCode());
        verify(appointmentRepository).findOwnedById(
                APPOINTMENT_ID,
                CURRENT_USER_ID
        );
        verifyNoInteractions(slotBookingService);
        verifyNoCancellationWrites();
    }

    @Test
    void repeatedCancellationShouldReturnCancelledWithoutAnotherSideEffect() {
        Appointment cancelled = appointment(AppointmentStatus.CANCELLED);
        when(appointmentRepository.findOwnedById(
                APPOINTMENT_ID,
                CURRENT_USER_ID
        )).thenReturn(Optional.of(cancelled));

        Appointment result = appointmentService.cancel(
                CURRENT_USER_ID,
                APPOINTMENT_ID
        );

        assertSame(cancelled, result);
        verifyNoInteractions(slotBookingService);
        verifyNoCancellationWrites();
    }

    @Test
    void cancelShouldRejectExpiredAppointmentWithoutReturningCapacity() {
        when(appointmentRepository.findOwnedById(
                APPOINTMENT_ID,
                CURRENT_USER_ID
        )).thenReturn(Optional.of(appointment(
                AppointmentStatus.EXPIRED
        )));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> appointmentService.cancel(
                        CURRENT_USER_ID,
                        APPOINTMENT_ID
                )
        );

        assertEquals(
                ErrorCode.APPOINTMENT_NOT_CANCELLABLE,
                exception.getErrorCode()
        );
        verifyNoInteractions(slotBookingService);
        verifyNoCancellationWrites();
    }

    @Test
    void cancelPendingShouldChangeStateReturnCapacityAndAppendHistoryInOrder() {
        Appointment pending = appointment(
                AppointmentStatus.PENDING_CONFIRMATION
        );
        stubOwnedCancellation(pending, pending, true);

        Appointment result = appointmentService.cancel(
                CURRENT_USER_ID,
                APPOINTMENT_ID
        );

        assertEquals(AppointmentStatus.CANCELLED, result.status());
        assertEquals(pending.id(), result.id());
        assertEquals(pending.patientId(), result.patientId());
        assertEquals(pending.slotId(), result.slotId());
        assertEquals(pending.confirmDeadline(), result.confirmDeadline());
        assertEquals(pending.cancelDeadline(), result.cancelDeadline());

        InOrder order = inOrder(
                appointmentRepository,
                slotBookingService
        );
        verifySuccessfulCancellationOrder(
                order,
                AppointmentStatus.PENDING_CONFIRMATION
        );
    }

    @Test
    void cancelBookedShouldReturnCapacityAndRecordBookedAsPreviousStatus() {
        Appointment booked = appointment(AppointmentStatus.BOOKED);
        stubOwnedCancellation(booked, booked, true);

        Appointment result = appointmentService.cancel(
                CURRENT_USER_ID,
                APPOINTMENT_ID
        );

        assertEquals(AppointmentStatus.CANCELLED, result.status());
        InOrder order = inOrder(
                appointmentRepository,
                slotBookingService
        );
        verifySuccessfulCancellationOrder(
                order,
                AppointmentStatus.BOOKED
        );
    }

    @Test
    void cancellationThatLosesAfterSlotLockShouldNotReturnCapacityAgain() {
        Appointment pending = appointment(
                AppointmentStatus.PENDING_CONFIRMATION
        );
        Appointment cancelled = appointment(AppointmentStatus.CANCELLED);
        when(appointmentRepository.findOwnedById(
                APPOINTMENT_ID,
                CURRENT_USER_ID
        )).thenReturn(Optional.of(pending));
        when(appointmentRepository.lockOwnedByIdForUpdate(
                APPOINTMENT_ID,
                CURRENT_USER_ID
        )).thenReturn(Optional.of(cancelled));

        Appointment result = appointmentService.cancel(
                CURRENT_USER_ID,
                APPOINTMENT_ID
        );

        assertSame(cancelled, result);
        verify(slotBookingService).lockForCapacityReturn(SLOT_ID);
        verify(slotBookingService, never()).returnOneCapacity(SLOT_ID);
        verifyNoCancellationWrites();
    }

    @Test
    void cancellationAfterDeadlineShouldNotReturnCapacityOrWriteHistory() {
        Appointment pending = appointment(
                AppointmentStatus.PENDING_CONFIRMATION
        );
        stubOwnedCancellation(pending, pending, false);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> appointmentService.cancel(
                        CURRENT_USER_ID,
                        APPOINTMENT_ID
                )
        );

        assertEquals(
                ErrorCode.APPOINTMENT_CANCELLATION_EXPIRED,
                exception.getErrorCode()
        );
        verify(slotBookingService, never()).returnOneCapacity(SLOT_ID);
        verify(appointmentRepository, never())
                .insertCancelledStatusLog(
                        APPOINTMENT_ID,
                        AppointmentStatus.PENDING_CONFIRMATION,
                        CURRENT_USER_ID
                );
    }

    @Test
    void cancellationHistoryFailureShouldEscapeForTransactionRollback() {
        Appointment pending = appointment(
                AppointmentStatus.PENDING_CONFIRMATION
        );
        stubOwnedCancellation(pending, pending, true);
        IllegalStateException historyFailure = new IllegalStateException(
                "test cancellation history failure"
        );
        doThrow(historyFailure).when(appointmentRepository)
                .insertCancelledStatusLog(
                        APPOINTMENT_ID,
                        AppointmentStatus.PENDING_CONFIRMATION,
                        CURRENT_USER_ID
                );

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> appointmentService.cancel(
                        CURRENT_USER_ID,
                        APPOINTMENT_ID
                )
        );

        assertSame(historyFailure, thrown);
        verify(slotBookingService).returnOneCapacity(SLOT_ID);
    }

    @Test
    void expireShouldRejectInvalidAppointmentIdBeforeReadingRepository() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> appointmentService.expirePending(0L)
        );

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
        verifyNoInteractions(
                appointmentRepository,
                slotBookingService,
                patientService,
                idempotencyRepository
        );
    }

    @Test
    void expireShouldTreatMissingAppointmentAsNoOp() {
        when(appointmentRepository.findById(APPOINTMENT_ID))
                .thenReturn(Optional.empty());

        assertFalse(appointmentService.expirePending(APPOINTMENT_ID));

        verify(appointmentRepository, never())
                .markExpiredIfPendingAtOrBeforeDeadline(APPOINTMENT_ID);
        verifyNoInteractions(slotBookingService);
        verify(appointmentRepository, never())
                .insertExpiredStatusLog(APPOINTMENT_ID);
    }

    @ParameterizedTest
    @EnumSource(
            value = AppointmentStatus.class,
            names = {"BOOKED", "CANCELLED", "EXPIRED"}
    )
    void expireShouldTreatNonPendingAppointmentAsNoOp(
            AppointmentStatus status
    ) {
        when(appointmentRepository.findById(APPOINTMENT_ID))
                .thenReturn(Optional.of(appointment(status)));

        assertFalse(appointmentService.expirePending(APPOINTMENT_ID));

        verify(appointmentRepository, never())
                .markExpiredIfPendingAtOrBeforeDeadline(APPOINTMENT_ID);
        verifyNoInteractions(slotBookingService);
        verify(appointmentRepository, never())
                .insertExpiredStatusLog(APPOINTMENT_ID);
    }

    @Test
    void expireBeforeDeadlineShouldRemainNoOpWithoutReturningCapacity() {
        Appointment pending = appointment(
                AppointmentStatus.PENDING_CONFIRMATION
        );
        stubExpiration(pending, pending, false);

        assertFalse(appointmentService.expirePending(APPOINTMENT_ID));

        verify(slotBookingService).lockForCapacityReturn(SLOT_ID);
        verify(slotBookingService, never()).returnOneCapacity(SLOT_ID);
        verify(appointmentRepository, never())
                .insertExpiredStatusLog(APPOINTMENT_ID);
    }

    @Test
    void expirationThatLosesAfterSlotLockShouldNotReturnCapacityAgain() {
        Appointment pending = appointment(
                AppointmentStatus.PENDING_CONFIRMATION
        );
        when(appointmentRepository.findById(APPOINTMENT_ID))
                .thenReturn(Optional.of(pending));
        when(appointmentRepository.lockByIdForUpdate(APPOINTMENT_ID))
                .thenReturn(Optional.of(appointment(
                        AppointmentStatus.CANCELLED
                )));

        assertFalse(appointmentService.expirePending(APPOINTMENT_ID));

        verify(slotBookingService).lockForCapacityReturn(SLOT_ID);
        verify(slotBookingService, never()).returnOneCapacity(SLOT_ID);
        verify(appointmentRepository, never())
                .markExpiredIfPendingAtOrBeforeDeadline(APPOINTMENT_ID);
        verify(appointmentRepository, never())
                .insertExpiredStatusLog(APPOINTMENT_ID);
    }

    @Test
    void expiredPendingAppointmentShouldReturnCapacityAndAppendHistoryInOrder() {
        Appointment pending = appointment(
                AppointmentStatus.PENDING_CONFIRMATION
        );
        stubExpiration(pending, pending, true);

        assertTrue(appointmentService.expirePending(APPOINTMENT_ID));

        InOrder order = inOrder(
                appointmentRepository,
                slotBookingService
        );
        order.verify(appointmentRepository).findById(APPOINTMENT_ID);
        order.verify(slotBookingService).lockForCapacityReturn(SLOT_ID);
        order.verify(appointmentRepository).lockByIdForUpdate(
                APPOINTMENT_ID
        );
        order.verify(appointmentRepository)
                .markExpiredIfPendingAtOrBeforeDeadline(
                        APPOINTMENT_ID
                );
        order.verify(slotBookingService).returnOneCapacity(SLOT_ID);
        order.verify(appointmentRepository).insertExpiredStatusLog(
                APPOINTMENT_ID
        );
    }

    @Test
    void expirationHistoryFailureShouldEscapeForTransactionRollback() {
        Appointment pending = appointment(
                AppointmentStatus.PENDING_CONFIRMATION
        );
        stubExpiration(pending, pending, true);
        IllegalStateException historyFailure = new IllegalStateException(
                "test expiration history failure"
        );
        doThrow(historyFailure).when(appointmentRepository)
                .insertExpiredStatusLog(APPOINTMENT_ID);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> appointmentService.expirePending(APPOINTMENT_ID)
        );

        assertSame(historyFailure, thrown);
        verify(slotBookingService).returnOneCapacity(SLOT_ID);
    }

    private void stubOwnedCancellation(
            Appointment observed,
            Appointment current,
            boolean stateChanged
    ) {
        when(appointmentRepository.findOwnedById(
                APPOINTMENT_ID,
                CURRENT_USER_ID
        )).thenReturn(Optional.of(observed));
        when(appointmentRepository.lockOwnedByIdForUpdate(
                APPOINTMENT_ID,
                CURRENT_USER_ID
        )).thenReturn(Optional.of(current));
        when(appointmentRepository.markCancelledIfActiveBeforeDeadline(
                APPOINTMENT_ID,
                CURRENT_USER_ID
        )).thenReturn(stateChanged);
    }

    private void stubExpiration(
            Appointment observed,
            Appointment current,
            boolean stateChanged
    ) {
        when(appointmentRepository.findById(APPOINTMENT_ID))
                .thenReturn(Optional.of(observed));
        when(appointmentRepository.lockByIdForUpdate(APPOINTMENT_ID))
                .thenReturn(Optional.of(current));
        when(appointmentRepository
                .markExpiredIfPendingAtOrBeforeDeadline(APPOINTMENT_ID))
                .thenReturn(stateChanged);
    }

    private void verifySuccessfulCancellationOrder(
            InOrder order,
            AppointmentStatus previousStatus
    ) {
        order.verify(appointmentRepository).findOwnedById(
                APPOINTMENT_ID,
                CURRENT_USER_ID
        );
        order.verify(slotBookingService).lockForCapacityReturn(SLOT_ID);
        order.verify(appointmentRepository).lockOwnedByIdForUpdate(
                APPOINTMENT_ID,
                CURRENT_USER_ID
        );
        order.verify(appointmentRepository)
                .markCancelledIfActiveBeforeDeadline(
                        APPOINTMENT_ID,
                        CURRENT_USER_ID
                );
        order.verify(slotBookingService).returnOneCapacity(SLOT_ID);
        order.verify(appointmentRepository).insertCancelledStatusLog(
                APPOINTMENT_ID,
                previousStatus,
                CURRENT_USER_ID
        );
    }

    private void verifyNoCancellationWrites() {
        verify(appointmentRepository, never())
                .markCancelledIfActiveBeforeDeadline(
                        APPOINTMENT_ID,
                        CURRENT_USER_ID
                );
        verify(appointmentRepository, never())
                .insertCancelledStatusLog(
                        APPOINTMENT_ID,
                        AppointmentStatus.PENDING_CONFIRMATION,
                        CURRENT_USER_ID
                );
        verify(appointmentRepository, never())
                .insertCancelledStatusLog(
                        APPOINTMENT_ID,
                        AppointmentStatus.BOOKED,
                        CURRENT_USER_ID
                );
    }

    private Appointment appointment(AppointmentStatus status) {
        LocalDateTime now = LocalDateTime.now();

        return new Appointment(
                APPOINTMENT_ID,
                PATIENT_ID,
                SLOT_ID,
                status,
                now.plusMinutes(10),
                now.plusHours(1)
        );
    }
}
