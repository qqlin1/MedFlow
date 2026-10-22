package com.qqlin.medflow.billing.service;

import com.qqlin.medflow.billing.domain.*;
import com.qqlin.medflow.billing.dto.*;
import com.qqlin.medflow.billing.mapper.BillingMapper;
import com.qqlin.medflow.encounter.domain.EncounterStatus;
import com.qqlin.medflow.encounter.dto.EncounterSummary;
import com.qqlin.medflow.encounter.mapper.EncounterMapper;
import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.qqlin.medflow.billing.domain.PrescriptionStatus.*;

@Service
public class PrescriptionService {
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");
    private final BillingMapper billing;
    private final EncounterMapper encounters;

    public PrescriptionService(BillingMapper billing, EncounterMapper encounters) {
        this.billing = billing;
        this.encounters = encounters;
    }

    @Transactional(readOnly = true)
    public PrescriptionDetailResponse doctorDetail(long userId, long encounterId) {
        positive(encounterId);
        if (encounters.findDoctorEncounter(encounterId, doctorId(userId)) == null) throw error(ErrorCode.ENCOUNTER_NOT_FOUND);
        PrescriptionRow row = billing.findPrescriptionForEncounter(encounterId);
        return row == null ? null : detail(row.id());
    }

    /** Lock order: Encounter -> Prescription -> Drug ids ascending. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PrescriptionDetailResponse save(long userId, long encounterId, SavePrescriptionRequest request) {
        positive(encounterId);
        List<SavePrescriptionRequest.Item> requested = validate(request);
        requireInProgress(lockEncounter(userId, encounterId));
        PrescriptionRow row = billing.lockPrescriptionForEncounter(encounterId);
        if (row != null && row.status() == ISSUED) throw error(ErrorCode.PRESCRIPTION_STATE_CONFLICT);
        long version = row == null ? 0 : row.version();
        if (request.version() != version) throw error(ErrorCode.PRESCRIPTION_VERSION_CONFLICT);

        Map<Long, PrescriptionDrug> drugs = lockEnabledDrugs(requested.stream().map(SavePrescriptionRequest.Item::drugId).toList());
        List<PrescriptionItemResponse> items = new ArrayList<>();
        BigDecimal total = new BigDecimal("0.00");
        for (SavePrescriptionRequest.Item item : requested) {
            PrescriptionDrug drug = drugs.get(item.drugId());
            BigDecimal price = money(drug.unitPrice());
            BigDecimal line = money(price.multiply(BigDecimal.valueOf(item.quantity())));
            total = money(total.add(line));
            items.add(new PrescriptionItemResponse(0, drug.id(), drug.code(), drug.name(), drug.specification(), drug.unit(),
                    item.quantity(), item.instructions(), price, line));
        }
        if (row == null) {
            billing.insertPrescription(encounterId, total);
            row = billing.findPrescriptionForEncounter(encounterId);
            if (row == null) throw new IllegalStateException("Inserted prescription was not found");
        } else {
            if (billing.updateDraft(row.id(), version, total) != 1) throw error(ErrorCode.PRESCRIPTION_VERSION_CONFLICT);
            billing.deleteItems(row.id());
        }
        for (PrescriptionItemResponse item : items) billing.insertItem(row.id(), item);
        return detail(row.id());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PrescriptionDetailResponse issue(long userId, long encounterId, long version) {
        validVersion(version);
        EncounterSummary encounter = lockEncounter(userId, encounterId);
        PrescriptionRow row = billing.lockPrescriptionForEncounter(encounterId);
        if (row == null) throw error(ErrorCode.PRESCRIPTION_NOT_FOUND);
        // An already issued prescription is a frozen result, including after completion.
        if (row.status() == ISSUED) return detail(row.id());
        requireInProgress(encounter);
        if (row.status() != DRAFT) throw error(ErrorCode.PRESCRIPTION_STATE_CONFLICT);
        if (row.version() != version) throw error(ErrorCode.PRESCRIPTION_VERSION_CONFLICT);
        List<PrescriptionItemResponse> items = billing.findItems(row.id());
        if (items.isEmpty()) throw error(ErrorCode.PRESCRIPTION_STATE_CONFLICT);
        lockEnabledDrugs(items.stream().map(PrescriptionItemResponse::drugId).toList());
        // Current catalog prices are deliberately ignored: issue the saved snapshot.
        LocalDateTime now = billing.databaseNow();
        if (billing.issueDraft(row.id(), version, now) != 1) throw error(ErrorCode.PRESCRIPTION_VERSION_CONFLICT);
        billing.insertBill(row.id(), row.totalAmount(), now);
        return detail(row.id());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PrescriptionDetailResponse discard(long userId, long encounterId, long version) {
        validVersion(version);
        EncounterSummary encounter = lockEncounter(userId, encounterId);
        PrescriptionRow row = billing.lockPrescriptionForEncounter(encounterId);
        if (row == null) throw error(ErrorCode.PRESCRIPTION_NOT_FOUND);
        // A completed encounter can replay a prior discard without any new write.
        if (row.status() == DISCARDED) return detail(row.id());
        requireInProgress(encounter);
        if (row.status() != DRAFT) throw error(ErrorCode.PRESCRIPTION_STATE_CONFLICT);
        if (row.version() != version) throw error(ErrorCode.PRESCRIPTION_VERSION_CONFLICT);
        if (billing.discardDraft(row.id(), version) != 1) throw error(ErrorCode.PRESCRIPTION_VERSION_CONFLICT);
        billing.deleteItems(row.id());
        return detail(row.id());
    }

    @Transactional(readOnly = true)
    public PrescriptionDetailResponse patientDetail(long userId, long prescriptionId) {
        positive(prescriptionId);
        PrescriptionSummary summary = billing.findPatientSummary(prescriptionId, userId);
        if (summary == null) throw error(ErrorCode.PRESCRIPTION_NOT_FOUND);
        return detail(summary);
    }

    @Transactional(readOnly = true)
    public PatientPrescriptionPage patientPage(long userId, int page, int size) {
        if (page < 1 || size < 1 || size > 50) throw error(ErrorCode.VALIDATION_FAILED);
        List<PrescriptionSummary> summaries = billing.listPatientSummaries(userId, ((long) page - 1) * size, size);
        Map<Long, BillResponse> bills = summaries.isEmpty() ? Map.of() : billing.findBills(
                summaries.stream().map(PrescriptionSummary::id).toList()).stream()
                .collect(Collectors.toMap(BillResponse::prescriptionId, Function.identity()));
        return new PatientPrescriptionPage(summaries.stream().map(p -> PatientPrescriptionItem.from(p, bills.get(p.id()))).toList(),
                billing.countPatientSummaries(userId), page, size);
    }

    /** Payment never locks Encounter or Prescription, so it cannot reverse their order. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PrescriptionDetailResponse pay(long userId, long billId) {
        positive(billId);
        BillResponse bill = billing.lockOwnedBill(billId, userId);
        if (bill == null) throw error(ErrorCode.BILL_NOT_FOUND);
        if (bill.status() == BillStatus.PAID) return patientDetail(userId, bill.prescriptionId());
        if (bill.status() != BillStatus.UNPAID) throw error(ErrorCode.BILL_STATE_CONFLICT);
        LocalDateTime now = billing.databaseNow();
        if (billing.markBillPaid(billId, now) != 1) throw error(ErrorCode.BILL_STATE_CONFLICT);
        billing.insertMockPayment(billId, userId, bill.totalAmount(), now);
        return patientDetail(userId, bill.prescriptionId());
    }

    private PrescriptionDetailResponse detail(long id) {
        PrescriptionSummary summary = billing.findSummary(id);
        if (summary == null) throw new IllegalStateException("Prescription summary was not found");
        return detail(summary);
    }

    private PrescriptionDetailResponse detail(PrescriptionSummary summary) {
        return new PrescriptionDetailResponse(summary, billing.findItems(summary.id()), billing.findBill(summary.id()));
    }

    private long doctorId(long userId) {
        Long doctorId = encounters.findDoctorId(userId);
        if (doctorId == null) throw error(ErrorCode.DOCTOR_NOT_FOUND);
        return doctorId;
    }

    private EncounterSummary lockEncounter(long userId, long encounterId) {
        positive(encounterId);
        EncounterSummary encounter = encounters.lockDoctorEncounter(encounterId, doctorId(userId));
        if (encounter == null) throw error(ErrorCode.ENCOUNTER_NOT_FOUND);
        return encounter;
    }

    private Map<Long, PrescriptionDrug> lockEnabledDrugs(List<Long> ids) {
        Map<Long, PrescriptionDrug> result = new HashMap<>();
        for (long id : ids.stream().distinct().sorted().toList()) {
            PrescriptionDrug drug = billing.lockDrug(id);
            if (drug == null) throw error(ErrorCode.DRUG_NOT_FOUND);
            if (!"ENABLED".equals(drug.status())) throw error(ErrorCode.DRUG_DISABLED);
            result.put(id, drug);
        }
        return result;
    }

    private static List<SavePrescriptionRequest.Item> validate(SavePrescriptionRequest request) {
        if (request == null || request.version() == null || request.version() < 0 || request.items() == null
                || request.items().isEmpty() || request.items().size() > 20) throw error(ErrorCode.VALIDATION_FAILED);
        Set<Long> ids = new HashSet<>();
        List<SavePrescriptionRequest.Item> items = new ArrayList<>();
        for (SavePrescriptionRequest.Item item : request.items()) {
            if (item == null || item.drugId() == null || item.drugId() <= 0 || !ids.add(item.drugId())
                    || item.quantity() == null || item.quantity() < 1 || item.quantity() > 1000
                    || item.instructions() != null && item.instructions().length() > 1000) throw error(ErrorCode.VALIDATION_FAILED);
            items.add(new SavePrescriptionRequest.Item(item.drugId(), item.quantity(), item.instructions() == null ? "" : item.instructions().strip()));
        }
        return items;
    }

    private static BigDecimal money(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(MAX_AMOUNT) > 0) throw error(ErrorCode.AMOUNT_LIMIT_EXCEEDED);
        try { return value.setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException exception) { throw error(ErrorCode.AMOUNT_LIMIT_EXCEEDED); }
    }

    private static void requireInProgress(EncounterSummary encounter) {
        if (encounter.status() != EncounterStatus.IN_PROGRESS) throw error(ErrorCode.PRESCRIPTION_STATE_CONFLICT);
    }

    private static void positive(long id) { if (id <= 0) throw error(ErrorCode.VALIDATION_FAILED); }
    private static void validVersion(long version) { if (version < 0) throw error(ErrorCode.VALIDATION_FAILED); }
    private static BusinessException error(ErrorCode code) { return new BusinessException(code); }
}
