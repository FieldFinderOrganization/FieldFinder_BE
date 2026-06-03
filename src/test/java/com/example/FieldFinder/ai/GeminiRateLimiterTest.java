package com.example.FieldFinder.ai;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.*;

class GeminiRateLimiterTest {

    @Test
    void acquireRetriesUntilDistributedPermitIsGranted() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(
                "ai:gemini:rate:permit",
                "1",
                GeminiRateLimiter.MIN_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        )).thenReturn(false, true);

        GeminiRateLimiter limiter = new GeminiRateLimiter(redisTemplate);
        limiter.acquire();

        verify(valueOps, times(2)).setIfAbsent(
                "ai:gemini:rate:permit",
                "1",
                GeminiRateLimiter.MIN_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
    }
}
