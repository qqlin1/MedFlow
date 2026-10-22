package com.qqlin.medflow.organization.detail;

import com.qqlin.medflow.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DoctorDetailServiceTest {
    final DoctorDetailRepository repository=mock(DoctorDetailRepository.class);
    final DoctorDetailCache cache=mock(DoctorDetailCache.class);
    final DoctorDetailService service=new DoctorDetailService(repository,cache);
    final DoctorDetail doctor=new DoctorDetail(7,2,"演示医生","全科");

    @Test void missLoadsDatabaseAndFillsCache() {
        when(cache.read(7)).thenReturn(Optional.empty()); when(repository.findEnabled(7)).thenReturn(Optional.of(doctor));
        assertThat(service.get(7)).isEqualTo(doctor); verify(repository).findEnabled(7); verify(cache).put(7,Optional.of(doctor));
    }
    @Test void hitDoesNotReadMysql() {
        when(cache.read(7)).thenReturn(Optional.of(Optional.of(doctor)));
        assertThat(service.get(7)).isEqualTo(doctor); verifyNoInteractions(repository); verify(cache,never()).put(anyLong(),any());
    }
    @Test void negativeHitReturns404WithoutDatabase() {
        when(cache.read(7)).thenReturn(Optional.of(Optional.empty()));
        assertThatThrownBy(() -> service.get(7)).isInstanceOf(BusinessException.class).hasMessageContaining("医生");
        verifyNoInteractions(repository);
    }
    @Test void missingRowIsNegativelyCached() {
        when(cache.read(7)).thenReturn(Optional.empty()); when(repository.findEnabled(7)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(7)).isInstanceOf(BusinessException.class);
        verify(cache).put(7,Optional.empty());
    }
    @Test void databaseOutageIsNotCachedAsMissing() {
        when(cache.read(7)).thenReturn(Optional.empty()); when(repository.findEnabled(7)).thenThrow(new IllegalStateException("db down"));
        assertThatThrownBy(() -> service.get(7)).hasMessage("db down"); verify(cache,never()).put(anyLong(),any());
    }
    @Test void invalidIdDoesNotReachCacheOrDatabase() {
        assertThatThrownBy(() -> service.get(0)).isInstanceOf(BusinessException.class); verifyNoInteractions(repository,cache);
    }
    @Test void updateDefersEvictionUntilCommitCallback() {
        when(repository.findEnabled(7)).thenReturn(Optional.of(doctor)); when(repository.updateName(7,"新姓名")).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.updateName(7," 新姓名 "); verify(cache,never()).evict(anyLong());
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit()); verify(cache).evict(7);
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
    @Test void invalidUpdateCannotRegisterInvalidation() {
        assertThatThrownBy(() -> service.updateName(7," ")).isInstanceOf(BusinessException.class); verifyNoInteractions(repository,cache);
    }

    @Test void publicProfileUpdatePreservesOmittedFieldsAndInvalidatesOnlyAfterCommit() {
        when(repository.findEnabled(7)).thenReturn(Optional.of(doctor));
        when(repository.updateProfile(7,"演示医生",null,"复诊咨询","")).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.updateProfile(7," 演示医生 ",null," 复诊咨询 ","");
            verify(repository).updateProfile(7,"演示医生",null,"复诊咨询","");
            verify(cache,never()).evict(anyLong());
            TransactionSynchronizationManager.getSynchronizations().forEach(s->s.afterCommit());
            verify(cache).evict(7);
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
    @Test void profileRejectsOversizedBiographyBeforeReadingOrWriting() {
        assertThatThrownBy(()->service.updateProfile(7,"姓名","职称","擅长","字".repeat(4001)))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(repository,cache);
    }
    @Test void directoryUsesTrimmedSearchWithoutTouchingPerDoctorCache() {
        when(repository.directory(null,"林")).thenReturn(java.util.List.of(doctor));
        assertThat(service.directory(null," 林 ")).containsExactly(doctor);
        verifyNoInteractions(cache);
    }
}
