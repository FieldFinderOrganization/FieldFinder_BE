package com.example.FieldFinder.entity.log;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "user_interaction_logs")
public class InteractionLog {
    @Id
    private String logId;

    private String userId;
    private String sessionId;

    private Instant timestamp;
    private int dayOfWeek;       // 1 = Monday, 7 = Sunday
    private int hourOfDay;       // 5 - 23
    private boolean isWeekend;

    private Map<String, Object> context; // Chứa Weather, User-Agent, Device...

    private String eventType;    // VIEW_PITCH, SEARCH, CHAT_INTENT, BOOKING...
    private String itemId;       // PitchId hoặc ProductId
    private String itemType;     // PITCH, PRODUCT

    private Map<String, Object> eventMetadata;
}