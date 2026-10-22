package com.qqlin.medflow.scheduling.management;

import com.qqlin.medflow.scheduling.domain.CreateScheduleCommand;
import com.qqlin.medflow.scheduling.domain.ScheduleStatus;
import com.qqlin.medflow.scheduling.management.ScheduleManagementModels.*;
import com.qqlin.medflow.scheduling.service.ScheduleService;
import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScheduleManagementServiceTest {
    ScheduleManagementRepository repository;
    ScheduleService drafts;
    ScheduleManagementService service;
    LocalDate future = LocalDate.now().plusDays(4);

    @BeforeEach void setup() {
        repository = mock(ScheduleManagementRepository.class);
        drafts = mock(ScheduleService.class);
        service = new ScheduleManagementService(repository, drafts);
    }

    @Test void publishedCancellationLocksSlotsBeforeScheduleAndAuditsTrimmedReason() {
        var published = state(ScheduleStatus.PUBLISHED);
        when(repository.findState(1)).thenReturn(Optional.of(published));
        when(repository.findSlotIds(1)).thenReturn(List.of(11L, 12L));
        when(repository.lockSchedule(1)).thenReturn(Optional.of(published));
        LocalDateTime now = future.minusDays(1).atStartOfDay();
        when(repository.databaseTime()).thenReturn(now);
        when(repository.cancel(1, "医生请假", 99, now)).thenReturn(true);
        when(repository.findItem(1)).thenReturn(Optional.of(item(ScheduleStatus.CANCELLED)));

        assertThat(service.cancel(1, 99, "  医生请假  ").status()).isEqualTo(ScheduleStatus.CANCELLED);
        var order = inOrder(repository);
        order.verify(repository).findState(1);
        order.verify(repository).findSlotIds(1);
        order.verify(repository).lockSlots(List.of(11L, 12L));
        order.verify(repository).lockSchedule(1);
        order.verify(repository).databaseTime();
        order.verify(repository).hasActiveAppointments(List.of(11L, 12L));
        order.verify(repository).cancel(1, "医生请假", 99, now);
    }

    @Test void committedBookingAfterLockWaitPreventsCancellation() {
        var published = state(ScheduleStatus.PUBLISHED);
        when(repository.findState(1)).thenReturn(Optional.of(published));
        when(repository.findSlotIds(1)).thenReturn(List.of(11L));
        when(repository.lockSchedule(1)).thenReturn(Optional.of(published));
        when(repository.databaseTime()).thenReturn(future.minusDays(1).atStartOfDay());
        when(repository.hasActiveAppointments(List.of(11L))).thenReturn(true);
        assertCode(() -> service.cancel(1, 99, "请假"), ErrorCode.SCHEDULE_HAS_APPOINTMENTS);
        verify(repository, never()).cancel(anyLong(), anyString(), anyLong(), any());
    }

    @Test void publishWinningDraftRaceFailsWithoutReversingLockOrder() {
        when(repository.findState(1)).thenReturn(Optional.of(state(ScheduleStatus.DRAFT)));
        when(repository.lockSchedule(1)).thenReturn(Optional.of(state(ScheduleStatus.PUBLISHED)));
        assertCode(() -> service.cancel(1, 99, "请假"), ErrorCode.SCHEDULE_NOT_CANCELLABLE);
        verify(repository, never()).lockSlots(anyList());
        verify(repository, never()).findSlotIds(anyLong());
    }

    @Test void repeatedCancellationReturnsOriginalResultWithoutOverwritingAudit() {
        when(repository.findState(1)).thenReturn(Optional.of(state(ScheduleStatus.CANCELLED)));
        when(repository.findItem(1)).thenReturn(Optional.of(item(ScheduleStatus.CANCELLED)));
        assertThat(service.cancel(1, 99, "different reason").status()).isEqualTo(ScheduleStatus.CANCELLED);
        verify(repository, never()).cancel(anyLong(), anyString(), anyLong(), any());
        verify(repository, never()).lockSchedule(anyLong());
    }

    @Test void cancellationAtExactStartIsRejectedUsingPostLockDatabaseTime() {
        when(repository.findState(1)).thenReturn(Optional.of(state(ScheduleStatus.DRAFT)));
        when(repository.lockSchedule(1)).thenReturn(Optional.of(state(ScheduleStatus.DRAFT)));
        when(repository.databaseTime()).thenReturn(future.atTime(8, 0));
        assertCode(() -> service.cancel(1, 99, "请假"), ErrorCode.SCHEDULE_NOT_CANCELLABLE);
        verify(repository, never()).cancel(anyLong(), anyString(), anyLong(), any());
    }

    @Test void batchRejectsDuplicatePastAndExcessDatesBeforeAnyCreate() {
        for (List<LocalDate> dates : List.of(List.of(future, future), List.of(LocalDate.now()),
                java.util.stream.IntStream.range(0, 8).mapToObj(future::plusDays).toList())) {
            assertCode(() -> service.createBatch(new BatchRequest(2, 3, 4, dates)), ErrorCode.VALIDATION_FAILED);
        }
        verifyNoInteractions(drafts);
    }

    @Test void batchSortsDatesAndAddsConflictDateToError() {
        var first = new CreateScheduleCommand(2, 3, 4, future);
        var second = new CreateScheduleCommand(2, 3, 4, future.plusDays(1));
        when(drafts.createDraft(first)).thenReturn(101L);
        when(drafts.createDraft(second)).thenThrow(new BusinessException(ErrorCode.SCHEDULE_CONFLICT));
        assertThatThrownBy(() -> service.createBatch(new BatchRequest(2, 3, 4,
                List.of(future.plusDays(1), future))))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SCHEDULE_CONFLICT);
                    assertThat(exception.getMessage()).contains(future.plusDays(1).toString());
                });
        var order = inOrder(drafts);
        order.verify(drafts).createDraft(first);
        order.verify(drafts).createDraft(second);
    }

    @Test void doctorListIsAlwaysScopedToBoundDoctorRatherThanUserId() {
        when(repository.findDoctorId(99)).thenReturn(Optional.of(7L));
        when(repository.findPage(any())).thenReturn(List.of());
        service.findDoctorPage(99, future, future, null, 2, 10);
        var query = ArgumentCaptor.forClass(Query.class);
        verify(repository).findPage(query.capture());
        assertThat(query.getValue().doctorId()).isEqualTo(7L);
        assertThat(query.getValue().offset()).isEqualTo(10L);
    }

    @Test void unboundDoctorCannotReadScheduleOrAppointmentDirectory() {
        when(repository.findDoctorId(99)).thenReturn(Optional.empty());
        assertCode(() -> service.findDoctorPage(99, null, null, null, 1, 20), ErrorCode.DOCTOR_NOT_FOUND);
        assertCode(() -> service.doctorAppointments(99, future, 1, 20), ErrorCode.DOCTOR_NOT_FOUND);
        verify(repository, never()).findPage(any());
        verify(repository, never()).findDoctorAppointments(anyLong(), any(), anyInt(), anyLong());
    }

    @Test void rejectsUnboundedDatesAndInvalidPaginationBeforeQueries() {
        assertCode(() -> service.findPage(future, future.plusDays(93), null, null, null, 1, 20), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.findPage(future, future.minusDays(1), null, null, null, 1, 20), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.findPage(future, future, null, null, null, 1, 51), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.findPage(future, future, -1L, null, null, 1, 20), ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(repository);
    }

    @Test void missingDetailReturnsDomain404() {
        when(repository.findItem(1)).thenReturn(Optional.empty());
        assertCode(() -> service.detail(1), ErrorCode.SCHEDULE_NOT_FOUND);
    }

    private LockedSchedule state(ScheduleStatus status) {
        return new LockedSchedule(1, future, LocalTime.of(8, 0), status);
    }
    private ScheduleItem item(ScheduleStatus status) {
        return new ScheduleItem(1, 2, "医生", 3, "科室", 4, "诊室", 5L, "上午",
                future, LocalTime.of(8, 0), LocalTime.of(12, 0), status, 80, 80, 0, "医生请假");
    }
    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ErrorCode code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
