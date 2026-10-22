package com.qqlin.medflow.statistics;

import com.qqlin.medflow.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.qqlin.medflow.statistics.StatisticsResponse.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StatisticsServiceTest {
    StatisticsRepository repository = mock(StatisticsRepository.class);
    StatisticsService service = new StatisticsService(repository);

    @Test void defaultsToDatabaseTodayAndFillsMissingDatesWithZero() {
        LocalDate today = LocalDate.of(2026, 10, 20);
        when(repository.today()).thenReturn(today);
        when(repository.appointmentTrend(today.minusDays(6).atStartOfDay(), today.plusDays(1).atStartOfDay()))
                .thenReturn(List.of(new AppointmentPoint(today, 2)));
        var result = service.get(null, null);
        assertThat(result.from()).isEqualTo(today.minusDays(6));
        assertThat(result.to()).isEqualTo(today);
        assertThat(result.appointmentTrend()).hasSize(7);
        assertThat(result.appointmentTrend().getFirst().count()).isZero();
        assertThat(result.appointmentTrend().getLast().count()).isEqualTo(2);
        verify(repository).departmentVisits(today.minusDays(6).atStartOfDay(), today.plusDays(1).atStartOfDay());
    }

    @Test void explicitRangeUsesHalfOpenEndAndKeepsCompletedAggregates() {
        LocalDate date = LocalDate.of(2026, 10, 20);
        var department = new DepartmentVisits(4, "模拟科室", 3);
        var doctor = new DoctorWorkload(8, "模拟医生", "模拟科室", 3);
        when(repository.departmentVisits(date.atStartOfDay(), date.plusDays(1).atStartOfDay())).thenReturn(List.of(department));
        when(repository.doctorWorkload(date.atStartOfDay(), date.plusDays(1).atStartOfDay())).thenReturn(List.of(doctor));
        var result = service.get(date, date);
        assertThat(result.departmentVisits()).containsExactly(department);
        assertThat(result.doctorWorkload()).containsExactly(doctor);
        assertThat(result.appointmentTrend()).containsExactly(new AppointmentPoint(date, 0));
        verify(repository, never()).today();
    }

    @Test void accepts93InclusiveDaysButRejects94AndInverseRangeBeforeQueries() {
        LocalDate date = LocalDate.of(2026, 10, 20);
        assertThat(service.get(date, date.plusDays(92)).appointmentTrend()).hasSize(93);
        clearInvocations(repository);
        assertThatThrownBy(() -> service.get(date, date.plusDays(93))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.get(date, date.minusDays(1))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.get(LocalDate.of(1999, 1, 1), LocalDate.of(1999, 1, 2))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(repository);
    }
}
