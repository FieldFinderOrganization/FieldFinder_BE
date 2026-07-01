package com.example.FieldFinder.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
public class RedisConfig {

    /**
     * GenericJackson2JsonRedisSerializer() mặc định KHÔNG có JavaTimeModule, nên field
     * LocalDate/LocalDateTime (vd Pitch.deactivationDate) ném InvalidDefinitionException khi
     * cache ghi → cả request lỗi 400. Truyền ObjectMapper riêng qua .objectMapper() bắt buộc
     * phải bật lại .defaultTyping(true) — builder chỉ tự áp default typing (PROPERTY/"@class")
     * khi dùng ObjectMapper nội bộ của nó, không tự áp cho mapper custom.
     */
    private static GenericJackson2JsonRedisSerializer redisJsonSerializer() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return GenericJackson2JsonRedisSerializer.builder()
                .objectMapper(mapper)
                .defaultTyping(true)
                .build();
    }

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(redisJsonSerializer()));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base)
                .withCacheConfiguration("products_category", base.entryTtl(Duration.ofHours(24)))
                .withCacheConfiguration("product_detail",    base.entryTtl(Duration.ofHours(6)))
                .withCacheConfiguration("pitches_list",      base.entryTtl(Duration.ofHours(24)))
                .withCacheConfiguration("pitch_detail",      base.entryTtl(Duration.ofHours(12)))
                .withCacheConfiguration("ai_catalog",        base.entryTtl(Duration.ofHours(24)))
                .withCacheConfiguration("top_selling",       base.entryTtl(Duration.ofHours(12)))
                .build();
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        template.setValueSerializer(redisJsonSerializer());
        template.setHashValueSerializer(redisJsonSerializer());

        template.afterPropertiesSet();
        return template;
    }
}
