package com.example.FieldFinder.ai.enrich;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test logic mime/Cloudinary của enrichment (chốt fix ảnh AVIF/GIF Gemini không nhận).
 * HTTP/Gemini không test ở đây — chỉ phần thuần quyết định định dạng ảnh.
 */
class ProductEnrichmentServiceTest {

    @Test
    void geminiSupportsMime_acceptsSupportedRejectsAvifGifNull() {
        assertTrue(ProductEnrichmentService.geminiSupportsMime("image/jpeg"));
        assertTrue(ProductEnrichmentService.geminiSupportsMime("image/png"));
        assertTrue(ProductEnrichmentService.geminiSupportsMime("image/webp"));
        assertFalse(ProductEnrichmentService.geminiSupportsMime("image/avif"));
        assertFalse(ProductEnrichmentService.geminiSupportsMime("image/gif"));
        assertFalse(ProductEnrichmentService.geminiSupportsMime(null));
    }

    @Test
    void guessMimeFromUrl_byExtension_defaultJpeg() {
        assertEquals("image/png", ProductEnrichmentService.guessMimeFromUrl("http://x/a.PNG"));
        assertEquals("image/webp", ProductEnrichmentService.guessMimeFromUrl("http://x/a.webp"));
        assertEquals("image/avif", ProductEnrichmentService.guessMimeFromUrl("http://x/a.avif"));
        assertEquals("image/jpeg", ProductEnrichmentService.guessMimeFromUrl("http://x/a.jpg"));
        assertEquals("image/jpeg", ProductEnrichmentService.guessMimeFromUrl("http://x/noext"));
    }

    @Test
    void cloudinaryAsJpg_insertsFJpgOnlyForCloudinaryUpload() {
        assertEquals(
                "https://res.cloudinary.com/demo/image/upload/f_jpg/v1/a.avif",
                ProductEnrichmentService.cloudinaryAsJpg("https://res.cloudinary.com/demo/image/upload/v1/a.avif"));
        // không phải cloudinary → null
        assertNull(ProductEnrichmentService.cloudinaryAsJpg("https://other.com/upload/a.avif"));
        // đã có f_jpg → null (không chèn lại)
        assertNull(ProductEnrichmentService.cloudinaryAsJpg("https://res.cloudinary.com/demo/image/upload/f_jpg/a.avif"));
        assertNull(ProductEnrichmentService.cloudinaryAsJpg(null));
    }
}
