package com.example.FieldFinder.service.log;

import com.example.FieldFinder.config.RabbitMQLogConfig;
import com.example.FieldFinder.entity.log.InteractionLog;
import com.example.FieldFinder.service.OpenWeatherService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LogPublisherService {

    private final RabbitTemplate rabbitTemplate;
    private final OpenWeatherService openWeatherService;

    @Async
    public void publishEvent(String userId, String sessionId, String eventType,
                             String itemId, String itemType, Map<String, Object> metadata, String userAgent) {

        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"));

        Map<String, Object> context = new HashMap<>();
        context.put("device_info", userAgent);

        // Gọi hàm dùng Cache (chỉ 15 phút mới gọi API 1 lần)
        context.put("weather", openWeatherService.getCachedDefaultWeather());

        InteractionLog log = InteractionLog.builder()
                .logId(UUID.randomUUID().toString())
                .userId(userId)
                .sessionId(sessionId)
                .timestamp(Instant.now())
                .dayOfWeek(now.getDayOfWeek().getValue())
                .hourOfDay(now.getHour())
                .isWeekend(now.getDayOfWeek().getValue() >= 6)
                .context(context)
                .eventType(eventType)
                .itemId(itemId)
                .itemType(itemType)
                .eventMetadata(metadata != null ? metadata : new HashMap<>()) // Nhận metadata từ AOP
                .build();

        rabbitTemplate.convertAndSend(RabbitMQLogConfig.EXCHANGE_LOG, RabbitMQLogConfig.ROUTING_KEY_LOG, log);
    }
}