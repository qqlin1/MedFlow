package com.qqlin.medflow.medication.dto;

import com.qqlin.medflow.medication.domain.Drug;
import java.util.List;

public record DrugPageResponse(List<Drug> items, long total, int page, int size) {}
