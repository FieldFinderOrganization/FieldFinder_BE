package com.example.FieldFinder.ai;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
public class GeminiRateLimiter {

    static final long MIN_INTERVAL_MS = 5_000L;
    private static final String PERMIT_KEY = "ai:gemini:rate:permit";

    private final StringRedisTemplate redisTemplate;
    private final AtomicLong fallbackNextAvailableTime = new AtomicLong(0);

    public void acquire() throws InterruptedException {
        while (true) {
            try {
                Boolean acquired = redisTemplate.opsForValue()
                        .setIfAbsent(PERMIT_KEY, "1", MIN_INTERVAL_MS, TimeUnit.MILLISECONDS);
                if (Boolean.TRUE.equals(acquired)) {
                    return;
                }
            } catch (Exception redisUnavailable) {
                acquireLocalFallback();
                return;
            }
            Thread.sleep(100L);
        }
    }

    private void acquireLocalFallback() throws InterruptedException {
        long now = System.currentTimeMillis();
        long myTurn;
        while (true) {
            long currentNext = fallbackNextAvailableTime.get();
            myTurn = Math.max(now, currentNext);
            if (fallbackNextAvailableTime.compareAndSet(currentNext, myTurn + MIN_INTERVAL_MS)) {
                break;
            }
        }
        long waitMs = myTurn - now;
        if (waitMs > 0) {
            Thread.sleep(waitMs);
        }
    }
}
