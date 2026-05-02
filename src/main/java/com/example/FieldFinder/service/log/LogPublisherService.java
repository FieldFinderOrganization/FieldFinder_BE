package com.example.FieldFinder.service.log;

import com.example.FieldFinder.config.RabbitMQLogConfig;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.entity.log.InteractionLog;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.OpenWeatherService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class LogPublisherService {

    private final RabbitTemplate rabbitTemplate;
    private final OpenWeatherService openWeatherService;
    private final UserRepository userRepository;

    // ── Backward-compatible overload (giữ nguyên signature cũ) ──
    @Async
    public void publishEvent(String userId, String sessionId, String eventType,
                             String itemId, String itemType, Map<String, Object> metadata, String userAgent) {
        publishEventEnriched(userId, sessionId, eventType, itemId, itemType, metadata, userAgent, null, null);
    }

    // ── Enriched overload — thêm location ──
    @Async
    public void publishEventEnriched(String userId, String sessionId, String eventType,
                                     String itemId, String itemType, Map<String, Object> metadata,
                                     String userAgent, Double lat, Double lng) {

        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"));

        Map<String, Object> context = new HashMap<>();
        context.put("device_info", userAgent);

        // Parse User-Agent thành device_model, os, os_version
        Map<String, String> parsedUA = parseUserAgent(userAgent);
        context.put("device_model", parsedUA.get("device_model"));
        context.put("os", parsedUA.get("os"));
        context.put("os_version", parsedUA.get("os_version"));

        context.put("weather", openWeatherService.getCachedDefaultWeather());

        // Location nếu frontend gửi
        if (lat != null && lng != null) {
            Map<String, Double> location = new HashMap<>();
            location.put("lat", lat);
            location.put("lng", lng);
            context.put("location", location);
        }

        // Snapshot user demographics tại thời điểm event
        snapshotUserDemographics(userId, context);

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

    // ── User demographics snapshot ──
    private void snapshotUserDemographics(String userId, Map<String, Object> context) {
        if (userId == null || userId.isBlank()) return;
        try {
            UUID uid = UUID.fromString(userId);
            userRepository.findById(uid).ifPresent(user -> {
                if (user.getDateOfBirth() != null) {
                    int age = Period.between(user.getDateOfBirth(), LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))).getYears();
                    context.put("user_age_at_event", age);
                }
                if (user.getGender() != null) {
                    context.put("user_gender", user.getGender().name());
                }
                if (user.getLatitude() != null && user.getLongitude() != null
                        && !context.containsKey("location")) {
                    // Fallback: dùng location profile nếu frontend không gửi
                    Map<String, Double> location = new HashMap<>();
                    location.put("lat", user.getLatitude());
                    location.put("lng", user.getLongitude());
                    context.put("location", location);
                }
            });
        } catch (IllegalArgumentException ignored) {
            // userId không phải UUID hợp lệ (VD: guest), bỏ qua
        }
    }

    // ── User-Agent parser (regex, không cần thêm dependency) ──
    static Map<String, String> parseUserAgent(String userAgent) {
        Map<String, String> result = new HashMap<>();
        result.put("device_model", "Unknown");
        result.put("os", "Unknown");
        result.put("os_version", "");

        if (userAgent == null || userAgent.isBlank() || "AI_Chatbot".equals(userAgent)) {
            result.put("os", "AI_Chatbot".equals(userAgent) ? "Server" : "Unknown");
            return result;
        }

        // Android: "Linux; Android 13; SM-G991B Build/TP1A.220624.014; wv"
        Pattern androidPattern = Pattern.compile("Android\\s+([\\d.]+)(?:;\\s*([^;)]+))?");
        Matcher m = androidPattern.matcher(userAgent);
        if (m.find()) {
            result.put("os", "Android");
            result.put("os_version", m.group(1));
            if (m.group(2) != null) {
                String model = m.group(2).trim().split(" Build/")[0];
                result.put("device_model", model);
            }
            return result;
        }

        // iPhone: "iPhone; CPU iPhone OS 17_4 like Mac OS X"
        Pattern iosPattern = Pattern.compile("(iPhone|iPad).*?OS\\s+([\\d_]+)");
        m = iosPattern.matcher(userAgent);
        if (m.find()) {
            result.put("os", "iOS");
            result.put("os_version", m.group(2).replace('_', '.'));
            result.put("device_model", m.group(1));
            return result;
        }

        // Windows: "Windows NT 10.0"
        Pattern winPattern = Pattern.compile("Windows NT\\s+([\\d.]+)");
        m = winPattern.matcher(userAgent);
        if (m.find()) {
            result.put("os", "Windows");
            result.put("os_version", m.group(1));
            result.put("device_model", "PC");
            return result;
        }

        // Mac: "Macintosh; Intel Mac OS X 10_15_7"
        Pattern macPattern = Pattern.compile("Macintosh.*?OS X\\s+([\\d_]+)");
        m = macPattern.matcher(userAgent);
        if (m.find()) {
            result.put("os", "macOS");
            result.put("os_version", m.group(1).replace('_', '.'));
            result.put("device_model", "Mac");
            return result;
        }

        // Linux generic
        if (userAgent.contains("Linux")) {
            result.put("os", "Linux");
            result.put("device_model", "PC");
        }

        return result;
    }
}