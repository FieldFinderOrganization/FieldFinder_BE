package com.example.FieldFinder.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class PitchRedisLockService {

    private final StringRedisTemplate redisTemplate;

    // Định dạng Key Redis: lock:pitch:{pitchId}:date:{date}:slot:{slotId}
    private String getLockKey(UUID pitchId, LocalDate date, Integer slotId) {
        return String.format("lock:pitch:%s:date:%s:slot:%d", pitchId, date.toString(), slotId);
    }

    public boolean lockSlots(UUID pitchId, LocalDate date, List<Integer> slotIds, String userId) {
        long LOCK_TIMEOUT_MINUTES = 10;

        for (int i = 0; i < slotIds.size(); i++) {
            String key = getLockKey(pitchId, date, slotIds.get(i));

            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, userId, LOCK_TIMEOUT_MINUTES, TimeUnit.MINUTES);

            if (Boolean.FALSE.equals(acquired)) {
                for (int j = 0; j < i; j++) {
                    unlockSlot(pitchId, date, slotIds.get(j), userId);
                }
                return false;
            }
        }
        return true;
    }

    public void unlockSlot(UUID pitchId, LocalDate date, Integer slotId, String userId) {
        String key = getLockKey(pitchId, date, slotId);
        String currentLockOwner = redisTemplate.opsForValue().get(key);

        if (userId.equals(currentLockOwner)) {
            redisTemplate.delete(key);
        }
    }
}