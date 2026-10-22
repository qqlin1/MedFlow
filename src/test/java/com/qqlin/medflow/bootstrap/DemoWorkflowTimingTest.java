package com.qqlin.medflow.bootstrap;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

/** Clock boundary checks do not alter the system/DB clock or production check-in. */
class DemoWorkflowTimingTest {
    private static final LocalDateTime START=LocalDateTime.of(2026,10,20,8,0);
    private static final LocalDateTime END=START.plusMinutes(30);

    @Test void beforeLegalCheckInWindowDoesNotProduceAFutureAttendanceTimestamp() {
        assertThat(DemoWorkflowService.demoCheckInTime(START,END,START.minusMinutes(31))).isNull();
        assertThat(DemoWorkflowService.demoCheckInTime(START,END,START.toLocalDate().atStartOfDay())).isNull();
    }

    @Test void legalWindowStartIsInclusiveAndUsesTheActualCurrentTime() {
        var now=START.minusMinutes(30);
        assertThat(DemoWorkflowService.demoCheckInTime(START,END,now)).isEqualTo(now);
    }

    @Test void beforePreferredSevenFiftyUsesALegalNonFutureTimestamp() {
        var now=START.minusMinutes(20);
        var checkedIn=DemoWorkflowService.demoCheckInTime(START,END,now);
        assertThat(checkedIn).isEqualTo(now).isAfterOrEqualTo(START.minusMinutes(30)).isBefore(END);
    }

    @Test void lateDayDemoUsesPastAttendanceInsideItsOriginalSlotWindow() {
        var now=START.toLocalDate().atTime(21,0);
        assertThat(DemoWorkflowService.demoCheckInTime(START,END,now)).isEqualTo(START.minusMinutes(10)).isBefore(now);
    }

    @Test void attendanceMustStillBelongToTheSlotsCalendarDay() {
        assertThat(DemoWorkflowService.demoCheckInTime(START,END,START.minusDays(1))).isNull();
        assertThat(DemoWorkflowService.demoCheckInTime(START,END,START.plusDays(1))).isNull();
    }
}
