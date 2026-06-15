package com.example.FieldFinder.ai.match;

import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.example.FieldFinder.service.CategoryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Helper khớp sản phẩm dùng chung cho image-search / product-query / recommend. */
class AiProductMatchTest {

    @Test
    void containsQueryToken_wholeTokenOnly() {
        assertTrue(AiProductMatch.containsQueryToken("tôi muốn puma size 42", "puma"));
        assertTrue(AiProductMatch.containsQueryToken("balo New Balance màu đen", "new balance"));
        assertFalse(AiProductMatch.containsQueryToken("spumante không phải brand", "puma")); // substring không tính
        assertFalse(AiProductMatch.containsQueryToken(null, "puma"));
    }

    @Test
    void normalizeAiProductType_mapsSynonyms_nullForInvalid() {
        assertEquals("SHOES", AiProductMatch.normalizeAiProductType("shoe"));
        assertEquals("SHOES", AiProductMatch.normalizeAiProductType("FOOTWEAR"));
        assertEquals("TOP", AiProductMatch.normalizeAiProductType("jersey"));
        assertEquals("BOTTOM", AiProductMatch.normalizeAiProductType("shorts"));
        assertEquals("BAG", AiProductMatch.normalizeAiProductType("backpack"));
        assertNull(AiProductMatch.normalizeAiProductType(null));
        assertNull(AiProductMatch.normalizeAiProductType("NULL"));
        assertNull(AiProductMatch.normalizeAiProductType("xyz"));
    }

    @Test
    void strictTypeFilter_keepsOnlyMatchingType_preservesScores() {
        ProductResponseDTO shoe = ProductResponseDTO.builder().id(1L).name("Giày").build();
        ProductResponseDTO bag = ProductResponseDTO.builder().id(2L).name("Túi").build();
        CategoryService cs = mock(CategoryService.class);
        when(cs.productMatchesType(shoe, "SHOES")).thenReturn(true);
        when(cs.productMatchesType(bag, "SHOES")).thenReturn(false);

        var r = AiProductMatch.strictTypeFilter(
                List.of(shoe, bag), List.of(0.9, 0.8), "SHOES", cs);

        assertEquals(1, r.products().size());
        assertEquals(1L, r.products().get(0).getId());
        assertEquals(0.9, r.scores().get(0));
    }

    @Test
    void strictTypeFilter_nullSafe() {
        var r = AiProductMatch.strictTypeFilter(null, null, "SHOES", null);
        assertTrue(r.products().isEmpty());
        assertTrue(r.scores().isEmpty());
    }

    @Test
    void detectQuerySize_geminiThenRegex() {
        assertEquals("39", AiProductMatch.detectQuerySize("39", "size 40"));      // Gemini ưu tiên
        assertEquals("40", AiProductMatch.detectQuerySize(null, "cỡ 40 có hàng"));
        assertEquals("XL", AiProductMatch.detectQuerySize(null, "áo sz xl"));
        assertNull(AiProductMatch.detectQuerySize(null, "giày màu đen"));
    }

    @Test
    void detectQueryGender_tagsThenInput() {
        assertEquals("MEN", AiProductMatch.detectQueryGender(List.of("nam"), "tìm giày"));
        assertEquals("WOMEN", AiProductMatch.detectQueryGender(null, "áo thể thao nữ"));
        assertNull(AiProductMatch.detectQueryGender(List.of("nike"), "tìm giày nike"));
    }

    @Test
    void detectQueryBrand_longestBrandTokenInText() {
        ProductResponseDTO p1 = ProductResponseDTO.builder().id(1L).brand("Nike").build();
        ProductResponseDTO p2 = ProductResponseDTO.builder().id(2L).brand("New Balance").build();
        List<ProductResponseDTO> catalog = List.of(p1, p2);

        // "new balance" dài hơn → ưu tiên khi cả hai khớp
        assertEquals("New Balance",
                AiProductMatch.detectQueryBrand(catalog, "giày New Balance 550", null, "tìm giày"));
        assertEquals("Nike",
                AiProductMatch.detectQueryBrand(catalog, null, List.of("nike", "air"), "đôi nike"));
        assertNull(AiProductMatch.detectQueryBrand(catalog, "giày thể thao", null, "abc"));
        assertNull(AiProductMatch.detectQueryBrand(null, "nike", null, null));
    }
}
