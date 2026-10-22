package com.qqlin.medflow.bootstrap;

import java.time.LocalDate;

public record DemoWorkflowSummary(LocalDate workDate, boolean seededToday,
        int schedulesCreated, int slotsCreated, int patientsCreated, int drugsCreated,
        int appointmentsCreated, int encountersCreated, int prescriptionsCreated,
        int paymentsCreated, int skippedSchedules, String message) {}
