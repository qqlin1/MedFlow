package com.qqlin.medflow.scheduling.service;

import com.qqlin.medflow.scheduling.domain.AvailableSlot;
import com.qqlin.medflow.scheduling.domain.AvailableSlotPage;
import com.qqlin.medflow.scheduling.repository.SlotQueryRepository;
import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Service tests prove validation and database-clock delegation, not SQL eligibility. */
@ExtendWith(MockitoExtension.class)
class SlotQueryServiceTest {

    private static final LocalDate WORK_DATE = LocalDate.of(2026, 10, 5);
    private static final LocalDateTime DATABASE_NOW =
            LocalDateTime.of(2026, 10, 4, 16, 30, 0, 123_000_000);

    @Mock
    private SlotQueryRepository repository;

    private SlotQueryService service;

    @BeforeEach
    void setUp() {
        service = new SlotQueryService(repository);
    }

    @Test
    void shouldUseSameDatabaseClockForCountAndPageAndPreserveFilters() {
        List<AvailableSlot> slots = List.of(slot());
        when(repository.currentDatabaseTime()).thenReturn(DATABASE_NOW);
        when(repository.countAvailable(WORK_DATE, 11L, 22L, DATABASE_NOW)).thenReturn(21L);
        when(repository.findAvailablePage(WORK_DATE, 11L, 22L, DATABASE_NOW, 10L, 10))
                .thenReturn(slots);

        AvailableSlotPage result = service.findAvailable(WORK_DATE, 11L, 22L, 2, 10);

        assertEquals(slots, result.items());
        assertEquals(21L, result.total());
        assertEquals(2, result.page());
        assertEquals(10, result.size());
        verify(repository).currentDatabaseTime();
        verify(repository).countAvailable(WORK_DATE, 11L, 22L, DATABASE_NOW);
        verify(repository).findAvailablePage(WORK_DATE, 11L, 22L, DATABASE_NOW, 10L, 10);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void shouldReturnEmptyPageWithoutListQueryWhenNothingIsAvailable() {
        when(repository.currentDatabaseTime()).thenReturn(DATABASE_NOW);
        when(repository.countAvailable(WORK_DATE, null, null, DATABASE_NOW)).thenReturn(0L);

        AvailableSlotPage result = service.findAvailable(WORK_DATE, null, null, 1, 20);

        assertTrue(result.items().isEmpty());
        assertEquals(0L, result.total());
        verify(repository).currentDatabaseTime();
        verify(repository).countAvailable(WORK_DATE, null, null, DATABASE_NOW);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void shouldPreserveTotalForPageBeyondLastPageWithoutReadingItems() {
        when(repository.currentDatabaseTime()).thenReturn(DATABASE_NOW);
        when(repository.countAvailable(WORK_DATE, null, 22L, DATABASE_NOW)).thenReturn(2L);

        AvailableSlotPage result = service.findAvailable(WORK_DATE, null, 22L, 2, 20);

        assertEquals(2L, result.total());
        assertTrue(result.items().isEmpty());
        verify(repository).currentDatabaseTime();
        verify(repository).countAvailable(WORK_DATE, null, 22L, DATABASE_NOW);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void shouldCalculateLargeOffsetWithoutIntegerOverflow() {
        when(repository.currentDatabaseTime()).thenReturn(DATABASE_NOW);
        when(repository.countAvailable(WORK_DATE, null, null, DATABASE_NOW))
                .thenReturn(Long.MAX_VALUE);
        when(repository.findAvailablePage(
                WORK_DATE, null, null, DATABASE_NOW, 107_374_182_300L, 50))
                .thenReturn(List.of());

        service.findAvailable(WORK_DATE, null, null, Integer.MAX_VALUE, 50);

        verify(repository).findAvailablePage(
                WORK_DATE, null, null, DATABASE_NOW, 107_374_182_300L, 50);
    }

    @Test
    void shouldRejectInvalidInputBeforeAccessingDatabase() {
        assertInvalid(null, null, null, 1, 20);
        assertInvalid(LocalDate.of(999, 1, 1), null, null, 1, 20);
        assertInvalid(LocalDate.of(10000, 1, 1), null, null, 1, 20);
        assertInvalid(WORK_DATE, 0L, null, 1, 20);
        assertInvalid(WORK_DATE, null, -1L, 1, 20);
        assertInvalid(WORK_DATE, null, null, 0, 20);
        assertInvalid(WORK_DATE, null, null, 1, 0);
        assertInvalid(WORK_DATE, null, null, 1, 51);
        verifyNoInteractions(repository);
    }

    @Test
    void shouldFailWhenDatabaseClockCannotBeRead() {
        assertThrows(IllegalStateException.class,
                () -> service.findAvailable(WORK_DATE, null, null, 1, 20));
        verify(repository).currentDatabaseTime();
        verifyNoMoreInteractions(repository);
    }

    private void assertInvalid(LocalDate date, Long departmentId,
                               Long doctorId, int page, int size) {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.findAvailable(date, departmentId, doctorId, page, size));
        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    private AvailableSlot slot() {
        return new AvailableSlot(
                101L, 102L, 11L, "内科", 22L, "陈医生", 33L, "第一诊室",
                WORK_DATE, LocalTime.of(9, 0), LocalTime.of(9, 30),
                WORK_DATE.atTime(8, 30), 5);
    }
}
