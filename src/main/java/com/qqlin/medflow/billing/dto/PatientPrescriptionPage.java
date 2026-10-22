package com.qqlin.medflow.billing.dto;

import java.util.List;

public record PatientPrescriptionPage(List<PatientPrescriptionItem> items, long total, int page, int size) {}
