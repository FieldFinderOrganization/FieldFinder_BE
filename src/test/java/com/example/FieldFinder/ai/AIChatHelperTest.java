package com.example.FieldFinder.ai;

import com.example.FieldFinder.Enum.PitchEnvironment;
import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.example.FieldFinder.service.CategoryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AIChatHelperTest {

    @Test
    void containsQueryToken_MatchesStandaloneBrandOnly() {
        assertTrue(AIChat.containsQueryToken("tôi muốn puma size 42", "puma"));
        assertTrue(AIChat.containsQueryToken("balo New Balance màu đen", "new balance"));
        assertFalse(AIChat.containsQueryToken("spumante không phải brand", "puma"));
    }

    @Test
    void suggestEnvironmentByWeather_OvercastSuggestsIndoor() {
        assertEquals(PitchEnvironment.INDOOR,
                AIChat.suggestEnvironmentByWeather("Mây đen u ám, nhiệt độ khoảng 29.5°C."));
        assertEquals(PitchEnvironment.INDOOR,
                AIChat.suggestEnvironmentByWeather("Nhiều mây, nhiệt độ khoảng 28°C."));
    }

    @Test
    void suggestEnvironmentByWeather_ClearSuggestsOutdoor() {
        assertEquals(PitchEnvironment.OUTDOOR,
                AIChat.suggestEnvironmentByWeather("Trời quang, nhiệt độ khoảng 32°C."));
        assertEquals(PitchEnvironment.OUTDOOR,
                AIChat.suggestEnvironmentByWeather("Ít mây, nhiệt độ khoảng 30°C."));
    }

    @Test
    void suggestEnvironmentByWeather_RainSuggestsIndoor() {
        assertEquals(PitchEnvironment.INDOOR,
                AIChat.suggestEnvironmentByWeather("Mưa nhẹ, nhiệt độ khoảng 25°C."));
    }

    @Test
    void explainEnvironmentChoice_DescribesOvercastReason() {
        String reason = AIChat.explainEnvironmentChoice(
                "Mây đen u ám, nhiệt độ khoảng 29.5°C.", PitchEnvironment.INDOOR);
        assertTrue(reason.contains("trong nhà") || reason.contains("mây"));
    }

    @Test
    void strictTypeFilter_ReturnsEmptyWhenAllProductsWrongType() {
        ProductResponseDTO wrong = ProductResponseDTO.builder().id(1L).name("Quần thể thao").build();
        CategoryService categoryService = mock(CategoryService.class);
        when(categoryService.productMatchesType(wrong, "SHOES")).thenReturn(false);

        AIChat.StrictTypeFilterResult result = AIChat.strictTypeFilter(
                List.of(wrong),
                List.of(0.9),
                "SHOES",
                categoryService
        );

        assertTrue(result.products().isEmpty());
        assertTrue(result.scores().isEmpty());
    }
}
