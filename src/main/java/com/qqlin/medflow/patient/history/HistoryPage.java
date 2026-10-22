package com.qqlin.medflow.patient.history;

import com.qqlin.medflow.patient.dto.PatientResponse;
import java.util.List;

public record HistoryPage(PatientResponse patient, List<HistoryVisit> items, long total, int page, int size) {}
