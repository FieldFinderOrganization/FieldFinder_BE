package com.example.FieldFinder.ai.gemini;

import com.example.FieldFinder.ai.GeminiRateLimiter;
import com.example.FieldFinder.service.RedisService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.cdimascio.dotenv.Dotenv;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Lớp transport gọi Gemini (chat / vision / embedding) — tách khỏi AIChat.
 * Chỉ lo HTTP + retry + làm sạch JSON; KHÔNG chứa logic nghiệp vụ hay prompt
 * (prompt do caller truyền vào). Hành vi giữ nguyên 1:1 so với bản cũ trong AIChat.
 */
@Component
public class GeminiClient {

    private static final String MODEL_VERSION = "gemini-2.5-flash";
    private static final String GEMINI_API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL_VERSION + ":generateContent?key=";
    private static final String EMBEDDING_API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:embedContent?key=";
    private static final String GOOGLE_API_KEY = Dotenv.load().get("GOOGLE_API_KEY");

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(15))
            .readTimeout(Duration.ofSeconds(60))
            .writeTimeout(Duration.ofSeconds(30))
            .callTimeout(Duration.ofSeconds(90))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    private final GeminiRateLimiter geminiRateLimiter;
    private final RedisService redisService;

    public GeminiClient(GeminiRateLimiter geminiRateLimiter, RedisService redisService) {
        this.geminiRateLimiter = geminiRateLimiter;
        this.redisService = redisService;
    }

    /** Gọi Gemini chat, trả về text JSON đã làm sạch ```json fences. */
    public String chat(String userInput, String systemPrompt) throws IOException, InterruptedException {
        ObjectNode rootNode = mapper.createObjectNode();
        ObjectNode systemInstNode = rootNode.putObject("system_instruction");
        systemInstNode.putObject("parts").put("text", systemPrompt);

        ArrayNode contentsArray = rootNode.putArray("contents");
        ObjectNode userMessage = contentsArray.addObject();
        userMessage.put("role", "user");
        userMessage.putObject("parts").put("text", userInput);

        ObjectNode generationConfig = rootNode.putObject("generationConfig");
        generationConfig.put("temperature", 0.1);
        generationConfig.put("response_mime_type", "application/json");
        generationConfig.put("maxOutputTokens", 2048);
        generationConfig.putObject("thinkingConfig").put("thinkingBudget", 0);

        Request request = new Request.Builder()
                .url(GEMINI_API_URL + GOOGLE_API_KEY)
                .post(RequestBody.create(mapper.writeValueAsString(rootNode), MediaType.parse("application/json")))
                .build();

        try (Response response = callWithRetry(request, "Chat")) {
            return cleanJson(extractGeminiResponse(response.body().string()));
        }
    }

    /**
     * Vision parse — mime tự suy từ tiền tố base64, không set temperature (mặc định), 1024 tokens.
     * Trả JsonNode đã parse, hoặc null nếu lỗi.
     */
    public JsonNode visionJson(String systemPrompt, String userText, String base64Image) {
        try {
            ObjectNode rootNode = mapper.createObjectNode();
            rootNode.putObject("system_instruction").putObject("parts").put("text", systemPrompt);

            ArrayNode contentsArray = rootNode.putArray("contents");
            ObjectNode userMessage = contentsArray.addObject();
            userMessage.put("role", "user");
            ArrayNode parts = userMessage.putArray("parts");
            parts.addObject().put("text", userText);

            if (base64Image != null && !base64Image.isEmpty()) {
                ObjectNode inlineData = parts.addObject().putObject("inline_data");
                String mimeType = "image/jpeg";
                String cleanB64 = base64Image;
                if (base64Image.contains(",")) {
                    String[] tokens = base64Image.split(",");
                    if (tokens[0].contains("png")) mimeType = "image/png";
                    cleanB64 = tokens[1];
                }
                inlineData.put("mime_type", mimeType);
                inlineData.put("data", cleanB64);
            }

            ObjectNode generationConfig = rootNode.putObject("generationConfig");
            generationConfig.put("response_mime_type", "application/json");
            generationConfig.put("maxOutputTokens", 1024);
            generationConfig.putObject("thinkingConfig").put("thinkingBudget", 0);

            Request request = new Request.Builder()
                    .url(GEMINI_API_URL + GOOGLE_API_KEY)
                    .post(RequestBody.create(mapper.writeValueAsString(rootNode), MediaType.parse("application/json")))
                    .build();

            try (Response response = callWithRetry(request, "Image Analysis")) {
                return mapper.readTree(cleanJson(extractGeminiResponse(response.body().string())));
            }
        } catch (Exception e) {
            System.err.println("GeminiClient.visionJson fail: " + e.getMessage());
            return null;
        }
    }

    /**
     * Vision parse với mime + temperature tường minh (dùng cho enrichment).
     * base64Image phải là chuỗi đã sạch (không tiền tố data:). Trả JsonNode hoặc null.
     */
    public JsonNode visionJson(String systemPrompt, String userText, String base64Image,
                               String mimeType, double temperature) {
        try {
            ObjectNode rootNode = mapper.createObjectNode();
            rootNode.putObject("system_instruction").putObject("parts").put("text", systemPrompt);

            ArrayNode contentsArray = rootNode.putArray("contents");
            ObjectNode userMessage = contentsArray.addObject();
            userMessage.put("role", "user");
            ArrayNode parts = userMessage.putArray("parts");
            parts.addObject().put("text", userText);

            ObjectNode inlineData = parts.addObject().putObject("inline_data");
            inlineData.put("mime_type", mimeType);
            inlineData.put("data", base64Image);

            ObjectNode generationConfig = rootNode.putObject("generationConfig");
            generationConfig.put("temperature", temperature);
            generationConfig.put("response_mime_type", "application/json");
            generationConfig.put("maxOutputTokens", 1024);
            generationConfig.putObject("thinkingConfig").put("thinkingBudget", 0);

            Request request = new Request.Builder()
                    .url(GEMINI_API_URL + GOOGLE_API_KEY)
                    .post(RequestBody.create(mapper.writeValueAsString(rootNode), MediaType.parse("application/json")))
                    .build();

            try (Response response = callWithRetry(request, "Product Tags Enrichment")) {
                return mapper.readTree(cleanJson(extractGeminiResponse(response.body().string())));
            }
        } catch (Exception e) {
            System.err.println("GeminiClient.visionJson(enrich) fail: " + e.getMessage());
            return null;
        }
    }

    /** Embedding gemini-embedding-001, cache Redis theo hash(text) TTL 7 ngày. */
    public List<Double> getEmbedding(String text) {
        if (text == null || text.isBlank()) return new ArrayList<>();

        String cacheKey = "ai:embed:" + sha256Hex(text);
        try {
            String cached = redisService.getData(cacheKey);
            if (cached != null) {
                return mapper.readValue(cached, new TypeReference<List<Double>>() {});
            }
        } catch (Exception ignored) {
            // cache miss / parse fail → fetch online
        }

        try {
            ObjectNode rootNode = mapper.createObjectNode();
            ObjectNode content = rootNode.putObject("content");
            content.putObject("parts").put("text", text);

            Request request = new Request.Builder()
                    .url(EMBEDDING_API_URL + GOOGLE_API_KEY)
                    .post(RequestBody.create(mapper.writeValueAsString(rootNode), MediaType.parse("application/json")))
                    .build();

            try (Response response = callWithRetry(request, "Embedding")) {
                JsonNode root = mapper.readTree(response.body().string());
                JsonNode valuesNode = root.path("embedding").path("values");

                List<Double> vector = new ArrayList<>();
                if (valuesNode.isArray()) {
                    for (JsonNode val : valuesNode) {
                        vector.add(val.asDouble());
                    }
                }
                if (!vector.isEmpty()) {
                    try {
                        redisService.saveDataWithTTL(cacheKey, mapper.writeValueAsString(vector), 7, TimeUnit.DAYS);
                    } catch (Exception ignored) {}
                }
                return vector;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    private Response callWithRetry(Request request, String description) throws IOException, InterruptedException {
        int maxRetries = 4;
        long backoff = 6000; // 6s base — Gemini free tier cần nhiều thời gian hồi hơn 4s

        for (int i = 0; i <= maxRetries; i++) {
            geminiRateLimiter.acquire();
            Response response;
            try {
                response = client.newCall(request).execute();
            } catch (SocketTimeoutException ste) {
                if (i < maxRetries) {
                    System.err.println("⚠️ [" + description + "] timeout. Retry " + (i + 1) + "/" + maxRetries + " sau " + backoff + "ms...");
                    Thread.sleep(backoff);
                    backoff = Math.min(backoff * 2, 30000);
                    continue;
                }
                throw new IOException("Gemini timeout sau " + maxRetries + " retries: " + ste.getMessage(), ste);
            }

            if (response.isSuccessful()) {
                return response;
            }

            String errorBody = response.body() != null ? response.body().string() : "No body";
            if (response.code() == 429 && i < maxRetries) {
                String retryAfter = response.header("Retry-After");
                long waitMs = backoff;
                if (retryAfter != null) {
                    try {
                        waitMs = Math.max(backoff, Long.parseLong(retryAfter.trim()) * 1000);
                    } catch (NumberFormatException ignored) {}
                }
                System.err.println("⚠️ [" + description + "] Gemini 429. Retry " + (i + 1) + "/" + maxRetries + " sau " + waitMs + "ms...");
                response.close();
                Thread.sleep(waitMs);
                backoff = Math.min(backoff * 2, 30000);
                continue;
            }

            throw new IOException("Gemini API Error [" + response.code() + "]: " + errorBody);
        }
        throw new IOException("Gemini API call failed after " + maxRetries + " retries.");
    }

    private String extractGeminiResponse(String rawJson) throws IOException {
        JsonNode root = mapper.readTree(rawJson);
        if (root.path("candidates").isMissingNode() || root.path("candidates").isEmpty()) {
            String errMsg = root.path("error").path("message").asText("");
            System.err.println("⚠️ Gemini không trả về candidates. Raw: " + (rawJson != null && rawJson.length() > 500 ? rawJson.substring(0, 500) + "..." : rawJson));
            throw new IOException("Gemini API không trả về kết quả" + (errMsg.isEmpty() ? "" : ": " + errMsg));
        }
        return root.path("candidates").get(0)
                .path("content").path("parts").get(0)
                .path("text").asText();
    }

    /** Bỏ ```json fences. Public để test + tái dùng. */
    public String cleanJson(String raw) {
        if (raw == null) return "{}";
        String cleaned = raw.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        return cleaned.trim();
    }

    /** Resize base64 ảnh về max maxDim px (cạnh dài), JPEG 80%. Fail → trả input gốc. */
    public static String resizeBase64(String base64Clean, int maxDim) {
        if (base64Clean == null || base64Clean.isEmpty()) return base64Clean;
        try {
            byte[] raw = Base64.getDecoder().decode(base64Clean);
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(raw));
            if (img == null) return base64Clean;
            int w = img.getWidth(), h = img.getHeight();
            if (w <= maxDim && h <= maxDim) return base64Clean;
            double scale = (double) maxDim / Math.max(w, h);
            int nw = (int) Math.round(w * scale);
            int nh = (int) Math.round(h * scale);
            java.awt.image.BufferedImage resized = new java.awt.image.BufferedImage(nw, nh,
                    java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = resized.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, nw, nh, null);
            g.dispose();
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(resized, "jpg", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            System.err.println("⚠️ resizeBase64 fail: " + e.getMessage());
            return base64Clean;
        }
    }

    /** SHA-256 hex cho cache key. */
    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
