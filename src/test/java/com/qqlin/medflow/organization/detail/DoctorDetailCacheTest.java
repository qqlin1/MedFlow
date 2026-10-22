package com.qqlin.medflow.organization.detail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.time.Duration;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DoctorDetailCacheTest {
    final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") final ValueOperations<String,String> values=mock(ValueOperations.class);
    final ObjectMapper json=new ObjectMapper();
    final DoctorDetail doctor=new DoctorDetail(7,2,"演示医生","全科");
    DoctorDetailCache cache(boolean enabled) {
        when(redis.opsForValue()).thenReturn(values);
        return new DoctorDetailCache(redis,json,enabled,Duration.ofMinutes(5),Duration.ofSeconds(30),Duration.ofSeconds(5));
    }
    @Test void serializesJsonWithPositiveTtlAndReadsIt() throws Exception {
        var cache=cache(true); cache.put(7,Optional.of(doctor));
        verify(values).set(DoctorDetailCache.key(7),json.writeValueAsString(doctor),Duration.ofMinutes(5));
        when(values.get(DoctorDetailCache.key(7))).thenReturn(json.writeValueAsString(doctor));
        assertThat(cache.read(7)).contains(Optional.of(doctor));
    }
    @Test void nullMarkerHasShortTtlAndDiffersFromMiss() {
        var cache=cache(true); cache.put(7,Optional.empty());
        verify(values).set(DoctorDetailCache.key(7),DoctorDetailCache.NULL_VALUE,Duration.ofSeconds(30));
        when(values.get(DoctorDetailCache.key(7))).thenReturn(DoctorDetailCache.NULL_VALUE);
        assertThat(cache.read(7)).contains(Optional.empty());
        when(values.get(DoctorDetailCache.key(7))).thenReturn(null); assertThat(cache.read(7)).isEmpty();
    }
    @Test void redisReadFailureBacksOffAndSkipsFill() {
        var cache=cache(true); when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("offline"));
        assertThat(cache.read(7)).isEmpty(); assertThat(cache.read(7)).isEmpty(); cache.put(7,Optional.of(doctor));
        verify(values,times(1)).get(anyString()); verify(values,never()).set(anyString(),anyString(),any(Duration.class));
    }
    @Test void failedFillIsOptionalAndCannotBreakReadResult() {
        var cache=cache(true); doThrow(new RedisConnectionFailureException("offline")).when(values).set(anyString(),anyString(),any(Duration.class));
        assertThatCode(() -> cache.put(7,Optional.of(doctor))).doesNotThrowAnyException();
    }
    @Test void malformedOrWrongIdPayloadIsEvictedAndTreatedAsMiss() {
        var cache=cache(true); when(values.get(DoctorDetailCache.key(7))).thenReturn("garbage");
        assertThat(cache.read(7)).isEmpty(); verify(redis).delete(DoctorDetailCache.key(7));
    }
    @Test void failedInvalidationSuppressesStaleReadsOnThisNode() {
        var cache=cache(true); doThrow(new RedisConnectionFailureException("offline")).when(redis).delete(anyString());
        assertThatCode(() -> cache.evict(7)).doesNotThrowAnyException(); assertThat(cache.read(7)).isEmpty(); verify(values,never()).get(anyString());
    }
    @Test void featureSwitchDisablesAllRedisOperations() {
        var cache=cache(false); cache.read(7); cache.put(7,Optional.of(doctor)); cache.evict(7);
        verifyNoInteractions(values); verify(redis,never()).delete(anyString());
    }
    @Test void ttlMustBePositiveAndBounded() {
        assertThatThrownBy(() -> new DoctorDetailCache(redis,json,true,Duration.ZERO,Duration.ofSeconds(1),Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
