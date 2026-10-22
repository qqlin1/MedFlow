package com.qqlin.medflow.appointment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqlin.medflow.appointment.domain.AppointmentCursor;
import com.qqlin.medflow.appointment.domain.AppointmentCursorItem;
import com.qqlin.medflow.appointment.domain.AppointmentSlice;
import com.qqlin.medflow.appointment.domain.AppointmentStatus;
import com.qqlin.medflow.appointment.repository.AppointmentRepository;
import com.qqlin.medflow.appointment.repository.IdempotencyRepository;
import com.qqlin.medflow.patient.service.PatientService;
import com.qqlin.medflow.scheduling.service.SlotBookingService;
import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Cursor response and owner-scope rules. Real timestamp comparisons, SQL
 * ordering and index reads are verified separately against disposable MySQL.
 */
@ExtendWith(MockitoExtension.class)
class AppointmentCursorServiceTest {

    private static final long USER_ID = 11L;
    private static final long PATIENT_ID = 33L;
    private static final LocalDateTime CREATED_AT =
            LocalDateTime.of(2026, 10, 2, 10, 0, 0, 123_000_000);

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
    void cursorReadsMustNotCountOrTouchBookingDependencies() {
        verify(appointmentRepository, never()).countOwnedByUser(anyLong());
        verifyNoInteractions(
                patientService,
                slotBookingService,
                idempotencyRepository
        );
    }

    @Test
    void firstSliceShouldFetchOneExtraAndUseLastReturnedItemAsCursor() {
        List<AppointmentCursorItem> rows = IntStream.range(0, 51)
                .mapToObj(index -> item(200L - index, PATIENT_ID))
                .toList();
        when(appointmentRepository.findUpToTwoPatientIdsOwnedByUser(USER_ID))
                .thenReturn(List.of(PATIENT_ID));
        when(appointmentRepository.findSliceOwnedBySinglePatient(
                PATIENT_ID, USER_ID, null, 51
        )).thenReturn(rows);

        AppointmentSlice result = appointmentService.findOwnedSlice(
                USER_ID, 50, null
        );

        assertEquals(rows.subList(0, 50), result.items());
        assertEquals(50, result.size());
        assertTrue(result.hasNext());
        assertEquals(new AppointmentCursor(CREATED_AT, 151L), result.nextCursor());
        assertFalse(result.items().contains(rows.get(50)));
        verify(appointmentRepository).findUpToTwoPatientIdsOwnedByUser(USER_ID);
        verify(appointmentRepository).findSliceOwnedBySinglePatient(
                PATIENT_ID, USER_ID, null, 51
        );
        verifyNoMoreInteractions(appointmentRepository);
    }

    @Test
    void laterSinglePatientSliceShouldPassBothBoundaryValuesAndJwtOwner() {
        AppointmentCursor cursor = new AppointmentCursor(CREATED_AT, 151L);
        List<AppointmentCursorItem> rows = List.of(
                item(150L, PATIENT_ID),
                item(149L, PATIENT_ID),
                item(148L, PATIENT_ID)
        );
        when(appointmentRepository.findUpToTwoPatientIdsOwnedByUser(USER_ID))
                .thenReturn(List.of(PATIENT_ID));
        when(appointmentRepository.findSliceOwnedBySinglePatient(
                PATIENT_ID, USER_ID, cursor, 3
        )).thenReturn(rows);

        AppointmentSlice result = appointmentService.findOwnedSlice(USER_ID, 2, cursor);

        assertEquals(rows.subList(0, 2), result.items());
        assertTrue(result.hasNext());
        assertEquals(new AppointmentCursor(CREATED_AT, 149L), result.nextCursor());
        verify(appointmentRepository).findUpToTwoPatientIdsOwnedByUser(USER_ID);
        verify(appointmentRepository).findSliceOwnedBySinglePatient(
                PATIENT_ID, USER_ID, cursor, 3
        );
        verifyNoMoreInteractions(appointmentRepository);
    }

    @Test
    void multiPatientSliceShouldKeepAccountScopeAndRepositoryGlobalOrder() {
        AppointmentCursor cursor = new AppointmentCursor(CREATED_AT, 200L);
        List<AppointmentCursorItem> rows = List.of(
                item(199L, PATIENT_ID + 1),
                item(198L, PATIENT_ID),
                item(197L, PATIENT_ID + 1)
        );
        when(appointmentRepository.findUpToTwoPatientIdsOwnedByUser(USER_ID))
                .thenReturn(List.of(PATIENT_ID, PATIENT_ID + 1));
        when(appointmentRepository.findSliceOwnedByUser(USER_ID, cursor, 3))
                .thenReturn(rows);

        AppointmentSlice result = appointmentService.findOwnedSlice(USER_ID, 2, cursor);

        assertEquals(rows.subList(0, 2), result.items());
        assertTrue(result.hasNext());
        assertEquals(new AppointmentCursor(CREATED_AT, 198L), result.nextCursor());
        verify(appointmentRepository).findUpToTwoPatientIdsOwnedByUser(USER_ID);
        verify(appointmentRepository).findSliceOwnedByUser(USER_ID, cursor, 3);
        verifyNoMoreInteractions(appointmentRepository);
    }

    @Test
    void accountWithoutPatientsShouldReturnEmptyWithoutReadingAppointments() {
        when(appointmentRepository.findUpToTwoPatientIdsOwnedByUser(USER_ID))
                .thenReturn(List.of());

        AppointmentSlice result = appointmentService.findOwnedSlice(USER_ID, 20, null);

        assertTrue(result.items().isEmpty());
        assertFalse(result.hasNext());
        assertNull(result.nextCursor());
        assertEquals(20, result.size());
        verify(appointmentRepository).findUpToTwoPatientIdsOwnedByUser(USER_ID);
        verifyNoMoreInteractions(appointmentRepository);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void terminalSliceShouldReturnAllRowsWithoutAdvertisingAnotherCursor(int rowCount) {
        List<AppointmentCursorItem> rows = IntStream.range(0, rowCount)
                .mapToObj(index -> item(200L - index, PATIENT_ID))
                .toList();
        when(appointmentRepository.findUpToTwoPatientIdsOwnedByUser(USER_ID))
                .thenReturn(List.of(PATIENT_ID));
        when(appointmentRepository.findSliceOwnedBySinglePatient(
                PATIENT_ID, USER_ID, null, 3
        )).thenReturn(rows);

        AppointmentSlice result = appointmentService.findOwnedSlice(USER_ID, 2, null);

        assertEquals(rows, result.items());
        assertFalse(result.hasNext());
        assertNull(result.nextCursor());
        verify(appointmentRepository).findUpToTwoPatientIdsOwnedByUser(USER_ID);
        verify(appointmentRepository).findSliceOwnedBySinglePatient(
                PATIENT_ID, USER_ID, null, 3
        );
        verifyNoMoreInteractions(appointmentRepository);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 51})
    void invalidSizeShouldFailBeforeAnyDatabaseRead(int size) {
        assertValidationFailure(() -> appointmentService.findOwnedSlice(USER_ID, size, null));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, 0L})
    void invalidIdentityShouldFailBeforeAnyDatabaseRead(long userId) {
        assertValidationFailure(() -> appointmentService.findOwnedSlice(userId, 20, null));
    }

    @ParameterizedTest
    @MethodSource("invalidCursors")
    void invalidCursorShouldFailBeforeAnyDatabaseRead(AppointmentCursor cursor) {
        assertValidationFailure(() -> appointmentService.findOwnedSlice(USER_ID, 20, cursor));
    }

    @ParameterizedTest
    @MethodSource("validDateBoundaries")
    void mysqlDatetimeBoundariesAtMillisecondPrecisionShouldBeAccepted(LocalDateTime time) {
        AppointmentCursor cursor = new AppointmentCursor(time, 1L);
        when(appointmentRepository.findUpToTwoPatientIdsOwnedByUser(USER_ID))
                .thenReturn(List.of());

        AppointmentSlice result = appointmentService.findOwnedSlice(USER_ID, 1, cursor);

        assertTrue(result.items().isEmpty());
        verify(appointmentRepository).findUpToTwoPatientIdsOwnedByUser(USER_ID);
        verifyNoMoreInteractions(appointmentRepository);
    }

    private void assertValidationFailure(org.junit.jupiter.api.function.Executable action) {
        BusinessException exception = assertThrows(BusinessException.class, action);
        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
        verifyNoInteractions(appointmentRepository);
    }

    private static Stream<AppointmentCursor> invalidCursors() {
        return Stream.of(
                new AppointmentCursor(CREATED_AT, 0L),
                new AppointmentCursor(CREATED_AT, -1L),
                new AppointmentCursor(null, 1L),
                new AppointmentCursor(CREATED_AT.withNano(123_456_789), 1L),
                new AppointmentCursor(CREATED_AT.withYear(999), 1L),
                new AppointmentCursor(CREATED_AT.withYear(10_000), 1L)
        );
    }

    private static Stream<LocalDateTime> validDateBoundaries() {
        return Stream.of(
                LocalDateTime.of(1000, 1, 1, 0, 0),
                LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_000_000)
        );
    }

    private static AppointmentCursorItem item(long id, long patientId) {
        return new AppointmentCursorItem(
                id,
                patientId,
                44L,
                AppointmentStatus.CANCELLED,
                CREATED_AT.plusMinutes(15),
                CREATED_AT.plusHours(1),
                CREATED_AT
        );
    }
}
