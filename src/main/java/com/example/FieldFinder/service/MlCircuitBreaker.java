package com.example.FieldFinder.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
public class MlCircuitBreaker {

    private static final int FAILURE_THRESHOLD = 5;
    private static final long OPEN_DURATION_MS = 60_000L;
    private static final String FAILURES_KEY = "ml:circuit:failures";
    private static final String OPEN_UNTIL_KEY = "ml:circuit:openUntil";
    private static final String PROBE_KEY = "ml:circuit:halfOpenProbe";

    private final StringRedisTemplate redisTemplate;
    private final AtomicInteger fallbackFailures = new AtomicInteger(0);
    private final AtomicLong fallbackOpenUntil = new AtomicLong(0);
    private final AtomicLong fallbackProbeUntil = new AtomicLong(0);

    public boolean allowRequest() {
        try {
            long openUntil = parseLong(redisTemplate.opsForValue().get(OPEN_UNTIL_KEY), 0L);
            long now = Instant.now().toEpochMilli();
            if (openUntil <= 0 || now < openUntil) {
                return openUntil <= 0;
            }
            Boolean probeAcquired = redisTemplate.opsForValue()
                    .setIfAbsent(PROBE_KEY, "1", OPEN_DURATION_MS, TimeUnit.MILLISECONDS);
            return Boolean.TRUE.equals(probeAcquired);
        } catch (Exception redisUnavailable) {
            return allowRequestFallback();
        }
    }

    public void recordSuccess() {
        try {
            redisTemplate.delete(FAILURES_KEY);
            redisTemplate.delete(OPEN_UNTIL_KEY);
            redisTemplate.delete(PROBE_KEY);
        } catch (Exception redisUnavailable) {
            fallbackFailures.set(0);
            fallbackOpenUntil.set(0);
            fallbackProbeUntil.set(0);
        }
    }

    public void recordFailure() {
        try {
            boolean probeActive = Boolean.TRUE.equals(redisTemplate.hasKey(PROBE_KEY));
            Long failures = redisTemplate.opsForValue().increment(FAILURES_KEY);
            redisTemplate.expire(FAILURES_KEY, OPEN_DURATION_MS, TimeUnit.MILLISECONDS);
            if (probeActive || (failures != null && failures >= FAILURE_THRESHOLD)) {
                open();
            }
        } catch (Exception redisUnavailable) {
            long now = Instant.now().toEpochMilli();
            if (fallbackProbeUntil.get() > now) {
                fallbackFailures.set(FAILURE_THRESHOLD);
                fallbackOpenUntil.set(now + OPEN_DURATION_MS);
                fallbackProbeUntil.set(0);
                return;
            }
            int failures = fallbackFailures.incrementAndGet();
            if (failures >= FAILURE_THRESHOLD) {
                fallbackOpenUntil.set(now + OPEN_DURATION_MS);
                fallbackProbeUntil.set(0);
            }
        }
    }

    private void open() {
        long until = Instant.now().toEpochMilli() + OPEN_DURATION_MS;
        redisTemplate.opsForValue().set(OPEN_UNTIL_KEY, Long.toString(until), OPEN_DURATION_MS, TimeUnit.MILLISECONDS);
        redisTemplate.delete(PROBE_KEY);
    }

    private boolean allowRequestFallback() {
        long openUntil = fallbackOpenUntil.get();
        long now = Instant.now().toEpochMilli();
        if (openUntil <= 0) return true;
        if (now < openUntil) return false;
        long probeUntil = fallbackProbeUntil.get();
        if (probeUntil > now) return false;
        return fallbackProbeUntil.compareAndSet(probeUntil, now + OPEN_DURATION_MS);
    }

    private long parseLong(String raw, long fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
