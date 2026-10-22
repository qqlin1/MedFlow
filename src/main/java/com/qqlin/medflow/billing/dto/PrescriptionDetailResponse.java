package com.qqlin.medflow.billing.dto;

import java.util.List;

public record PrescriptionDetailResponse(PrescriptionSummary prescription, List<PrescriptionItemResponse> items, BillResponse bill) {}
