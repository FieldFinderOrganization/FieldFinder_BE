package com.example.FieldFinder.ai;

import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiChatSessionContextStoreTest {

    private StringRedisTemplate redisTemplate;
    private HashOperations<String, Object, Object> hashOps;
    private AiChatSessionContextStore store;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        hashOps = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        store = new AiChatSessionContextStore(redisTemplate, new ObjectMapper());
    }

    @Test
    void writesAndReadsProductWithSlidingTtl() throws Exception {
        ProductResponseDTO product = ProductResponseDTO.builder()
                .id(10L)
                .name("Puma Future")
                .brand("Puma")
                .build();
        String raw = new ObjectMapper().writeValueAsString(product);
        when(hashOps.get("ai:chat:session:s1", "lastProduct")).thenReturn(raw);

        store.setLastProduct("s1", product);
        ProductResponseDTO result = store.getLastProduct("s1");

        assertEquals(10L, result.getId());
        assertEquals("Puma", result.getBrand());
        verify(hashOps).put(eq("ai:chat:session:s1"), eq("lastProduct"), anyString());
        verify(redisTemplate, atLeast(2)).expire("ai:chat:session:s1", 2, TimeUnit.HOURS);
    }

    @Test
    void malformedJsonReturnsNullAndDeletesField() {
        when(hashOps.get("ai:chat:session:s1", "lastProduct")).thenReturn("{bad-json");

        ProductResponseDTO result = store.getLastProduct("s1");

        assertNull(result);
        verify(hashOps).delete("ai:chat:session:s1", "lastProduct");
    }
}
