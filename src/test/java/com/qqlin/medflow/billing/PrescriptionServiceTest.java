package com.qqlin.medflow.billing;

import com.qqlin.medflow.billing.domain.*;
import com.qqlin.medflow.billing.dto.*;
import com.qqlin.medflow.billing.mapper.BillingMapper;
import com.qqlin.medflow.billing.service.PrescriptionService;
import com.qqlin.medflow.encounter.domain.EncounterStatus;
import com.qqlin.medflow.encounter.dto.EncounterSummary;
import com.qqlin.medflow.encounter.mapper.EncounterMapper;
import com.qqlin.medflow.shared.exception.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.qqlin.medflow.billing.domain.PrescriptionStatus.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PrescriptionServiceTest {
    static final long USER=10, DOCTOR=20, ENCOUNTER=30, RX=40, DRUG=50, BILL=60;
    static final LocalDateTime NOW=LocalDateTime.of(2026,10,20,9,30);
    @Mock BillingMapper billing;
    @Mock EncounterMapper encounters;
    PrescriptionService service;

    @BeforeEach void setup() { service=new PrescriptionService(billing,encounters); }

    @Test void doctorWithoutOwnedEncounterCannotReadPrescription() {
        when(encounters.findDoctorId(USER)).thenReturn(DOCTOR);
        fails(ErrorCode.ENCOUNTER_NOT_FOUND, () -> service.doctorDetail(USER,ENCOUNTER));
        verifyNoInteractions(billing);
    }

    @Test void missingPrescriptionReturnsNullOnlyAfterOwnershipCheck() {
        when(encounters.findDoctorId(USER)).thenReturn(DOCTOR);
        when(encounters.findDoctorEncounter(ENCOUNTER,DOCTOR)).thenReturn(encounter(EncounterStatus.IN_PROGRESS));
        assertThat(service.doctorDetail(USER,ENCOUNTER)).isNull();
    }

    @Test void duplicateDrugIdsAndZeroQuantityFailBeforeReadingDatabase() {
        fails(ErrorCode.VALIDATION_FAILED, () -> service.save(USER,ENCOUNTER,new SavePrescriptionRequest(0L,
                List.of(new SavePrescriptionRequest.Item(DRUG,1,""),new SavePrescriptionRequest.Item(DRUG,2,"")))));
        fails(ErrorCode.VALIDATION_FAILED, () -> service.save(USER,ENCOUNTER,request(0,0)));
        verifyNoInteractions(billing,encounters);
    }

    @Test void completedEncounterCannotAcquirePrescriptionForWriting() {
        doctorLocked(EncounterStatus.COMPLETED);
        fails(ErrorCode.PRESCRIPTION_STATE_CONFLICT, () -> service.save(USER,ENCOUNTER,request(0,2)));
        verifyNoInteractions(billing);
    }

    @Test void saveComputesDecimalAmountFromDatabasePriceAndLocksEncounterFirst() {
        doctorLocked(EncounterStatus.IN_PROGRESS);
        when(billing.lockDrug(DRUG)).thenReturn(drug(DRUG,"0.10","ENABLED"));
        when(billing.findPrescriptionForEncounter(ENCOUNTER)).thenReturn(row(DRAFT,1,"0.30"));
        when(billing.findSummary(RX)).thenReturn(summary(DRAFT,1,"0.30"));
        service.save(USER,ENCOUNTER,request(0,3));
        ArgumentCaptor<PrescriptionItemResponse> item=ArgumentCaptor.forClass(PrescriptionItemResponse.class);
        verify(billing).insertItem(eq(RX),item.capture());
        assertThat(item.getValue().unitPrice()).isEqualByComparingTo("0.10");
        assertThat(item.getValue().lineAmount()).isEqualByComparingTo("0.30");
        verify(billing).insertPrescription(ENCOUNTER,new BigDecimal("0.30"));
        var order=inOrder(encounters,billing);
        order.verify(encounters).findDoctorId(USER);
        order.verify(encounters).lockDoctorEncounter(ENCOUNTER,DOCTOR);
        order.verify(billing).lockPrescriptionForEncounter(ENCOUNTER);
        order.verify(billing).lockDrug(DRUG);
    }

    @Test void drugLocksAlwaysFollowAscendingIdOrderDespiteRequestOrder() {
        doctorLocked(EncounterStatus.IN_PROGRESS);
        when(billing.lockDrug(1)).thenReturn(drug(1,"1.00","ENABLED"));
        when(billing.lockDrug(2)).thenReturn(drug(2,"2.00","ENABLED"));
        when(billing.findPrescriptionForEncounter(ENCOUNTER)).thenReturn(row(DRAFT,1,"3.00"));
        when(billing.findSummary(RX)).thenReturn(summary(DRAFT,1,"3.00"));
        service.save(USER,ENCOUNTER,new SavePrescriptionRequest(0L,List.of(
                new SavePrescriptionRequest.Item(2L,1,""),new SavePrescriptionRequest.Item(1L,1,""))));
        var order=inOrder(billing);
        order.verify(billing).lockDrug(1);
        order.verify(billing).lockDrug(2);
    }

    @Test void disabledDrugCannotBeSaved() {
        doctorLocked(EncounterStatus.IN_PROGRESS);
        when(billing.lockDrug(DRUG)).thenReturn(drug(DRUG,"2.00","DISABLED"));
        fails(ErrorCode.DRUG_DISABLED, () -> service.save(USER,ENCOUNTER,request(0,1)));
        verify(billing,never()).insertPrescription(anyLong(),any());
    }

    @Test void oldDraftVersionCannotReplaceItems() {
        doctorLocked(EncounterStatus.IN_PROGRESS);
        when(billing.lockPrescriptionForEncounter(ENCOUNTER)).thenReturn(row(DRAFT,2,"2.00"));
        fails(ErrorCode.PRESCRIPTION_VERSION_CONFLICT, () -> service.save(USER,ENCOUNTER,request(1,1)));
        verify(billing,never()).deleteItems(anyLong());
        verify(billing,never()).lockDrug(anyLong());
    }

    @Test void totalBeyondDatabasePrecisionFailsBeforeAnyRowsAreWritten() {
        doctorLocked(EncounterStatus.IN_PROGRESS);
        var items=new ArrayList<SavePrescriptionRequest.Item>();
        for(long id=1;id<=11;id++) {
            items.add(new SavePrescriptionRequest.Item(id,1000,""));
            when(billing.lockDrug(id)).thenReturn(drug(id,"999999.99","ENABLED"));
        }
        fails(ErrorCode.AMOUNT_LIMIT_EXCEEDED, () -> service.save(USER,ENCOUNTER,new SavePrescriptionRequest(0L,items)));
        verify(billing,never()).insertPrescription(anyLong(),any());
    }

    @Test void issueKeepsSavedPriceEvenAfterCatalogPriceChanges() {
        doctorLocked(EncounterStatus.IN_PROGRESS);
        when(billing.lockPrescriptionForEncounter(ENCOUNTER)).thenReturn(row(DRAFT,1,"6.60"));
        when(billing.findItems(RX)).thenReturn(List.of(item("3.30",2)));
        when(billing.lockDrug(DRUG)).thenReturn(drug(DRUG,"99.99","ENABLED"));
        when(billing.databaseNow()).thenReturn(NOW);
        when(billing.issueDraft(RX,1,NOW)).thenReturn(1);
        when(billing.findSummary(RX)).thenReturn(summary(ISSUED,2,"6.60"));
        service.issue(USER,ENCOUNTER,1);
        verify(billing).insertBill(RX,new BigDecimal("6.60"),NOW);
        verify(billing,never()).updateDraft(anyLong(),anyLong(),any());
        verify(billing,never()).insertItem(anyLong(),any());
    }

    @Test void disabledDrugBlocksIssueWithoutFreezingDraft() {
        doctorLocked(EncounterStatus.IN_PROGRESS);
        when(billing.lockPrescriptionForEncounter(ENCOUNTER)).thenReturn(row(DRAFT,1,"6.60"));
        when(billing.findItems(RX)).thenReturn(List.of(item("3.30",2)));
        when(billing.lockDrug(DRUG)).thenReturn(drug(DRUG,"3.30","DISABLED"));
        fails(ErrorCode.DRUG_DISABLED, () -> service.issue(USER,ENCOUNTER,1));
        verify(billing,never()).issueDraft(anyLong(),anyLong(),any());
        verify(billing,never()).insertBill(anyLong(),any(),any());
    }

    @Test void repeatIssueAfterEncounterCompletionReturnsFrozenResult() {
        doctorLocked(EncounterStatus.COMPLETED);
        when(billing.lockPrescriptionForEncounter(ENCOUNTER)).thenReturn(row(ISSUED,2,"6.60"));
        when(billing.findSummary(RX)).thenReturn(summary(ISSUED,2,"6.60"));
        service.issue(USER,ENCOUNTER,1);
        verify(billing,never()).lockDrug(anyLong());
        verify(billing,never()).insertBill(anyLong(),any(),any());
    }

    @Test void discardClearsItemsWithoutGeneratingBill() {
        doctorLocked(EncounterStatus.IN_PROGRESS);
        when(billing.lockPrescriptionForEncounter(ENCOUNTER)).thenReturn(row(DRAFT,1,"6.60"));
        when(billing.discardDraft(RX,1)).thenReturn(1);
        when(billing.findSummary(RX)).thenReturn(summary(DISCARDED,2,"0.00"));
        assertThat(service.discard(USER,ENCOUNTER,1).prescription().status()).isEqualTo(DISCARDED);
        verify(billing).deleteItems(RX);
        verify(billing,never()).insertBill(anyLong(),any(),any());
    }

    @Test void discardedPrescriptionReopensWithSameIdentityAndIncreasingVersion() {
        doctorLocked(EncounterStatus.IN_PROGRESS);
        when(billing.lockPrescriptionForEncounter(ENCOUNTER)).thenReturn(row(DISCARDED,2,"0.00"));
        when(billing.lockDrug(DRUG)).thenReturn(drug(DRUG,"3.30","ENABLED"));
        when(billing.updateDraft(RX,2,new BigDecimal("6.60"))).thenReturn(1);
        when(billing.findSummary(RX)).thenReturn(summary(DRAFT,3,"6.60"));
        assertThat(service.save(USER,ENCOUNTER,request(2,2)).prescription().id()).isEqualTo(RX);
        verify(billing,never()).insertPrescription(anyLong(),any());
    }

    @Test void repeatDiscardAfterEncounterCompletionReturnsOriginalWithoutAnyWrite() {
        doctorLocked(EncounterStatus.COMPLETED);
        when(billing.lockPrescriptionForEncounter(ENCOUNTER)).thenReturn(row(DISCARDED,2,"0.00"));
        when(billing.findSummary(RX)).thenReturn(summary(DISCARDED,2,"0.00"));
        var replay=service.discard(USER,ENCOUNTER,1);
        assertThat(replay.prescription().status()).isEqualTo(DISCARDED);
        assertThat(replay.prescription().version()).isEqualTo(2);
        verify(billing,never()).discardDraft(anyLong(),anyLong());
        verify(billing,never()).deleteItems(anyLong());
        verify(billing,never()).insertBill(anyLong(),any(),any());
    }

    @Test void issuedPrescriptionCannotBeDiscardedOrRewritten() {
        doctorLocked(EncounterStatus.IN_PROGRESS);
        when(billing.lockPrescriptionForEncounter(ENCOUNTER)).thenReturn(row(ISSUED,2,"6.60"));
        fails(ErrorCode.PRESCRIPTION_STATE_CONFLICT, () -> service.discard(USER,ENCOUNTER,2));
        fails(ErrorCode.PRESCRIPTION_STATE_CONFLICT, () -> service.save(USER,ENCOUNTER,request(2,2)));
    }

    @Test void patientCannotReadDraftOrOtherOwnersPrescription() {
        fails(ErrorCode.PRESCRIPTION_NOT_FOUND, () -> service.patientDetail(USER,RX));
        verify(billing,never()).findItems(anyLong());
    }

    @Test void unownedBillCannotBePaid() {
        fails(ErrorCode.BILL_NOT_FOUND, () -> service.pay(USER,BILL));
        verify(billing,never()).markBillPaid(anyLong(),any());
    }

    @Test void paymentUsesLockedServerAmountAndRecordsSameTimestamp() {
        when(billing.lockOwnedBill(BILL,USER)).thenReturn(new BillResponse(BILL,RX,BillStatus.UNPAID,new BigDecimal("6.60"),null));
        when(billing.databaseNow()).thenReturn(NOW);
        when(billing.markBillPaid(BILL,NOW)).thenReturn(1);
        when(billing.findPatientSummary(RX,USER)).thenReturn(summary(ISSUED,2,"6.60"));
        service.pay(USER,BILL);
        verify(billing).insertMockPayment(BILL,USER,new BigDecimal("6.60"),NOW);
        verifyNoInteractions(encounters);
    }

    @Test void paidBillRetryDoesNotCreateAnotherPayment() {
        when(billing.lockOwnedBill(BILL,USER)).thenReturn(new BillResponse(BILL,RX,BillStatus.PAID,new BigDecimal("6.60"),NOW));
        when(billing.findPatientSummary(RX,USER)).thenReturn(summary(ISSUED,2,"6.60"));
        service.pay(USER,BILL);
        verify(billing,never()).databaseNow();
        verify(billing,never()).insertMockPayment(anyLong(),anyLong(),any(),any());
    }

    private void doctorLocked(EncounterStatus status) {
        when(encounters.findDoctorId(USER)).thenReturn(DOCTOR);
        when(encounters.lockDoctorEncounter(ENCOUNTER,DOCTOR)).thenReturn(encounter(status));
    }
    private EncounterSummary encounter(EncounterStatus status) {
        return new EncounterSummary(ENCOUNTER,70,80,"Patient",DOCTOR,"Doctor","Department","Room",NOW.toLocalDate(),
                NOW.toLocalTime(),NOW.plusMinutes(30).toLocalTime(),status,NOW,NOW,null,1);
    }
    private PrescriptionDrug drug(long id,String price,String status) {
        return new PrescriptionDrug(id,"DRUG-"+id,"Test drug","10mg","片",new BigDecimal(price),status);
    }
    private PrescriptionRow row(PrescriptionStatus status,long version,String total) {
        return new PrescriptionRow(RX,ENCOUNTER,status,new BigDecimal(total),version);
    }
    private PrescriptionSummary summary(PrescriptionStatus status,long version,String total) {
        return new PrescriptionSummary(RX,ENCOUNTER,70,"Patient","Doctor","Department",status,new BigDecimal(total),version,NOW,status==ISSUED?NOW:null);
    }
    private PrescriptionItemResponse item(String price,int quantity) {
        return new PrescriptionItemResponse(1,DRUG,"DRUG-50","Test drug","10mg","片",quantity,"每日一次",new BigDecimal(price),new BigDecimal(price).multiply(BigDecimal.valueOf(quantity)));
    }
    private SavePrescriptionRequest request(long version,int quantity) {
        return new SavePrescriptionRequest(version,List.of(new SavePrescriptionRequest.Item(DRUG,quantity,"每日一次")));
    }
    private void fails(ErrorCode expected,Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }
}
