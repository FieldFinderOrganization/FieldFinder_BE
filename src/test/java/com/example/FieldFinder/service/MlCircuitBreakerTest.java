package com.example.FieldFinder.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MlCircuitBreakerTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private MlCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        breaker = new MlCircuitBreaker(redisTemplate);
    }

    @Test
    void opensAfterFailureThreshold() {
        when(valueOps.increment("ml:circuit:failures")).thenReturn(1L, 2L, 3L, 4L, 5L);

        for (int i = 0; i < 5; i++) {
            breaker.recordFailure();
        }

        verify(valueOps).set(eq("ml:circuit:openUntil"), anyString(), eq(60_000L), eq(TimeUnit.MILLISECONDS));
        verify(redisTemplate, times(5)).expire("ml:circuit:failures", 60_000L, TimeUnit.MILLISECONDS);
    }

    @Test
    void halfOpenAllowsSingleProbeAfterOpenWindow() {
        long expiredOpenUntil = Instant.now().minusMillis(1_000).toEpochMilli();
        when(valueOps.get("ml:circuit:openUntil")).thenReturn(Long.toString(expiredOpenUntil));
        when(valueOps.setIfAbsent("ml:circuit:halfOpenProbe", "1", 60_000L, TimeUnit.MILLISECONDS))
                .thenReturn(true, false);

        assertTrue(breaker.allowRequest());
        assertFalse(breaker.allowRequest());
    }

    @Test
    void halfOpenProbeFailureReopensImmediately() {
        when(redisTemplate.hasKey("ml:circuit:halfOpenProbe")).thenReturn(true);
        when(valueOps.increment("ml:circuit:failures")).thenReturn(1L);

        breaker.recordFailure();

        verify(valueOps).set(eq("ml:circuit:openUntil"), anyString(), eq(60_000L), eq(TimeUnit.MILLISECONDS));
        verify(redisTemplate).delete("ml:circuit:halfOpenProbe");
    }

    @Test
    void successClosesCircuit() {
        breaker.recordSuccess();

        verify(redisTemplate).delete("ml:circuit:failures");
        verify(redisTemplate).delete("ml:circuit:openUntil");
        verify(redisTemplate).delete("ml:circuit:halfOpenProbe");
    }
}
