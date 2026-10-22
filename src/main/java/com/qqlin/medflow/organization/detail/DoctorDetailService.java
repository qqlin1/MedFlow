package com.qqlin.medflow.organization.detail;

import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class DoctorDetailService {
    private static final Logger log=LoggerFactory.getLogger(DoctorDetailService.class);
    private final DoctorDetailRepository repository;
    private final DoctorDetailCache cache;
    public DoctorDetailService(DoctorDetailRepository repository,DoctorDetailCache cache) {
        this.repository=repository; this.cache=cache;
    }
    public DoctorDetail get(long id) {
        positive(id);
        var cached=cache.read(id);
        if (cached.isPresent()) {
            log.debug("doctor_detail source=redis doctorId={}",id);
            return cached.get().orElseThrow(() -> new BusinessException(ErrorCode.DOCTOR_NOT_FOUND));
        }
        log.debug("doctor_detail source=mysql doctorId={}",id);
        var detail=repository.findEnabled(id); // DB failures propagate; never cache an outage as not-found.
        cache.put(id,detail);
        return detail.orElseThrow(() -> new BusinessException(ErrorCode.DOCTOR_NOT_FOUND));
    }
    @Transactional
    public DoctorDetail updateName(long id,String name) {
        positive(id);
        if (name==null || name.isBlank() || name.trim().length()>64) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        repository.findEnabled(id).orElseThrow(() -> new BusinessException(ErrorCode.DOCTOR_NOT_FOUND));
        if (repository.updateName(id,name.trim())!=1) throw new BusinessException(ErrorCode.DOCTOR_NOT_FOUND);
        var updated=repository.findEnabled(id).orElseThrow(() -> new BusinessException(ErrorCode.DOCTOR_NOT_FOUND));
        // Register inside the proxied transaction. Rollback never triggers invalidation.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { cache.evict(id); }
        });
        return updated;
    }

    @Transactional
    public DoctorDetail updateProfile(long id,String name,String title,String specialty,String introduction) {
        positive(id);
        if(name==null || name.isBlank()) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        name=field(name,64);title=field(title,64);specialty=field(specialty,1000);introduction=field(introduction,4000);
        repository.findEnabled(id).orElseThrow(()->new BusinessException(ErrorCode.DOCTOR_NOT_FOUND));
        if(repository.updateProfile(id,name,title,specialty,introduction)!=1)
            throw new BusinessException(ErrorCode.DOCTOR_NOT_FOUND);
        var updated=repository.findEnabled(id).orElseThrow(()->new BusinessException(ErrorCode.DOCTOR_NOT_FOUND));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { cache.evict(id); }
        });
        return updated;
    }

    public java.util.List<DoctorDetail> directory(Long departmentId,String query) {
        if(departmentId!=null) positive(departmentId);
        return repository.directory(departmentId,query==null?"":field(query,64));
    }

    private static String field(String value,int max) {
        if(value==null) return null; // Missing optional fields preserve the stored value.
        value=value.strip();
        if(value.length()>max) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return value;
    }
    private static void positive(long id) {
        if (id<=0) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
}
