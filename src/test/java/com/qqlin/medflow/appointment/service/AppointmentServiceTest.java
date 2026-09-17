package com.qqlin.medflow.appointment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqlin.medflow.appointment.domain.Appointment;
import com.qqlin.medflow.appointment.domain.AppointmentStatus;
import com.qqlin.medflow.appointment.repository.AppointmentRepository;
import com.qqlin.medflow.appointment.repository.IdempotencyRepository;
import com.qqlin.medflow.patient.service.PatientService;
import com.qqlin.medflow.scheduling.service.SlotBookingService;
import com.qqlin.medflow.shared.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpStatus;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Service-level rule tests only. They verify branching and collaborator calls;
 * database transaction rollback and real concurrent row locking require a
 * separate integration test against MySQL.
 */
@ExtendWith(MockitoExtension.class)
class AppointmentServiceTest {

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

    @AfterEach
    void confirmMustNotTouchCreationOnlyDependencies() {
        verifyNoInteractions(
                slotBookingService,
                idempotencyRepository
        );
    }

    @Test
    void shouldRejectNonPositiveAppointmentIdBeforeReadingRepository() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> appointmentService.confirm(CURRENT_USER_ID, 0L)
        );

        assertEquals(
                HttpStatus.BAD_REQUEST,
                exception.getErrorCode().getHttpStatus()
        );
        verifyNoInteractions(appointmentRepository);
    }

    @Test
    void shouldHideMissingOrUnownedAppointmentAsNotFound() {
        when(appointmentRepository.lockOwnedByIdForUpdate(
                APPOINTMENT_ID,
                CURRENT_USER_ID
        )).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> appointmentService.confirm(
                        CURRENT_USER_ID,
                        APPOINTMENT_ID
                )
        );

        assertEquals(
                HttpStatus.NOT_FOUND,
                exception.getErrorCode().getHttpStatus()
        );
        verify(appointmentRepository).lockOwnedByIdForUpdate(
                APPOINTMENT_ID,
                CURRENT_USER_ID
        );
        verify(appointmentRepository, never())
                .markBookedIfPendingBeforeDeadline(
                        APPOINTMENT_ID,
                        CURRENT_USER_ID
                );
        verify(appointmentRepository, never())
                .insertConfirmedStatusLog(
                        APPOINTMENT_ID,
                        CURRENT_USER_ID
                );
    }

    @Test
    void shouldReturnBookedAppointmentForRepeatedConfirmationWithoutWriting() {
        Appointment booked = appointment(AppointmentStatus.BOOKED);
        when(appointmentRepository.lockOwnedByIdForUpdate(
                APPOINTMENT_ID,
                CURRENT_USER_ID
        )).thenReturn(Optional.of(booked));

        Appointment result = appointmentService.confirm(
                CURRENT_USER_ID,
                APPOINTMENT_ID
        );

        assertEquals(booked, result);
        verify(appointmentRepository).lockOwnedByIdForUpdate(
                APPOINTMENT_ID,
                CURRENT_USER_ID
        );
        verify(appointmentRepository, never())
                .markBookedIfPendingBeforeDeadline(
                        APPOINTMENT_ID,
                        CURRENT_USER_ID
                );
        verify(appointmentRepository, never())
                .insertConfirmedStatusLog(
                        APPOINTMENT_ID,
                        CURRENT_USER_ID
                );
    }

    @Test
    void shouldRejectNonPendingAppointmentWithoutStateWrite() {
        when(appointmentRepository.lockOwnedByIdForUpdate(
                APPOINTMENT_ID,
                CURRENT_USER_ID
        )).thenReturn(Optional.of(appointment(
                AppointmentStatus.CANCELLED
        )));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> appointmentService.confirm(
                        CURRENT_USER_ID,
                        APPOINTMENT_ID
                )
        );

        assertEquals(
                HttpStatus.CONFLICT,
                exception.getErrorCode().getHttpStatus()
        );
        verify(appointmentRepository, never())
                .markBookedIfPendingBeforeDeadline(
                        APPOINTMENT_ID,
                        CURRENT_USER_ID
                );
        verify(appointmentRepository, never())
                .insertConfirmedStatusLog(
                        APPOINTMENT_ID,
                        CURRENT_USER_ID
                );
    }

    @Test
    void shouldRejectWhenConditionalConfirmationDoesNotUpdateAnyRow() {
        when(appointmentRepository.lockOwnedByIdForUpdate(
                APPOINTMENT_ID,
                CURRENT_USER_ID
        )).thenReturn(Optional.of(appointment(
                AppointmentStatus.PENDING_CONFIRMATION
        )));
        when(appointmentRepository.markBookedIfPendingBeforeDeadline(
                APPOINTMENT_ID,
                CURRENT_USER_ID
        )).thenReturn(false);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> appointmentService.confirm(
                        CURRENT_USER_ID,
                        APPOINTMENT_ID
                )
        );

        assertEquals(
                HttpStatus.CONFLICT,
                exception.getErrorCode().getHttpStatus()
        );
        verify(appointmentRepository).markBookedIfPendingBeforeDeadline(
                APPOINTMENT_ID,
                CURRENT_USER_ID
        );
        verify(appointmentRepository, never())
                .insertConfirmedStatusLog(
                        APPOINTMENT_ID,
                        CURRENT_USER_ID
                );
    }

    @Test
    void shouldMarkPendingAppointmentBookedAndAppendOneConfirmationLog() {
        Appointment pending = appointment(
                AppointmentStatus.PENDING_CONFIRMATION
        );
        when(appointmentRepository.lockOwnedByIdForUpdate(
                APPOINTMENT_ID,
                CURRENT_USER_ID
        )).thenReturn(Optional.of(pending));
        when(appointmentRepository.markBookedIfPendingBeforeDeadline(
                APPOINTMENT_ID,
                CURRENT_USER_ID
        )).thenReturn(true);

        Appointment result = appointmentService.confirm(
                CURRENT_USER_ID,
                APPOINTMENT_ID
        );

        assertEquals(APPOINTMENT_ID, result.id());
        assertEquals(PATIENT_ID, result.patientId());
        assertEquals(SLOT_ID, result.slotId());
        assertEquals(AppointmentStatus.BOOKED, result.status());
        assertEquals(pending.confirmDeadline(), result.confirmDeadline());
        assertEquals(pending.cancelDeadline(), result.cancelDeadline());
        verify(appointmentRepository).markBookedIfPendingBeforeDeadline(
                APPOINTMENT_ID,
                CURRENT_USER_ID
        );
        verify(appointmentRepository).insertConfirmedStatusLog(
                APPOINTMENT_ID,
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
