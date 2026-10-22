package com.qqlin.medflow.billing.mapper;

import com.qqlin.medflow.billing.domain.PrescriptionDrug;
import com.qqlin.medflow.billing.domain.PrescriptionRow;
import com.qqlin.medflow.billing.dto.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface BillingMapper {
    PrescriptionRow findPrescriptionForEncounter(@Param("encounterId") long encounterId);
    PrescriptionRow lockPrescriptionForEncounter(@Param("encounterId") long encounterId);
    PrescriptionDrug lockDrug(@Param("drugId") long drugId);
    int insertPrescription(@Param("encounterId") long encounterId, @Param("total") BigDecimal total);
    int updateDraft(@Param("prescriptionId") long prescriptionId, @Param("version") long version, @Param("total") BigDecimal total);
    int discardDraft(@Param("prescriptionId") long prescriptionId, @Param("version") long version);
    int issueDraft(@Param("prescriptionId") long prescriptionId, @Param("version") long version, @Param("now") LocalDateTime now);
    int deleteItems(@Param("prescriptionId") long prescriptionId);
    int insertItem(@Param("prescriptionId") long prescriptionId, @Param("item") PrescriptionItemResponse item);
    List<PrescriptionItemResponse> findItems(@Param("prescriptionId") long prescriptionId);
    PrescriptionSummary findSummary(@Param("prescriptionId") long prescriptionId);
    PrescriptionSummary findPatientSummary(@Param("prescriptionId") long prescriptionId, @Param("userId") long userId);
    List<PrescriptionSummary> listPatientSummaries(@Param("userId") long userId, @Param("offset") long offset, @Param("size") int size);
    long countPatientSummaries(@Param("userId") long userId);
    BillResponse findBill(@Param("prescriptionId") long prescriptionId);
    List<BillResponse> findBills(@Param("prescriptionIds") List<Long> prescriptionIds);
    int insertBill(@Param("prescriptionId") long prescriptionId, @Param("total") BigDecimal total, @Param("now") LocalDateTime now);
    BillResponse lockOwnedBill(@Param("billId") long billId, @Param("userId") long userId);
    int markBillPaid(@Param("billId") long billId, @Param("now") LocalDateTime now);
    int insertMockPayment(@Param("billId") long billId, @Param("userId") long userId,
            @Param("amount") BigDecimal amount, @Param("now") LocalDateTime now);
    LocalDateTime databaseNow();
}
