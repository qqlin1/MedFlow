package com.qqlin.medflow.medication.service;

import com.qqlin.medflow.medication.domain.*;
import com.qqlin.medflow.medication.dto.*;
import com.qqlin.medflow.medication.repository.DrugRepository;
import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DrugServiceTest {
    DrugRepository repository;
    DrugService service;
    @BeforeEach void setup() {
        repository = mock(DrugRepository.class);
        service = new DrugService(repository);
    }

    @Test void createTrimsTextNormalizesPriceAndStartsEnabledAtVersionZero() {
        when(repository.insert("D001", "模拟药品", "10mg × 10片", "盒", new BigDecimal("12.30"))).thenReturn(7L);
        Drug drug = service.create(new CreateDrugRequest(" D001 ", " 模拟药品 ", " 10mg × 10片 ", " 盒 ", new BigDecimal("12.3")));
        assertThat(drug).isEqualTo(new Drug(7, "D001", "模拟药品", "10mg × 10片", "盒", new BigDecimal("12.30"), DrugStatus.ENABLED, 0));
    }

    @Test void zeroNegativeOverPrecisionAndOversizedPricesNeverReachDatabase() {
        for (BigDecimal value : Arrays.asList(null, BigDecimal.ZERO, new BigDecimal("-1"),
                new BigDecimal("0.001"), new BigDecimal("1.230"), new BigDecimal("1000000.00"))) {
            assertCode(() -> service.create(create("D001", value)), ErrorCode.VALIDATION_FAILED);
        }
        verifyNoInteractions(repository);
    }

    @Test void minimumAndMaximumPricesAreAcceptedWithoutFloatingPointRounding() {
        when(repository.insert(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(7L);
        assertThat(service.create(create("D001", new BigDecimal("0.01"))).unitPrice()).isEqualTo(new BigDecimal("0.01"));
        assertThat(service.create(create("D002", new BigDecimal("999999.99"))).unitPrice()).isEqualTo(new BigDecimal("999999.99"));
    }

    @Test void missingAndOversizedTextIsRejectedBeforeWrites() {
        assertCode(() -> service.create(null), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.create(create(" ", BigDecimal.ONE)), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.create(create("x".repeat(33), BigDecimal.ONE)), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.create(new CreateDrugRequest("D001", "药品", "  ", "盒", BigDecimal.ONE)), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.create(new CreateDrugRequest("D001", "药品", "10mg", "x".repeat(21), BigDecimal.ONE)), ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(repository);
    }

    @Test void duplicateCodeIsReportedAsBusinessConflict() {
        when(repository.insert(anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new DuplicateKeyException("unique drug code"));
        assertCode(() -> service.create(create("D001", BigDecimal.ONE)), ErrorCode.DRUG_CODE_ALREADY_EXISTS);
    }

    @Test void editKeepsCodeAndIncrementsVersionWhenConditionalUpdateWins() {
        when(repository.findById(7)).thenReturn(Optional.of(drug()));
        when(repository.updateIfVersion(7, "新名称", "20mg", "瓶", new BigDecimal("4.50"), DrugStatus.DISABLED, 0)).thenReturn(true);
        Drug edited = service.update(7, new UpdateDrugRequest(" 新名称 ", "20mg", "瓶", new BigDecimal("4.5"), DrugStatus.DISABLED, 0L));
        assertThat(edited.code()).isEqualTo("D001");
        assertThat(edited.version()).isEqualTo(1);
        assertThat(edited.status()).isEqualTo(DrugStatus.DISABLED);
        assertThat(edited.unitPrice()).isEqualTo(new BigDecimal("4.50"));
    }

    @Test void concurrentEditorLosingVersionConditionGets409InsteadOfOverwriting() {
        when(repository.findById(7)).thenReturn(Optional.of(drug()));
        when(repository.updateIfVersion(anyLong(), anyString(), anyString(), anyString(), any(), any(), anyLong())).thenReturn(false);
        assertCode(() -> service.update(7, update(0L)), ErrorCode.DRUG_VERSION_CONFLICT);
    }

    @Test void missingDrugAndMissingVersionHaveDifferentFailures() {
        when(repository.findById(7)).thenReturn(Optional.empty());
        assertCode(() -> service.update(7, update(0L)), ErrorCode.DRUG_NOT_FOUND);
        assertCode(() -> service.update(7, update(null)), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.update(7, update(-1L)), ErrorCode.VALIDATION_FAILED);
    }

    @Test void doctorCatalogUsesEnabledFilterAndEscapesLiteralSearchCharacters() {
        when(repository.count("A!_!%!!", DrugStatus.ENABLED)).thenReturn(1L);
        when(repository.findPage("A!_!%!!", DrugStatus.ENABLED, 0, 10)).thenReturn(List.of(drug()));
        DrugPageResponse page = service.doctorPage(" A_%! ", 1, 10);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
        verify(repository).findPage("A!_!%!!", DrugStatus.ENABLED, 0, 10);
    }

    @Test void administratorCanFilterDisabledRowsAndPageWithStableOffset() {
        when(repository.count(null, DrugStatus.DISABLED)).thenReturn(13L);
        when(repository.findPage(null, DrugStatus.DISABLED, 10, 10)).thenReturn(List.of(drug()));
        DrugPageResponse page = service.adminPage("  ", DrugStatus.DISABLED, 2, 10);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(10);
        verify(repository).findPage(null, DrugStatus.DISABLED, 10, 10);
    }

    @Test void pageBeyondTotalReturnsEmptyWithoutReadingRows() {
        when(repository.count(null, null)).thenReturn(1L);
        assertThat(service.adminPage(null, null, 100, 20).items()).isEmpty();
        verify(repository, never()).findPage(any(), any(), anyLong(), anyInt());
    }

    @Test void invalidPaginationAndLongSearchDoNotReadDatabase() {
        assertCode(() -> service.adminPage(null, null, 0, 20), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.adminPage(null, null, 1, 51), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.doctorPage("x".repeat(101), 1, 20), ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(repository);
    }

    private CreateDrugRequest create(String code, BigDecimal price) { return new CreateDrugRequest(code, "模拟药品", "10mg", "盒", price); }
    private UpdateDrugRequest update(Long version) { return new UpdateDrugRequest("模拟药品", "10mg", "盒", BigDecimal.ONE, DrugStatus.ENABLED, version); }
    private Drug drug() { return new Drug(7, "D001", "模拟药品", "10mg", "盒", new BigDecimal("1.00"), DrugStatus.ENABLED, 0); }
    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ErrorCode code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(code));
    }
}
