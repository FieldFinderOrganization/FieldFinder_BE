package com.example.FieldFinder.service.log;

import com.example.FieldFinder.config.RabbitMQLogConfig;
import com.example.FieldFinder.entity.log.InteractionLog;
import com.example.FieldFinder.repository.InteractionLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LogRabbitListener {

    private final InteractionLogRepository logRepository;

    @RabbitListener(queues = RabbitMQLogConfig.QUEUE_LOG)
    public void consumeLogEvent(InteractionLog interactionLog) {
        try {
            logRepository.save(interactionLog);
            log.info("✅ Đã ghi Log thành công: Event={}, User={}", interactionLog.getEventType(), interactionLog.getUserId());
        } catch (Exception e) {
            log.error("❌ Lỗi khi ghi log vào MongoDB: ", e);
        }
    }
}