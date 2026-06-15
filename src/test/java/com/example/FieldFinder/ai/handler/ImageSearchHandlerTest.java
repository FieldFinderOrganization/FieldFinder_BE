package com.example.FieldFinder.ai.handler;

import com.example.FieldFinder.dto.res.ProductResponseDTO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test các helper thuần của image-search (pin exact, expand màu). Pipeline gọi ML/Gemini
 * không test ở đây — chỉ logic sắp xếp/dedup/cap và mở rộng tag màu.
 */
class ImageSearchHandlerTest {

    // Helper không dùng dependency nào → construct với null là đủ.
    private final ImageSearchHandler handler =
            new ImageSearchHandler(null, null, null, null, null, null, null, null, null);

    private ProductResponseDTO p(long id) {
        return ProductResponseDTO.builder().id(id).name("P" + id).build();
    }

    @Test
    void pinExactFirst_movesPresentMatchToFront() {
        List<ProductResponseDTO> products = new ArrayList<>(List.of(p(1), p(2), p(3)));
        List<Double> scores = new ArrayList<>(List.of(0.9, 0.8, 0.7));

        handler.pinExactFirst(products, scores, 3L, p(3), 0.95, 10);

        assertEquals(3L, products.get(0).getId());
        assertEquals(3, products.size()); // chỉ đổi chỗ, không thêm
    }

    @Test
    void pinExactFirst_insertsAbsentExactAtFront() {
        List<ProductResponseDTO> products = new ArrayList<>(List.of(p(1), p(2)));
        List<Double> scores = new ArrayList<>(List.of(0.9, 0.8));

        handler.pinExactFirst(products, scores, 9L, p(9), 0.99, 10);

        assertEquals(9L, products.get(0).getId());
        assertEquals(3, products.size());
        assertEquals(0.99, scores.get(0));
    }

    @Test
    void pinExactFirst_capsToMaxSize() {
        List<ProductResponseDTO> products = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (int i = 1; i <= 12; i++) { products.add(p(i)); scores.add(0.5); }

        handler.pinExactFirst(products, scores, 12L, p(12), 0.99, 10);

        assertEquals(10, products.size());
        assertEquals(12L, products.get(0).getId()); // exact vẫn ở đầu sau khi cap
    }

    @Test
    void pinExactFirst_noopWhenExactPidNull() {
        List<ProductResponseDTO> products = new ArrayList<>(List.of(p(1), p(2)));
        List<Double> scores = new ArrayList<>(List.of(0.9, 0.8));

        handler.pinExactFirst(products, scores, null, null, 0.0, 10);

        assertEquals(1L, products.get(0).getId()); // không đổi
        assertEquals(2, products.size());
    }

    @Test
    void expandColorTags_addsCanonicalColor_noDuplicates() {
        List<String> out = handler.expandColorTags(List.of("black", "giày"));

        assertTrue(out.contains("black"));
        assertTrue(out.contains("giày"));
        assertTrue(out.contains("đen"), "canonical màu 'đen' phải được thêm cho 'black'");
        // distinct → không trùng
        assertEquals(out.size(), out.stream().distinct().count());
    }

    @Test
    void expandColorTags_nonColorUnchanged() {
        List<String> out = handler.expandColorTags(List.of("sneaker", "thể thao"));
        assertTrue(out.containsAll(List.of("sneaker", "thể thao")));
    }
}
