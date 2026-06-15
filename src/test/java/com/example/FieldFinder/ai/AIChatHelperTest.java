package com.example.FieldFinder.ai;

import com.example.FieldFinder.Enum.PitchEnvironment;
import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.example.FieldFinder.ai.match.AiProductMatch;
import com.example.FieldFinder.service.CategoryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AIChatHelperTest {

    @Test
    void containsQueryToken_MatchesStandaloneBrandOnly() {
        assertTrue(AiProductMatch.containsQueryToken("tôi muốn puma size 42", "puma"));
        assertTrue(AiProductMatch.containsQueryToken("balo New Balance màu đen", "new balance"));
        assertFalse(AiProductMatch.containsQueryToken("spumante không phải brand", "puma"));
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
    void detectQuerySize_PrefersGeminiParsedValue() {
        assertEquals("39", AiProductMatch.detectQuerySize("39", "tìm giày nike size 40"));
    }

    @Test
    void detectQuerySize_FallbackRegexOnUserInput() {
        assertEquals("39", AiProductMatch.detectQuerySize(null, "tìm giày nike nam màu đen size 39"));
        assertEquals("39.5", AiProductMatch.detectQuerySize(null, "giày Size 39,5 còn không"));
        assertEquals("40", AiProductMatch.detectQuerySize(null, "cỡ 40 có hàng không"));
        assertEquals("XL", AiProductMatch.detectQuerySize(null, "áo sz xl màu trắng"));
        assertEquals("2XL", AiProductMatch.detectQuerySize(null, "áo size 2XL"));
    }

    @Test
    void detectQuerySize_NullWhenAbsentOrAmbiguous() {
        assertNull(AiProductMatch.detectQuerySize(null, "tìm giày nike màu đen"));
        assertNull(AiProductMatch.detectQuerySize(null, "sân 7 còn trống không"));      // không có keyword size
        assertNull(AiProductMatch.detectQuerySize(null, "giày tầm 39k thôi"));          // giá, không phải size
        assertNull(AiProductMatch.detectQuerySize(null, "size 39k là gì"));             // 39k không phải size
        assertNull(AiProductMatch.detectQuerySize("null", null));                       // Gemini trả chuỗi "null"
    }

    @Test
    void detectQueryGender_TagsFirstThenUserInput() {
        assertEquals("MEN", AiProductMatch.detectQueryGender(List.of("nike", "nam"), "tìm giày"));
        assertEquals("WOMEN", AiProductMatch.detectQueryGender(List.of("nữ"), "tìm giày"));
        // tags không có → fallback dò userInput nguyên-token
        assertEquals("MEN", AiProductMatch.detectQueryGender(List.of("nike"), "giày nike nam màu đen"));
        assertEquals("WOMEN", AiProductMatch.detectQueryGender(null, "áo thể thao nữ"));
        assertNull(AiProductMatch.detectQueryGender(List.of("nike"), "tìm giày nike"));
        // "nam" nằm trong từ khác không tính (word boundary)
        assertNull(AiProductMatch.detectQueryGender(null, "giày namberone"));
    }

    @Test
    void strictTypeFilter_ReturnsEmptyWhenAllProductsWrongType() {
        ProductResponseDTO wrong = ProductResponseDTO.builder().id(1L).name("Quần thể thao").build();
        CategoryService categoryService = mock(CategoryService.class);
        when(categoryService.productMatchesType(wrong, "SHOES")).thenReturn(false);

        AiProductMatch.StrictTypeFilterResult result = AiProductMatch.strictTypeFilter(
                List.of(wrong),
                List.of(0.9),
                "SHOES",
                categoryService
        );

        assertTrue(result.products().isEmpty());
        assertTrue(result.scores().isEmpty());
    }
}
