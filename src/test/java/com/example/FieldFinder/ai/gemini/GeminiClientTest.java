package com.example.FieldFinder.ai.gemini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test các helper thuần của GeminiClient (không gọi HTTP):
 * làm sạch JSON fences, hash cache key, resize ảnh fail-safe.
 */
class GeminiClientTest {

    private final GeminiClient client = new GeminiClient(null, null);

    @Test
    void cleanJson_stripsJsonFences() {
        assertEquals("{\"a\":1}", client.cleanJson("```json\n{\"a\":1}\n```"));
        assertEquals("{\"a\":1}", client.cleanJson("```\n{\"a\":1}\n```"));
        assertEquals("{\"a\":1}", client.cleanJson("  {\"a\":1}  "));
    }

    @Test
    void cleanJson_nullReturnsEmptyObject() {
        assertEquals("{}", client.cleanJson(null));
    }

    @Test
    void sha256Hex_stableAndHexLength() {
        String h1 = GeminiClient.sha256Hex("abc");
        String h2 = GeminiClient.sha256Hex("abc");
        assertEquals(h1, h2);                       // ổn định
        assertEquals(64, h1.length());              // 32 byte → 64 hex
        assertNotEquals(h1, GeminiClient.sha256Hex("abd")); // khác input → khác hash
        assertTrue(h1.matches("[0-9a-f]{64}"));
    }

    @Test
    void resizeBase64_failSafeReturnsInput() {
        assertNull(GeminiClient.resizeBase64(null, 512));
        assertEquals("", GeminiClient.resizeBase64("", 512));
        // base64 không phải ảnh hợp lệ → ImageIO.read null → trả nguyên input
        String notAnImage = "bm90LWFuLWltYWdl"; // "not-an-image"
        assertEquals(notAnImage, GeminiClient.resizeBase64(notAnImage, 512));
    }
}
