package com.example.FieldFinder.service.log;

import com.example.FieldFinder.Enum.Gender;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.entity.log.InteractionLog;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.OpenWeatherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LogPublisherServiceTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private OpenWeatherService openWeatherService;

    @Mock
    private UserRepository userRepository;

    private LogPublisherService logPublisherService;

    @BeforeEach
    void setUp() {
        logPublisherService = new LogPublisherService(rabbitTemplate, openWeatherService, userRepository);
    }

    @Test
    void testParseUserAgent_Android() {
        String ua = "Mozilla/5.0 (Linux; Android 13; SM-G991B Build/TP1A.220624.014; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/116.0.0.0 Mobile Safari/537.36";
        Map<String, String> result = LogPublisherService.parseUserAgent(ua);

        assertEquals("Android", result.get("os"));
        assertEquals("13", result.get("os_version"));
        assertEquals("SM-G991B", result.get("device_model"));
    }

    @Test
    void testParseUserAgent_iOS() {
        String ua = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4.1 Mobile/15E148 Safari/604.1";
        Map<String, String> result = LogPublisherService.parseUserAgent(ua);

        assertEquals("iOS", result.get("os"));
        assertEquals("17.4.1", result.get("os_version"));
        assertEquals("iPhone", result.get("device_model"));
    }

    @Test
    void testPublishEventEnriched_WithDemographicsAndLocation() {
        UUID userId = UUID.randomUUID();
        User user = User.builder()
                .userId(userId)
                .gender(Gender.MALE)
                .dateOfBirth(LocalDate.now().minusYears(25))
                .latitude(10.762622)
                .longitude(106.660172)
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(openWeatherService.getCachedDefaultWeather()).thenReturn("Sunny, 30°C");

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("item_name", "Test Item");

        logPublisherService.publishEventEnriched(
                userId.toString(), "sess-123", "VIEW_PITCH",
                "pitch-1", "PITCH", metadata,
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                null, null
        );

        ArgumentCaptor<InteractionLog> captor = ArgumentCaptor.forClass(InteractionLog.class);
        verify(rabbitTemplate).convertAndSend(eq("logging_exchange"), eq("log.routing.key"), captor.capture());

        InteractionLog log = captor.getValue();
        assertEquals("VIEW_PITCH", log.getEventType());
        assertEquals(userId.toString(), log.getUserId());

        Map<String, Object> context = log.getContext();
        assertEquals(25, context.get("user_age_at_event"));
        assertEquals("MALE", context.get("user_gender"));
        assertEquals("Windows", context.get("os"));

        Map<String, Double> loc = (Map<String, Double>) context.get("location");
        assertEquals(10.762622, loc.get("lat"));
    }

    @Test
    void testPublishEventEnriched_WithFrontendLocationOverride() {
        UUID userId = UUID.randomUUID();
        when(openWeatherService.getCachedDefaultWeather()).thenReturn("Rainy");

        logPublisherService.publishEventEnriched(
                userId.toString(), "sess-123", "SEARCH",
                null, null, null,
                "AI_Chatbot",
                21.028511, 105.804817 // Hanoi
        );

        ArgumentCaptor<InteractionLog> captor = ArgumentCaptor.forClass(InteractionLog.class);
        verify(rabbitTemplate).convertAndSend(any(), any(), captor.capture());

        Map<String, Double> loc = (Map<String, Double>) captor.getValue().getContext().get("location");
        assertEquals(21.028511, loc.get("lat"));
        assertEquals(105.804817, loc.get("lng"));
        assertEquals("Server", captor.getValue().getContext().get("os"));
    }
}