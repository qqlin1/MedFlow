package com.qqlin.medflow.organization.detail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class DoctorDetailCache {
    public static final String NULL_VALUE = "__NULL__";
    private static final Logger log = LoggerFactory.getLogger(DoctorDetailCache.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final boolean enabled;
    private final Duration ttl, nullTtl, failureBackoff;
    private final AtomicLong retryAfter = new AtomicLong();
    private final AtomicLong unsafeUntil = new AtomicLong();

    public DoctorDetailCache(StringRedisTemplate redis, ObjectMapper json,
            @Value("${medflow.doctor-cache.enabled:true}") boolean enabled,
            @Value("${medflow.doctor-cache.ttl:PT5M}") Duration ttl,
            @Value("${medflow.doctor-cache.null-ttl:PT30S}") Duration nullTtl,
            @Value("${medflow.doctor-cache.failure-backoff:PT5S}") Duration failureBackoff) {
        this.redis=redis; this.json=json; this.enabled=enabled;
        this.ttl=bounded(ttl); this.nullTtl=bounded(nullTtl); this.failureBackoff=bounded(failureBackoff);
    }
    private static Duration bounded(Duration duration) {
        if (duration == null || duration.compareTo(Duration.ofMillis(1)) < 0 || duration.compareTo(Duration.ofHours(1)) > 0)
            throw new IllegalArgumentException("Doctor cache durations must be 1ms to 1h");
        return duration;
    }
    public static String key(long id) { return "medflow:doctor:detail:v1:" + id; }
    // Outer empty = MISS/unavailable; inner empty = HIT of an absent doctor.
    public Optional<Optional<DoctorDetail>> read(long id) {
        if (!available()) return Optional.empty();
        try {
            String value=redis.opsForValue().get(key(id));
            if (value == null) return Optional.empty();
            if (NULL_VALUE.equals(value)) return Optional.of(Optional.empty());
            DoctorDetail detail=json.readValue(value, DoctorDetail.class);
            if (detail == null || detail.id()!=id || detail.departmentId()<=0 || detail.name()==null || detail.departmentName()==null)
                throw new IllegalArgumentException("Invalid doctor cache payload");
            return Optional.of(Optional.of(detail));
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            log.warn("doctor_cache_invalid_payload doctorId={}; reload MySQL", id);
            evict(id);
            return Optional.empty();
        } catch (DataAccessException ex) {
            failed("read", id, ex);
            return Optional.empty();
        }
    }
    public void put(long id, Optional<DoctorDetail> detail) {
        if (!available()) return;
        try {
            String value=detail.isPresent() ? json.writeValueAsString(detail.get()) : NULL_VALUE;
            redis.opsForValue().set(key(id),value,detail.isPresent() ? ttl : nullTtl);
        } catch (JsonProcessingException ex) {
            log.warn("doctor_cache_encode_failed doctorId={}",id);
        } catch (DataAccessException ex) { failed("fill",id,ex); }
    }
    public void evict(long id) {
        if (!enabled) return;
        // A committed update always attempts DEL, even while reads are backed off.
        try { redis.delete(key(id)); }
        catch (DataAccessException ex) {
            // After a failed delete, this node must not serve a possibly stale key on reconnect.
            // Keep reads on MySQL until the longest existing TTL has elapsed.
            unsafeUntil.accumulateAndGet(System.currentTimeMillis()+Math.max(ttl.toMillis(),nullTtl.toMillis()),Math::max);
            failed("invalidate",id,ex);
        }
    }
    private boolean available() {
        long now=System.currentTimeMillis();
        return enabled && now>=retryAfter.get() && now>=unsafeUntil.get();
    }
    private void failed(String operation,long id,DataAccessException ex) {
        long now=System.currentTimeMillis();
        long previous=retryAfter.getAndSet(now+failureBackoff.toMillis());
        if (previous<=now) log.warn("doctor_cache_unavailable operation={} doctorId={} backoffMs={} cause={}; fallback MySQL",
                operation,id,failureBackoff.toMillis(),ex.getClass().getSimpleName());
    }
}
