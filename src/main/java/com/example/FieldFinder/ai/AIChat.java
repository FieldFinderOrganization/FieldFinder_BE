package com.example.FieldFinder.ai;
//deploytest
import com.example.FieldFinder.Enum.PitchEnvironment;
import com.example.FieldFinder.dto.res.MLItemResult;
import com.example.FieldFinder.dto.res.MLRetrieveResponse;
import com.example.FieldFinder.dto.res.PitchResponseDTO;
import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.example.FieldFinder.mapper.CategoryMapper;
import com.example.FieldFinder.service.BookingService;
import com.example.FieldFinder.dto.req.MLRetrieveByImageRequest;
import com.example.FieldFinder.service.CategoryService;
import com.example.FieldFinder.service.MLRecommendationService;
import com.example.FieldFinder.service.OpenWeatherService;
import com.example.FieldFinder.service.PhashIndex;
import com.example.FieldFinder.service.PitchService;
import com.example.FieldFinder.service.ProductService;
import com.example.FieldFinder.service.RedisService;
import com.example.FieldFinder.service.UserService;
import com.example.FieldFinder.util.PhashUtil;
import com.example.FieldFinder.service.log.LogPublisherService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.cdimascio.dotenv.Dotenv;
import okhttp3.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.security.MessageDigest;

@Component
public class AIChat {

    private static final String GOOGLE_API_KEY;

    private static final String MODEL_VERSION = "gemini-2.5-flash";

    private static final String GEMINI_API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL_VERSION + ":generateContent?key=";

    private static final String EMBEDDING_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:embedContent?key=";

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(15))
            .readTimeout(Duration.ofSeconds(60))
            .writeTimeout(Duration.ofSeconds(30))
            .callTimeout(Duration.ofSeconds(90))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final PitchService pitchService;
    private final ProductService productService;
    private final UserService userService;
    private final LogPublisherService logPublisherService;
    private final BookingService bookingService;
    private final RedisService redisService;
    private final MLRecommendationService mlService;
    private final PhashIndex phashIndex;
    private final CategoryService categoryService;
    private final com.example.FieldFinder.ai.ranking.CompositeRanker compositeRanker;
    private final AiChatSessionContextStore sessionContextStore;
    private final GeminiRateLimiter geminiRateLimiter;

    // Pause flag: khi user đang chat thì tạm dừng background enrichment
    private volatile boolean enrichmentPaused = false;
    public void pauseEnrichment()  { this.enrichmentPaused = true; }
    public void resumeEnrichment() { this.enrichmentPaused = false; }

    private final OpenWeatherService weatherService;

    // Optional: present only when MongoDB is configured. Used to read VIEW_PITCH interaction logs
    // for "đã xem" personalization signals. Null-safe everywhere it's used.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    public AIChat(PitchService pitchService, ProductService productService, UserService userService, OpenWeatherService weatherService, LogPublisherService logPublisherService, BookingService bookingService, RedisService redisService, MLRecommendationService mlService, PhashIndex phashIndex, CategoryService categoryService, com.example.FieldFinder.ai.ranking.CompositeRanker compositeRanker, AiChatSessionContextStore sessionContextStore, GeminiRateLimiter geminiRateLimiter) {
        this.pitchService = pitchService;
        this.productService = productService;
        this.userService = userService;
        this.weatherService = weatherService;
        this.logPublisherService = logPublisherService;
        this.bookingService = bookingService;
        this.redisService = redisService;
        this.phashIndex = phashIndex;
        this.mlService = mlService;
        this.categoryService = categoryService;
        this.compositeRanker = compositeRanker;
        this.sessionContextStore = sessionContextStore;
        this.geminiRateLimiter = geminiRateLimiter;
    }

    /**
     * ML-powered retrieval helper.
     * Trả List<ProductResponseDTO> match theo query bằng FastAPI RAG.
     * Trả null nếu ML fail / disabled → caller fallback retrieval cũ.
     */
    private List<ProductResponseDTO> retrieveProductsViaML(String query, UUID userId, int topK) {
        try {
            List<MLItemResult> hits = mlService.retrieve(query, userId != null ? userId.toString() : null, topK, "PRODUCT");
            if (hits == null || hits.isEmpty()) return null;
            List<ProductResponseDTO> out = new ArrayList<>();
            for (MLItemResult h : hits) {
                if (h.getItemId() == null) continue;
                try {
                    Long pid = Long.parseLong(h.getItemId());
                    ProductResponseDTO p = productService.getProductById(pid, userId);
                    if (p != null) out.add(p);
                } catch (NumberFormatException ignored) {
                    // ML có thể trả non-numeric ID — skip
                }
            }
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            System.err.println("ML retrieveProducts fallback: " + e.getMessage());
            return null;
        }
    }

    private List<PitchResponseDTO> retrievePitchesViaML(String query, UUID userId, int topK) {
        try {
            List<MLItemResult> hits = mlService.retrieve(query, userId != null ? userId.toString() : null, topK, "PITCH");
            if (hits == null || hits.isEmpty()) return null;
            List<PitchResponseDTO> out = new ArrayList<>();
            for (MLItemResult h : hits) {
                if (h.getItemId() == null) continue;
                try {
                    UUID pid = UUID.fromString(h.getItemId());
                    PitchResponseDTO p = pitchService.getPitchById(pid);
                    if (p != null) out.add(p);
                } catch (IllegalArgumentException ignored) {
                }
            }
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            System.err.println("ML retrievePitches fallback: " + e.getMessage());
            return null;
        }
    }

    private UUID resolveCurrentUserId(String sessionId) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof String) {
                String email = (String) auth.getPrincipal();
                if (email != null && !email.equals("anonymousUser") && !email.isBlank()) {
                    UUID uid = redisService.getUserIdByEmail(email);
                    if (uid != null) return uid;
                }
            }
        } catch (Exception e) {
            System.err.println("resolveCurrentUserId error: " + e.getMessage());
        }
        if (sessionId != null) {
            return userService.getUserIdBySession(sessionId);
        }
        return null;
    }

    static {
        Dotenv dotenv = Dotenv.load();
        dotenv.entries().forEach(entry -> {
            System.setProperty(entry.getKey(), entry.getValue());
        });
        GOOGLE_API_KEY = dotenv.get("GOOGLE_API_KEY");
    }

    private List<String> sanitizeTags(List<String> rawTags) {
        if (rawTags == null || rawTags.isEmpty()) {
            return new ArrayList<>();
        }
        return rawTags.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .map(tag -> tag.trim().toLowerCase())
                .filter(tag -> !tag.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    private List<String> expandColorTags(List<String> tags) {
        List<String> expandedTags = new ArrayList<>(tags);

        for (String tag : tags) {
            String t = tag.toLowerCase();

            if (t.contains("kem") || t.contains("cream") || t.contains("be") || t.contains("beige") || t.contains("sữa")) {
                expandedTags.add("trắng");
                expandedTags.add("white");
            }

            // 2. Nhóm MÀU NÓNG (Hồng <=> Cam <=> Đỏ)
            if (t.contains("hồng") || t.contains("pink") || t.contains("mận")) {
                expandedTags.add("cam");
                expandedTags.add("orange");
                expandedTags.add("đỏ");
                expandedTags.add("red");
                expandedTags.add("tím");
                expandedTags.add("purple");
            }
            // Nếu AI thấy Cam, tìm luôn cả Hồng và Đỏ
            if (t.contains("cam") || t.contains("orange") || t.contains("coral")) {
                expandedTags.add("hồng");
                expandedTags.add("pink");
                expandedTags.add("đỏ");
                expandedTags.add("red");
            }
            // Nếu AI thấy Đỏ, tìm luôn cả Cam và Hồng
            if (t.contains("đỏ") || t.contains("red") || t.contains("crimson")) {
                expandedTags.add("cam");
                expandedTags.add("orange");
                expandedTags.add("hồng");
                expandedTags.add("pink");
            }

            // 3. Nhóm XANH (Dương / Navy / Trời)
            if (t.contains("navy") || t.contains("chàm") || t.contains("biển") || t.contains("sky")) {
                expandedTags.add("xanh");
                expandedTags.add("blue");
                expandedTags.add("xanh dương");
            }

            // 4. Nhóm ĐEN (Đen / Xám đậm)
            if (t.contains("than") || t.contains("ghi") || t.contains("grey") || t.contains("gray")) {
                expandedTags.add("đen");
                expandedTags.add("black");
            }
        }

        return expandedTags.stream().distinct().collect(Collectors.toList());
    }

    public List<Double> getEmbedding(String text) {
        if (text == null || text.isBlank()) return new ArrayList<>();

        // P3: Redis cache embedding theo hash(text), TTL 7 ngày
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

    /**
     * Resize base64 image về max 512px (long edge), JPEG quality 80%, để giảm payload gửi ML/Gemini.
     * Trả base64 mới (không có prefix data:). Nếu fail → trả input gốc.
     */
    private static String resizeBase64(String base64Clean, int maxDim) {
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

    /** SHA-256 hex helper cho cache key. */
    private static String sha256Hex(String input) {
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

    /** P2: Cache `getAllPitches(0,50)` TTL 90s. */
    private List<PitchResponseDTO> getAllPitchesCached() {
        String key = "ai:pitches:all:50";
        try {
            String cached = redisService.getData(key);
            if (cached != null) {
                return mapper.readValue(cached, new TypeReference<List<PitchResponseDTO>>() {});
            }
        } catch (Exception ignored) {}
        List<PitchResponseDTO> data = pitchService.getAllPitches(PageRequest.of(0, 50), null, null, null).getContent();
        try {
            redisService.saveDataWithTTL(key, mapper.writeValueAsString(data), 90, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
        return data;
    }

    /** P3+: Cache productsByIds map. Key = sorted ids hash. TTL 60s. */
    private Map<Long, ProductResponseDTO> getProductsByIdsCached(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyMap();
        List<Long> sorted = new ArrayList<>(ids);
        Collections.sort(sorted);
        String key = "ai:products:byids:" + sha256Hex(sorted.toString());
        try {
            String cached = redisService.getData(key);
            if (cached != null) {
                List<ProductResponseDTO> list = mapper.readValue(cached,
                        new TypeReference<List<ProductResponseDTO>>() {});
                Map<Long, ProductResponseDTO> map = new LinkedHashMap<>();
                for (ProductResponseDTO p : list) {
                    if (p != null && p.getId() != null) map.put(p.getId(), p);
                }
                return map;
            }
        } catch (Exception ignored) {}
        Map<Long, ProductResponseDTO> fresh = productService.getProductsByIds(ids, null);
        try {
            redisService.saveDataWithTTL(key, mapper.writeValueAsString(new ArrayList<>(fresh.values())),
                    60, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
        return fresh;
    }

    /** P2: Cache `getProductsForAiAssistant(userId)` TTL 60s. */
    private List<ProductResponseDTO> getProductsForAiAssistantCached(java.util.UUID userId) {
        String key = "ai:products:assistant:" + (userId == null ? "anon" : userId.toString());
        try {
            String cached = redisService.getData(key);
            if (cached != null) {
                return mapper.readValue(cached, new TypeReference<List<ProductResponseDTO>>() {});
            }
        } catch (Exception ignored) {}
        List<ProductResponseDTO> data = productService.getProductsForAiAssistant(userId);
        try {
            redisService.saveDataWithTTL(key, mapper.writeValueAsString(data), 60, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
        return data;
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
                // Parse Retry-After header nếu Gemini gửi
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
                backoff = Math.min(backoff * 2, 30000); // Cap at 30s
                continue;
            }

            // Nếu không phải 429 hoặc đã hết lượt retry
            throw new IOException("Gemini API Error [" + response.code() + "]: " + errorBody);
        }
        throw new IOException("Gemini API call failed after " + maxRetries + " retries.");
    }

    private String buildSystemPrompt(List<PitchResponseDTO> allPitches) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));

        long fiveCount = allPitches.stream().filter(p -> p.getType().name().equals("FIVE_A_SIDE")).count();
        long sevenCount = allPitches.stream().filter(p -> p.getType().name().equals("SEVEN_A_SIDE")).count();
        long elevenCount = allPitches.stream().filter(p -> p.getType().name().equals("ELEVEN_A_SIDE")).count();

        return SYSTEM_INSTRUCTION
                .replace("{{today}}", today.toString())
                .replace("{{plus1}}", today.plusDays(1).toString())
                .replace("{{plus2}}", today.plusDays(2).toString())
                .replace("{{year}}", String.valueOf(today.getYear()))
                .replace("{{totalPitches}}", String.valueOf(allPitches.size()))
                .replace("{{fiveASideCount}}", String.valueOf(fiveCount))
                .replace("{{sevenASideCount}}", String.valueOf(sevenCount))
                .replace("{{elevenASideCount}}", String.valueOf(elevenCount));
    }

    private String callGeminiAPI(String userInput, String systemPrompt) throws IOException, InterruptedException {
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

    /** Gọi Gemini Vision parse JSON. Trả null nếu fail. */
    private JsonNode callGeminiVisionParse(String base64Image) {
        try {
            ObjectNode rootNode = mapper.createObjectNode();
            ObjectNode systemInstNode = rootNode.putObject("system_instruction");
            systemInstNode.putObject("parts").put("text", IMAGE_ANALYSIS_SYSTEM_PROMPT);

            ArrayNode contentsArray = rootNode.putArray("contents");
            ObjectNode userMessage = contentsArray.addObject();
            userMessage.put("role", "user");
            ArrayNode parts = userMessage.putArray("parts");
            parts.addObject().put("text", "Phân tích ảnh này và trích xuất Tags.");

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
                String rawJson = extractGeminiResponse(response.body().string());
                String cleanJson = cleanJson(rawJson);
                return mapper.readTree(cleanJson);
            }
        } catch (Exception e) {
            System.err.println("callGeminiVisionParse fail: " + e.getMessage());
            return null;
        }
    }

    public BookingQuery processImageSearchWithGemini(String base64Image, String sessionId) {
        final long _tStart = System.currentTimeMillis();
        Runnable _logTotal = () -> System.out.println("[IMG-TIMING] processImageSearchWithGemini TOTAL="
                + (System.currentTimeMillis() - _tStart) + "ms");
        BookingQuery result = new BookingQuery();
        result.data = new HashMap<>();
        result.slotList = new ArrayList<>();
        result.pitchType = "ALL";

        // In-shop exact match to pin at result #0 (pHash near-dup OR CLIP high-conf).
        // Captured in Stage 0, applied before returning Stage 1 / Stage 2 results.
        Long pinnedPid = null;
        ProductResponseDTO pinnedDto = null;
        double pinnedScore = 0.0;

        // ========== Stage 0: pHash near-duplicate match ==========
        long _t0 = System.currentTimeMillis();
        Long uploadHash = PhashUtil.computeFromBase64(base64Image);
        System.out.println("[IMG-TIMING] pHash compute=" + (System.currentTimeMillis() - _t0) + "ms");
        System.out.println("🔍 pHash debug: uploadHash=" + uploadHash + " indexSize=" + phashIndex.size());
        if (uploadHash != null && phashIndex.size() > 0) {
            List<PhashIndex.Hit> debugTop = phashIndex.findWithin(uploadHash, 64, 3);
            System.out.println("🔍 pHash top3 distances: " + debugTop.stream()
                    .map(h -> h.productId + "=" + h.distance)
                    .collect(java.util.stream.Collectors.joining(", ")));
            List<PhashIndex.Hit> hits = phashIndex.findWithin(uploadHash, 8, 5);
            if (!hits.isEmpty()) {
                // P3: Batch fetch products thay N+1
                List<Long> pidList = hits.stream().map(h -> h.productId).collect(Collectors.toList());
                Map<Long, ProductResponseDTO> pmap = getProductsByIdsCached(pidList);
                List<ProductResponseDTO> products = new ArrayList<>();
                List<Double> scores = new ArrayList<>();
                for (PhashIndex.Hit h : hits) {
                    ProductResponseDTO p = pmap.get(h.productId);
                    if (p != null) {
                        products.add(p);
                        scores.add(1.0 - (h.distance / 64.0));
                    }
                }
                if (!products.isEmpty()) {
                    // No longer short-circuit. Record the exact match; Stage 1 builds the
                    // "similar" backfill and pinExactFirst() pins this product at #0.
                    pinnedPid = hits.get(0).productId;
                    pinnedDto = pmap.get(pinnedPid);
                    pinnedScore = 1.0 - (hits.get(0).distance / 64.0);
                    System.out.println("✅ pHash exact: pid=" + pinnedPid + " dist=" + hits.get(0).distance
                            + " → pin #0, backfill similars via Stage 1");
                }
            }
        }

        // ========== Stage 1: Gemini Vision context + Hybrid CLIP (RRF + filter + MMR) ==========
        String cleanBase64 = base64Image;
        if (cleanBase64 != null && cleanBase64.contains(",")) {
            cleanBase64 = cleanBase64.substring(cleanBase64.indexOf(',') + 1);
        }
        // P4: Resize ảnh xuống max 512px để giảm payload ML/Gemini → tăng tốc upload + inference
        long _tResize = System.currentTimeMillis();
        int _origLen = cleanBase64 != null ? cleanBase64.length() : 0;
        cleanBase64 = resizeBase64(cleanBase64, 512);
        int _newLen = cleanBase64 != null ? cleanBase64.length() : 0;
        System.out.println("[IMG-TIMING] resize=" + (System.currentTimeMillis() - _tResize)
                + "ms (base64 " + _origLen + "→" + _newLen + ")");
        final String resizedForVision = cleanBase64;

        // Pre-call Gemini Vision để lấy caption/category/tags/productType. Nếu fail → context empty.
        String parsedCategory = null, parsedProductName = null, parsedColor = null, parsedProductType = null;
        List<String> parsedTags = new ArrayList<>();

        // P2: Cache Vision parse theo uploadHash (pHash). Same image perceptually → reuse parsed JSON.
        String visionCacheKey = uploadHash != null ? "ai:vision:phash:" + uploadHash : null;
        JsonNode cachedVision = null;
        if (visionCacheKey != null) {
            try {
                String cached = redisService.getData(visionCacheKey);
                if (cached != null) {
                    cachedVision = mapper.readTree(cached);
                    System.out.println("[IMG-TIMING] Gemini Vision parse=0ms (CACHE HIT)");
                }
            } catch (Exception ignored) {}
        }

        // P1: Parallel — Vision parse + ML CLIP retrieve.
        // ML không cần Vision context bắt buộc (image alone đủ chạy CLIP).
        // Trade-off: ML mất category hint → có thể giảm precision Stage 1 chút, nhưng tiết kiệm ~3s.
        // Vision cache hit → bypass async, ML vẫn nhận enriched req (best of both).
        long _tParallel = System.currentTimeMillis();

        // Kick off Vision future
        CompletableFuture<JsonNode> visionFuture;
        if (cachedVision != null) {
            JsonNode finalCached = cachedVision;
            visionFuture = CompletableFuture.completedFuture(finalCached);
        } else {
            visionFuture = CompletableFuture.supplyAsync(() -> {
                long _tV = System.currentTimeMillis();
                try {
                    JsonNode v = callGeminiVisionParse(resizedForVision);
                    System.out.println("[IMG-TIMING] Gemini Vision parse=" + (System.currentTimeMillis() - _tV) + "ms");
                    return v;
                } catch (Exception e) {
                    System.err.println("⚠️ Gemini Vision pre-parse fail: " + e.getMessage());
                    return null;
                }
            });
        }

        // Kick off ML future in parallel (image-only request; vision-enriched hints would require waiting).
        UUID resolvedMlUid = resolveCurrentUserId(sessionId);
        String mlUserId = resolvedMlUid != null ? resolvedMlUid.toString() : null;
        // Over-fetch (20, not 10): ML retrieve is image-only/cross-category, so the category
        // gate below drops wrong-type items — need a bigger pool to still fill ~10 same-type.
        // 20 = ML ImageRetrieveRequest.top_k cap (Field le=20); 30 → HTTP 422.
        MLRetrieveByImageRequest mlReqEarly = MLRetrieveByImageRequest.builder()
                .imageBase64(cleanBase64)
                .topK(20)
                .retrieveK(40)
                .itemType("PRODUCT")
                .userId(mlUserId)
                .build();
        CompletableFuture<MLRetrieveResponse> mlFuture = CompletableFuture.supplyAsync(() -> {
            long _tM = System.currentTimeMillis();
            try {
                MLRetrieveResponse r = mlService.retrieveByImageFull(mlReqEarly);
                System.out.println("[IMG-TIMING] ML CLIP retrieve=" + (System.currentTimeMillis() - _tM) + "ms");
                return r;
            } catch (Exception e) {
                System.err.println("⚠️ ML retrieve fail: " + e.getMessage());
                return null;
            }
        });

        // Wait both
        JsonNode visionJson = null;
        MLRetrieveResponse mlResEarly = null;
        try {
            CompletableFuture.allOf(visionFuture, mlFuture).get(25, TimeUnit.SECONDS);
            visionJson = visionFuture.getNow(null);
            mlResEarly = mlFuture.getNow(null);
        } catch (Exception e) {
            System.err.println("⚠️ Parallel future timeout/error: " + e.getMessage());
            visionJson = visionFuture.getNow(null);
            mlResEarly = mlFuture.getNow(null);
        }

        if (visionJson != null) {
            try {
                List<String> rawTags = mapper.convertValue(visionJson.path("tags"),
                        new TypeReference<List<String>>(){});
                parsedTags = sanitizeTags(rawTags);
                parsedCategory = visionJson.path("majorCategory").asText("");
                parsedProductName = visionJson.path("productName").asText("");
                parsedColor = visionJson.path("color").asText("");
                parsedProductType = visionJson.path("productType").asText("");

                if (cachedVision == null && visionCacheKey != null) {
                    try {
                        redisService.saveDataWithTTL(visionCacheKey, mapper.writeValueAsString(visionJson),
                                7, TimeUnit.DAYS);
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                System.err.println("⚠️ Vision json convert fail: " + e.getMessage());
            }
        }
        final MLRetrieveResponse mlResultEarly = mlResEarly;
        System.out.println("[IMG-TIMING] Parallel phase total=" + (System.currentTimeMillis() - _tParallel) + "ms");

        try {
            // Tier 1: narrower productType (SHOES/TOP/BOTTOM/DRESS...) → STANDARD-level
            // Tier 2: fallback majorCategory SUPER_CATEGORY
            List<Long> typeIds = parsedProductType != null
                    ? categoryService.expandByProductType(parsedProductType)
                    : new ArrayList<>();
            List<Long> superIds = parsedCategory != null
                    ? categoryService.expandToSuperCategoryDescendants(parsedCategory)
                    : new ArrayList<>();
            List<Long> categoryIds = !typeIds.isEmpty() ? typeIds : superIds;
            System.out.println("🔍 Gemini parsed: category='" + parsedCategory
                    + "' productType='" + parsedProductType
                    + "' productName='" + parsedProductName + "' tags=" + parsedTags
                    + " → typeIds=" + typeIds.size() + " superIds=" + superIds.size()
                    + " → using=" + categoryIds.size() + " " + categoryIds);
            String caption = String.join(" ",
                    parsedCategory != null ? parsedCategory : "",
                    parsedProductName != null ? parsedProductName : "",
                    parsedColor != null ? parsedColor : "",
                    String.join(" ", parsedTags)
            ).trim();

            // P1: ML đã chạy parallel ở trên, dùng kết quả pre-fetched.
            MLRetrieveResponse mlRes = mlResultEarly;
            List<MLItemResult> clipHits = mlRes != null ? mlRes.getResults() : null;
            if (clipHits != null && !clipHits.isEmpty()) {
                System.out.println("🔍 Hybrid CLIP top scores: " + clipHits.stream()
                        .map(h -> h.getItemId() + "=" + String.format("%.4f", h.getScore() != null ? h.getScore() : 0.0))
                        .collect(Collectors.joining(", "))
                        + " | categoryIds=" + categoryIds.size()
                        + " | ml_latency=" + (mlRes.getLatencyMs() != null ? mlRes.getLatencyMs() + "ms" : "n/a"));
                // Use ML-configured threshold (centralized in ml/config.py), fallback 0.005
                final double THRESHOLD = (mlRes.getRrfThreshold() != null) ? mlRes.getRrfThreshold() : 0.005;
                // P3: Batch fetch — thu thập pid + score, query 1 lần
                List<Long> pidOrder = new ArrayList<>();
                List<Double> scoreOrder = new ArrayList<>();
                for (MLItemResult h : clipHits) {
                    if (h.getItemId() == null) continue;
                    if (h.getScore() == null || h.getScore() < THRESHOLD) continue;
                    try {
                        pidOrder.add(Long.parseLong(h.getItemId()));
                        scoreOrder.add(h.getScore());
                    } catch (NumberFormatException ignored) {}
                }
                long _tDb = System.currentTimeMillis();
                Map<Long, ProductResponseDTO> pmap = getProductsByIdsCached(pidOrder);
                System.out.println("[IMG-TIMING] getProductsByIdsCached(" + pidOrder.size() + ")="
                        + (System.currentTimeMillis() - _tDb) + "ms");
                List<ProductResponseDTO> products = new ArrayList<>();
                List<Double> scores = new ArrayList<>();
                for (int i = 0; i < pidOrder.size(); i++) {
                    ProductResponseDTO p = pmap.get(pidOrder.get(i));
                    if (p != null) {
                        products.add(p);
                        scores.add(scoreOrder.get(i));
                    }
                }
                if (!products.isEmpty()) {
                    Double topClipCosine = clipHits.get(0).getClipScore();
                    System.out.println("✅ Hybrid CLIP hit: " + products.size()
                            + " product(s), topRRF=" + (scores.isEmpty() ? "-" : scores.get(0))
                            + " topCLIP=" + topClipCosine);

                    // Low-confidence guard: cosine < 0.65 AND ≤ 2 results → likely unrelated image
                    // → fall through to Stage 2 (tag/vector fallback) instead of returning bad results
                    // Thresholds tuned for jina-clip-v2 (cosine range higher than CLIP-B/32)
                    boolean tooLowConf = topClipCosine != null && topClipCosine < 0.65 && products.size() <= 2;
                    if (tooLowConf) {
                        System.out.println("⚠️ CLIP low-confidence (cosine=" + topClipCosine
                                + ", size=" + products.size() + ") → skip to Stage 2");
                        // fall through
                    } else {
                        // Exact = pHash near-dup (already in pinnedPid) OR CLIP cosine ≥ 0.90.
                        // When CLIP is high-conf, its #0 is already the exact item — just label it.
                        boolean clipExact = topClipCosine != null && topClipCosine >= 0.90;
                        if (pinnedPid == null && clipExact && !products.isEmpty()) {
                            pinnedPid = products.get(0).getId();
                            pinnedDto = products.get(0);     // keep ref so the type gate can't drop it
                        }

                        // Category gate (fixes áo→nón/giày, váy→áo/túi): keep only candidates of the
                        // same productType as the image. ML retrieve is image-only → pool is
                        // cross-category, and CLIP visual-sim alone confuses e.g. white shirt vs white
                        // shoe. Reuses the text-path matcher (handles skirt-filed-under-Shorts). Relaxes
                        // to the unfiltered pool only if the gate would empty it (Gemini mis-parse guard).
                        String normType = normalizeAiProductType(parsedProductType);
                        if (normType != null) {
                            StrictTypeFilterResult gated = strictTypeFilter(products, scores, normType, categoryService);
                            if (!gated.products().isEmpty()) {
                                products = new ArrayList<>(gated.products());
                                scores = new ArrayList<>(gated.scores());
                                System.out.println("🔎 type gate '" + normType + "' → " + products.size() + " same-type candidates");
                            } else {
                                System.out.println("⚠️ type gate '" + normType + "' empty → keep unfiltered pool");
                            }
                        }

                        // Step 4-5: soft attribute boost (category/color/brand from the image) +
                        // brand-diversity cap, then pin the exact product back to #0.
                        // Brand source: the recognized in-shop product (pHash/CLIP anchor) carries the
                        // authoritative brand from the DB — reliable for an image-only query even when
                        // Gemini didn't name the brand in its caption (e.g. logo-less shoe photo). Fall
                        // back to Gemini-parsed text only when no product was matched.
                        String anchorBrand = (pinnedDto != null && pinnedDto.getBrand() != null
                                && !pinnedDto.getBrand().isBlank()) ? pinnedDto.getBrand() : null;
                        String queryBrand = anchorBrand != null
                                ? anchorBrand
                                : detectQueryBrand(resolvedMlUid, parsedProductName, parsedTags, caption);
                        System.out.println("🏷️ image queryBrand='" + queryBrand + "' (source="
                                + (anchorBrand != null ? "anchor pid=" + pinnedPid : "gemini-text") + ")");

                        // Brand backfill: the ML pool is purely visual (CLIP only), so same-brand items
                        // that LOOK different from the query — e.g. a canvas Chuck Taylor vs the queried
                        // black zip sneaker — never reach the CLIP top-20; only the exact match does.
                        // Reordering can't surface what ML never returned, so when the brand is known we
                        // pull every same-brand same-type product straight from the catalog and merge it
                        // in (the "more like this / same brand" approach, like SimilarProductRanker).
                        if (queryBrand != null && !queryBrand.isBlank() && normType != null) {
                            Set<Long> present = new HashSet<>();
                            for (ProductResponseDTO p : products) {
                                if (p != null && p.getId() != null) present.add(p.getId());
                            }
                            int added = 0;
                            for (ProductResponseDTO p : getProductsForAiAssistantCached(resolvedMlUid)) {
                                if (p == null || p.getId() == null || present.contains(p.getId())) continue;
                                if (p.getBrand() != null && p.getBrand().equalsIgnoreCase(queryBrand)
                                        && categoryService.productMatchesType(p, normType)) {
                                    products.add(p);
                                    scores.add(0.5);   // brand+type match from catalog, no visual cosine
                                    present.add(p.getId());
                                    added++;
                                }
                            }
                            if (added > 0) {
                                System.out.println("➕ brand backfill: +" + added + " " + queryBrand
                                        + " " + normType + " from catalog (pool=" + products.size() + ")");
                            }
                        }

                        attributeRerank(products, scores, queryBrand, parsedColor, categoryIds, 3);
                        pinExactFirst(products, scores, pinnedPid, pinnedDto, pinnedScore, 10);
                        boolean hasExact = pinnedPid != null;
                        boolean lowConf  = topClipCosine == null || topClipCosine < 0.70;

                        if (sessionId != null) sessionContextStore.setLastProduct(sessionId, products.get(0));
                        result.message = hasExact
                                ? String.format("Tôi nhận ra ảnh này là %s. Đây là sản phẩm khớp, kèm vài gợi ý tương tự:",
                                                products.get(0).getName())
                                : lowConf
                                  ? "Tôi không chắc về sản phẩm trong ảnh, nhưng đây là một số sản phẩm có thể phù hợp:"
                                  : "Tôi tìm thấy một số sản phẩm tương tự với ảnh bạn gửi:";
                        result.data.put("action", "image_search_result");
                        result.data.put("products", products);
                        result.data.put("retrievalScores", scores);
                        result.data.put("extractedTags", parsedTags);
                        if (hasExact) result.data.put("exactProductId", pinnedPid);
                        result.data.put("retrievalSource", hasExact ? "CLIP_HYBRID_PINNED" : "CLIP_HYBRID");
                        _logTotal.run();
                        return result;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Hybrid CLIP image retrieve fallback: " + e.getMessage());
        }

        // ========== Stage 2 Fallback: reuse Gemini-parsed data + vector/tag DB search ==========
        // Runs when ML service is down or returns empty — uses already-parsed context from Stage 1
        try {
            List<String> cleanTags = parsedTags.isEmpty() ? parsedTags : sanitizeTags(parsedTags);
            List<String> expandedTags = expandColorTags(cleanTags);

            String fallbackCategory = (parsedCategory != null && !parsedCategory.isEmpty()) ? parsedCategory : "ALL";
            String fallbackProductName = (parsedProductName != null && !parsedProductName.isEmpty()) ? parsedProductName : "Sản phẩm";
            String fallbackColor = (parsedColor != null) ? parsedColor : "";

            String description = String.format("%s %s %s", fallbackCategory, fallbackProductName, String.join(" ", cleanTags));

            List<Map.Entry<ProductResponseDTO, Double>> scoredResults = productService.findProductsByVectorWithScores(description);
            List<ProductResponseDTO> finalResults = scoredResults.stream().map(Map.Entry::getKey).collect(Collectors.toList());
            List<Double> retrievalScores = scoredResults.stream().map(Map.Entry::getValue).collect(Collectors.toList());

            if (finalResults.isEmpty()) {
                finalResults = productService.findProductsByImage(cleanTags, fallbackCategory);
                retrievalScores = Collections.nCopies(finalResults.size(), 0.0);
            }

            // Pin pHash exact (if any) even on the ML-down fallback path — mutable copies
            // because retrievalScores may be an immutable Collections.nCopies list.
            List<ProductResponseDTO> outProducts = new ArrayList<>(finalResults);
            List<Double> outScores = new ArrayList<>(retrievalScores);
            // Same category gate as Stage 1 — this ML-down fallback also leaked wrong types
            // (váy→áo, giày→túi). Relax to unfiltered only if the gate would empty the list.
            String fbType = normalizeAiProductType(parsedProductType);
            if (fbType != null) {
                StrictTypeFilterResult fbGate = strictTypeFilter(outProducts, outScores, fbType, categoryService);
                if (!fbGate.products().isEmpty()) {
                    outProducts = new ArrayList<>(fbGate.products());
                    outScores = new ArrayList<>(fbGate.scores());
                    System.out.println("🔎 [fallback] type gate '" + fbType + "' → " + outProducts.size() + " same-type");
                }
            }
            pinExactFirst(outProducts, outScores, pinnedPid, pinnedDto, pinnedScore, 10);
            boolean hasExactFb = pinnedPid != null;

            if (!outProducts.isEmpty()) {
                if (sessionId != null) {
                    sessionContextStore.setLastProduct(sessionId, outProducts.get(0));
                    System.out.println("✅ Image Search Fallback: Saved Context for Session " + sessionId + " -> " + outProducts.get(0).getName());
                }

                result.message = hasExactFb
                        ? String.format("Tôi nhận ra ảnh này là %s. Đây là sản phẩm khớp, kèm vài gợi ý tương tự:", outProducts.get(0).getName())
                        : String.format("Dựa trên hình ảnh %s (%s), tôi tìm thấy %d sản phẩm tương tự:",
                                fallbackProductName, fallbackColor, outProducts.size());
                result.data.put("action", "image_search_result");
                result.data.put("products", outProducts);
                result.data.put("extractedTags", cleanTags);
                result.data.put("retrievalScores", outScores);
                if (hasExactFb) result.data.put("exactProductId", pinnedPid);
                result.data.put("retrievalSource", hasExactFb ? "TAG_FALLBACK_PINNED" : "TAG_FALLBACK");
            } else {
                result.message = String.format("Tôi nhận diện được đây là %s màu %s. Tuy nhiên, hiện tại cửa hàng không có sản phẩm nào khớp.", fallbackProductName, fallbackColor);
                result.data.put("extractedTags", expandedTags);
                result.data.put("products", new ArrayList<>());
                result.data.put("action", "image_search_result");
                result.data.put("retrievalSource", "TAG_FALLBACK");
            }

        } catch (Exception e) {
            e.printStackTrace();
            result.message = "Lỗi khi xử lý ảnh: " + e.getMessage();
        }

        try {
            String userId = null;
            if (sessionId != null) {
                UUID uid = resolveCurrentUserId(sessionId);
                if (uid != null) userId = uid.toString();
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("image_search_action", "process_image");
            metadata.put("aiResponseText", result.message);
            metadata.put("modelVersion", MODEL_VERSION);

            if (result.data != null) {
                metadata.put("extracted_tags", result.data.get("extractedTags"));
                if (result.data.get("products") instanceof List) {
                    List<?> products = (List<?>) result.data.get("products");
                    metadata.put("result_count", products.size());
                    // Log IDs for ML training
                    List<Long> retrievedIds = products.stream()
                            .filter(p -> p instanceof ProductResponseDTO)
                            .map(p -> ((ProductResponseDTO) p).getId())
                            .collect(Collectors.toList());
                    metadata.put("retrievedItemIds", retrievedIds);
                    List<String> top5Names = products.stream()
                            .limit(5)
                            .filter(p -> p instanceof ProductResponseDTO)
                            .map(p -> ((ProductResponseDTO) p).getName())
                            .collect(Collectors.toList());
                    metadata.put("top_5_results", top5Names);
                }
                // Log retrieval scores from vector search
                if (result.data.get("retrievalScores") instanceof List) {
                    metadata.put("retrievalScore", result.data.get("retrievalScores"));
                }
            }

            logPublisherService.publishEvent(
                    userId, sessionId,
                    "CHAT_IMAGE_SEARCH",
                    null, null,
                    metadata, "AI_Chatbot"
            );
        } catch (Exception e) {
            System.err.println("Không thể ghi log CHAT_IMAGE_SEARCH: " + e.getMessage());
        }
        _logTotal.run();
        return result;
    }

    /**
     * Pin the in-shop exact product at result #0, dedup, cap at maxSize.
     * exactPid = pHash near-dup or CLIP high-confidence match. Keeps the rest of the
     * list as "similar" backfill so the user sees a full set, exact item first.
     * Mutates {@code products} and {@code scores} in place (pass mutable lists).
     */
    private void pinExactFirst(List<ProductResponseDTO> products, List<Double> scores,
                               Long exactPid, ProductResponseDTO exactDto,
                               double exactScore, int maxSize) {
        if (exactPid == null || products == null) return;
        int found = -1;
        for (int i = 0; i < products.size(); i++) {
            ProductResponseDTO p = products.get(i);
            if (p != null && exactPid.equals(p.getId())) { found = i; break; }
        }
        if (found == 0) return;                       // already first
        if (found > 0) {                              // present lower → move to front
            ProductResponseDTO p = products.remove(found);
            Double s = found < scores.size() ? scores.remove(found) : exactScore;
            products.add(0, p);
            scores.add(0, s);
        } else if (exactDto != null) {                // absent → insert at front
            products.add(0, exactDto);
            scores.add(0, exactScore);
        } else {
            return;                                   // nothing usable to pin
        }
        while (products.size() > maxSize) products.remove(products.size() - 1);
        while (scores.size() > maxSize)   scores.remove(scores.size() - 1);
    }

    /**
     * Step 4-5: re-order the image-search backfill (positions 2..N) by attribute match,
     * then cap per-brand so one brand can't flood the list.
     *
     * <p>Each candidate keeps its CLIP/RRF base score; a soft boost is added for matching the
     * query's category / color / brand (parsed from the uploaded image) only for sort purposes —
     * the returned {@code scores} stay the original CLIP/RRF values, just re-ordered. Visual
     * similarity still dominates; attributes break ties within a similar-visual band, producing
     * the tiers cat+color+brand → cat+color → cat naturally (no rigid slots).
     *
     * <p>Mutates {@code products} and {@code scores} in place. Run BEFORE pinExactFirst.
     */
    private void attributeRerank(List<ProductResponseDTO> products, List<Double> scores,
                                 String queryBrand, String queryColor,
                                 List<Long> categoryIds, int brandCap) {
        int n = products.size();
        if (n <= 1) return;
        final double W_CAT = 0.10, W_COLOR = 0.12, W_BRAND = 0.15;

        Set<Long> catSet = (categoryIds != null) ? new HashSet<>(categoryIds) : Collections.emptySet();
        String qBrand = (queryBrand != null && !queryBrand.isBlank()) ? queryBrand.toLowerCase() : null;
        List<String> qColorTokens = new ArrayList<>();
        if (queryColor != null && !queryColor.isBlank()) {
            for (String t : queryColor.toLowerCase().split("\\s+")) {
                if (!t.isBlank()) qColorTokens.add(t);
            }
        }

        double[] base = new double[n];
        double[] boosted = new double[n];
        boolean[] isQueryBrand = new boolean[n];   // candidate matches the requested/anchor brand
        for (int i = 0; i < n; i++) {
            ProductResponseDTO p = products.get(i);
            base[i] = (i < scores.size() && scores.get(i) != null) ? scores.get(i) : 0.0;
            double add = 0.0;
            if (p != null) {
                if (!catSet.isEmpty() && p.getCategoryId() != null && catSet.contains(p.getCategoryId())) {
                    add += W_CAT;
                }
                if (!qColorTokens.isEmpty()) {
                    String hay = buildAttrHaystack(p);
                    for (String ct : qColorTokens) {
                        if (containsQueryToken(hay, ct)) { add += W_COLOR; break; }
                    }
                }
                if (qBrand != null && p.getBrand() != null && p.getBrand().toLowerCase().equals(qBrand)) {
                    add += W_BRAND;
                    isQueryBrand[i] = true;
                }
            }
            boosted[i] = base[i] + add;
        }

        List<Integer> order = new ArrayList<>(n);
        for (int i = 0; i < n; i++) order.add(i);
        // Requested/anchor brand is the dominant sort key (mirrors the text path's CompositeRanker):
        // all same-brand items lead, then the rest by boosted score. No-op when no brand detected
        // (isQueryBrand all false → falls through to boosted desc, identical to the old behaviour).
        order.sort((a, b) -> {
            if (isQueryBrand[a] != isQueryBrand[b]) return isQueryBrand[a] ? -1 : 1;
            return Double.compare(boosted[b], boosted[a]);
        });

        // Step 5: greedy per-brand cap — overflow appended at the tail (keeps full set, just de-clustered).
        // The requested/anchor brand is EXEMPT from the cap: when the user searches a Converse shoe we
        // want Converse to dominate, so the cap only de-clusters OTHER brands in the backfill.
        List<ProductResponseDTO> keepP = new ArrayList<>(n), overP = new ArrayList<>();
        List<Double> keepS = new ArrayList<>(n), overS = new ArrayList<>();
        Map<String, Integer> brandCount = new HashMap<>();
        for (int oi : order) {
            ProductResponseDTO p = products.get(oi);
            String b = (p != null && p.getBrand() != null) ? p.getBrand().toLowerCase() : "";
            int c = brandCount.getOrDefault(b, 0);
            if (b.isEmpty() || isQueryBrand[oi] || c < brandCap) {
                keepP.add(p); keepS.add(base[oi]);
                if (!b.isEmpty()) brandCount.put(b, c + 1);
            } else {
                overP.add(p); overS.add(base[oi]);
            }
        }
        keepP.addAll(overP); keepS.addAll(overS);

        products.clear(); products.addAll(keepP);
        scores.clear();   scores.addAll(keepS);
    }

    /** Lowercased name + tags of a product, for whole-token color matching. */
    private String buildAttrHaystack(ProductResponseDTO p) {
        StringBuilder sb = new StringBuilder();
        if (p.getName() != null) sb.append(' ').append(p.getName());
        if (p.getTags() != null) {
            for (String t : p.getTags()) {
                if (t != null) sb.append(' ').append(t);
            }
        }
        return sb.toString().toLowerCase();
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

    private String cleanJson(String raw) {
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

    private BookingQuery parseAIResponse(String cleanJson) throws IOException {
        JsonNode jsonNode = mapper.readTree(cleanJson);
        return mapper.readValue(cleanJson, BookingQuery.class);
    }

    private void processSpecialCases(String userInput, String sessionId,
                                     BookingQuery query, List<PitchResponseDTO> allPitches) {

        boolean isPitchRequest = userInput.toLowerCase().contains("sân") || userInput.toLowerCase().contains("pitch");

        // Xử lý sân rẻ nhất/mắc nhất
        if (query.message != null && isPitchRequest) {
            if (query.message.contains("giá rẻ nhất") || query.message.contains("giá mắc nhất")) {
                PitchResponseDTO selectedPitch = findPitchByPrice(allPitches,
                        query.message.contains("giá rẻ nhất"));

                if (selectedPitch != null) {
                    sessionContextStore.setLastPitch(sessionId, selectedPitch);
                    query.data.put("selectedPitch", selectedPitch);
                }
            }
        }

        // Xử lý "sân này" với fallback (Logic này đã check từ khóa 'sân này' nên an toàn)
        if (userInput.contains("sân này")) {
            PitchResponseDTO selectedPitch = sessionContextStore.getLastPitch(sessionId);
            if (selectedPitch == null) {
                selectedPitch = findPitchByContext(userInput, allPitches);
            }

            if (selectedPitch != null) {
                query.data.put("selectedPitch", selectedPitch);
            } else {
                query.message = "Không tìm thấy sân phù hợp. Vui lòng chọn sân trước.";
            }
        }
    }

    private PitchResponseDTO findPitchByPrice(List<PitchResponseDTO> pitches, boolean findCheapest) {
        if (pitches.isEmpty()) return null;

        return findCheapest
                ? pitches.stream().min(Comparator.comparing(PitchResponseDTO::getPrice)).orElse(null)
                : pitches.stream().max(Comparator.comparing(PitchResponseDTO::getPrice)).orElse(null);
    }

    private PitchResponseDTO findPitchByContext(String userInput, List<PitchResponseDTO> pitches) {
        if (userInput.contains("rẻ nhất")) {
            return findPitchByPrice(pitches, true);
        } else if (userInput.contains("mắc nhất")) {
            return findPitchByPrice(pitches, false);
        }
        return null;
    }

    private BookingQuery handleProductQuery(BookingQuery query, String userInput, String sessionId) {
        if (query.data == null) query.data = new HashMap<>();

        UUID userId = resolveCurrentUserId(sessionId);
        List<ProductResponseDTO> products = getProductsForAiAssistantCached(userId);
        String action = (String) query.data.get("action");
        String productName = (String) query.data.get("productName");

        ProductResponseDTO foundProduct = null;

        if ("list_on_sale".equals(action)) {
            List<ProductResponseDTO> onSaleProducts = products.stream()
                    .filter(p -> p.getSalePercent() != null && p.getSalePercent() > 0)
                    .collect(Collectors.toList());

            if (onSaleProducts.isEmpty()) {
                query.message = "Hiện tại shop chưa có sản phẩm nào đang giảm giá.";
            } else {
                query.message = String.format("Hiện tại shop có %d sản phẩm đang giảm giá. Tôi đã gửi danh sách cho bạn 👇", onSaleProducts.size());
                query.data.put("products", onSaleProducts);
            }
            logProductQuery(userId, sessionId, action, productName, query.message, null);
            return query;
        }

        if ("count_on_sale".equals(action)) {
            long count = products.stream()
                    .filter(p -> p.getSalePercent() != null && p.getSalePercent() > 0)
                    .count();
            query.message = "Hiện tại shop có " + count + " sản phẩm đang giảm giá.";
            logProductQuery(userId, sessionId, action, productName, query.message, null);
            return query;
        }

        if ("check_on_sale".equals(action) || "check_sales".equals(action) ||
                "check_sales_context".equals(action) || "product_detail".equals(action) ||
                "check_size".equals(action) || "prepare_order".equals(action)) {

            ProductResponseDTO p = null;

            if (productName != null && !productName.isEmpty()) {
                p = productService.getProductByName(productName);
            }

            if (p == null && sessionId != null) {
                p = sessionContextStore.getLastProduct(sessionId);
            }

            if (p != null) {
                foundProduct = p;

                if ("check_on_sale".equals(action)) {
                    if (p.getSalePercent() != null && p.getSalePercent() > 0) {
                        query.message = String.format("Sản phẩm '%s' đang giảm %d%%, giá chỉ còn %s VNĐ.",
                                p.getName(), p.getSalePercent(), formatMoney(p.getSalePrice()));
                    } else {
                        query.message = String.format("Sản phẩm '%s' hiện KHÔNG có chương trình giảm giá.", p.getName());
                    }
                }
                else if ("check_sales".equals(action) || "check_sales_context".equals(action)) {
                    int totalSold = (p.getTotalSold() != null) ? p.getTotalSold() : 0;
                    String comment = totalSold > 0 ? "Đang được quan tâm." : "Chưa có lượt bán.";
                    query.message = String.format("Sản phẩm '%s' đã bán được tổng cộng %d chiếc. %s", p.getName(), totalSold, comment);
                }
                else if ("product_detail".equals(action)) {
                    String lowerInput = userInput.toLowerCase();
                    boolean isAskingForImage = lowerInput.contains("ảnh") || lowerInput.contains("hình") || lowerInput.contains("photo") || lowerInput.contains("pic");

                    if (isAskingForImage) {
                        if (p.getImageUrl() != null && !p.getImageUrl().isEmpty()) {
                            query.message = String.format("Đây là hình ảnh thực tế của %s. Bạn xem bên dưới nhé 👇", p.getName());
                        } else {
                            query.message = String.format("Sản phẩm %s hiện chưa cập nhật hình ảnh.", p.getName());
                        }
                    } else {
                        StringBuilder detailMsg = new StringBuilder();
                        detailMsg.append(String.format("- Chi tiết: %s\n", p.getName()));
                        detailMsg.append(String.format("- Giá: %s VNĐ\n", formatMoney(p.getPrice())));
                        if (p.getSalePercent() != null && p.getSalePercent() > 0) {
                            detailMsg.append(String.format("- Giảm còn: %s VNĐ\n", formatMoney(p.getSalePrice())));
                        }
                        detailMsg.append(String.format("- Thương hiệu: %s\n", p.getBrand()));
                        detailMsg.append("- Mô tả: " + (p.getDescription() != null ? p.getDescription() : "Đang cập nhật"));
                        query.message = detailMsg.toString();
                    }
                }
                else if ("check_size".equals(action)) {
                    String sizeToCheck = (String) query.data.get("size");

                    if (sizeToCheck == null || sizeToCheck.isEmpty()) {
                        if (p.getVariants() == null || p.getVariants().isEmpty()) {
                            query.message = String.format("Sản phẩm '%s' hiện chưa cập nhật thông tin size.", p.getName());
                        } else {
                            List<String> availableList = new ArrayList<>();
                            for (ProductResponseDTO.VariantDTO v : p.getVariants()) {
                                if (v.getQuantity() > 0) {
                                    availableList.add(String.format("%s (còn %d)", v.getSize(), v.getQuantity()));
                                }
                            }

                            if (availableList.isEmpty()) {
                                query.message = String.format("Tiếc quá, sản phẩm '%s' hiện đã hết sạch hàng các size rồi ạ.", p.getName());
                            } else {
                                String sizeString = String.join(", ", availableList);
                                query.message = String.format("Dạ mẫu '%s' hiện còn các size: %s. Bạn chốt size nào để mình lên đơn nhé?", p.getName(), sizeString);
                            }
                        }
                    }
                    else {
                        boolean foundSize = false;
                        int quantity = 0;
                        if (p.getVariants() != null) {
                            for (ProductResponseDTO.VariantDTO variant : p.getVariants()) {
                                if (variant.getSize().equalsIgnoreCase(sizeToCheck)) {
                                    foundSize = true;
                                    quantity = variant.getQuantity();
                                    break;
                                }
                            }
                        }
                        if (foundSize && quantity > 0) {
                            if (sessionId != null) sessionContextStore.setLastSize(sessionId, sizeToCheck);
                            boolean hasOrderIntent = userInput != null && (
                                    userInput.toLowerCase().contains("đặt") ||
                                            userInput.toLowerCase().contains("mua") ||
                                            userInput.toLowerCase().contains("lấy") ||
                                            userInput.toLowerCase().contains("order")
                            );
                            if (hasOrderIntent) {
                                // Chuyển thẳng sang prepare_order flow
                                int orderQty = extractQuantityFromInput(userInput, query.data.get("quantity"));
                                query.message = String.format("Xác nhận: Bạn muốn đặt %d đôi %s - Size %s. Nhấn nút bên dưới để thanh toán nhé! 👇", orderQty, p.getName(), sizeToCheck);
                                query.data.put("selectedSize", sizeToCheck);
                                query.data.put("selectedQuantity", orderQty);
                                query.data.put("action", "ready_to_order");
                            } else {
                                query.message = String.format("Sản phẩm '%s' size %s hiện đang còn hàng (SL: %d).", p.getName(), sizeToCheck, quantity);
                            }
                        } else {
                            query.message = String.format("Tiếc quá, sản phẩm '%s' size %s hiện đang hết hàng.", p.getName(), sizeToCheck);
                        }
                    }
                }
                else if ("prepare_order".equals(action)) {
                    String sizeToOrder = (String) query.data.get("size");
                    if (sizeToOrder == null && sessionId != null) {
                        sizeToOrder = sessionContextStore.getLastSize(sessionId);
                    }

                    int quantity = extractQuantityFromInput(userInput, query.data.get("quantity"));

                    if (sizeToOrder == null) {
                        query.message = String.format("Bạn muốn đặt size nào cho sản phẩm '%s'? (VD: 'Lấy size 40').", p.getName());
                    } else {
                        query.message = String.format("Xác nhận: Bạn muốn đặt %d đôi %s - Size %s. Nhấn nút bên dưới để thanh toán nhé! 👇", quantity, p.getName(), sizeToOrder);
                        query.data.put("selectedSize", sizeToOrder);
                        query.data.put("selectedQuantity", quantity);
                        query.data.put("action", "ready_to_order");
                    }
                }

            } else {
                query.message = "Xin lỗi, tôi không biết bạn đang hỏi về sản phẩm nào. Vui lòng nói tên sản phẩm cụ thể.";
            }
        }

        else if ("check_stock".equals(action) && productName != null) {
            foundProduct = products.stream()
                    .filter(p -> p.getName().toLowerCase().contains(productName.toLowerCase()))
                    .findFirst().orElse(null);
            if (foundProduct != null) {
                query.message = "Sản phẩm " + foundProduct.getName() + " hiện đang có hàng.";
            } else {
                query.message = "Sản phẩm " + productName + " hiện không tìm thấy.";
            }
        }
        else if ("cheapest_product".equals(action)) {
            // Lấy từ khóa category từ AI (nếu có)
            String categoryKeyword = (String) query.data.get("categoryKeyword");

            // Lọc danh sách nếu có từ khóa
            List<ProductResponseDTO> targetProducts = filterProductsByCategoryOrName(products, categoryKeyword);

            foundProduct = targetProducts.stream()
                    .min(Comparator.comparing(ProductResponseDTO::getPrice))
                    .orElse(null);

            if (foundProduct != null) {
                String displayCat = categoryKeyword;
                if ("Shoes".equalsIgnoreCase(categoryKeyword)) displayCat = "giày";
                else if ("Clothing".equalsIgnoreCase(categoryKeyword)) displayCat = "quần áo";

                String contextMsg = (categoryKeyword != null) ? "thuộc nhóm " + displayCat : "trong cửa hàng";

                query.message = String.format("Sản phẩm %s rẻ nhất là %s với giá %s VNĐ.",
                        contextMsg, foundProduct.getName(), formatMoney(foundProduct.getPrice()));
            } else {
                query.message = (categoryKeyword != null)
                        ? "Không tìm thấy sản phẩm nào thuộc nhóm '" + categoryKeyword + "'."
                        : "Hiện cửa hàng chưa có sản phẩm nào.";
            }
        }
        else if ("most_expensive_product".equals(action)) {
            String categoryKeyword = (String) query.data.get("categoryKeyword");

            List<ProductResponseDTO> targetProducts = filterProductsByCategoryOrName(products, categoryKeyword);

            foundProduct = targetProducts.stream()
                    .max(Comparator.comparing(ProductResponseDTO::getPrice))
                    .orElse(null);

            if (foundProduct != null) {
                String displayCat = categoryKeyword;
                if ("Shoes".equalsIgnoreCase(categoryKeyword)) displayCat = "giày";
                else if ("Clothing".equalsIgnoreCase(categoryKeyword)) displayCat = "quần áo";

                String contextMsg = (categoryKeyword != null) ? "thuộc nhóm " + displayCat : "trong cửa hàng";

                query.message = String.format("Sản phẩm %s đắt nhất là %s với giá %s VNĐ.",
                        contextMsg, foundProduct.getName(), formatMoney(foundProduct.getPrice()));
            } else {
                query.message = (categoryKeyword != null)
                        ? "Không tìm thấy sản phẩm nào thuộc nhóm '" + categoryKeyword + "'."
                        : "Hiện cửa hàng chưa có sản phẩm nào.";
            }
        }
        else if ("best_selling_product".equals(action)) {
            List<ProductResponseDTO> top = productService.getTopSellingProducts(1, userId);
            if (!top.isEmpty()) {
                foundProduct = top.get(0);
                query.message = String.format("Sản phẩm bán chạy nhất là %s.", foundProduct.getName());
            } else {
                query.message = "Chưa có dữ liệu về sản phẩm bán chạy.";
            }
        }
        else if ("max_discount_product".equals(action)) {
            foundProduct = products.stream()
                    .filter(p -> p.getSalePercent() != null && p.getSalePercent() > 0)
                    .max(Comparator.comparing(ProductResponseDTO::getSalePercent))
                    .orElse(null);
            if (foundProduct != null) {
                query.message = String.format("Sản phẩm giảm sâu nhất là %s (-%d%%).", foundProduct.getName(), foundProduct.getSalePercent());
            } else {
                query.message = "Hiện không có sản phẩm nào giảm giá.";
            }
        }

        else if ("search_by_price_range".equals(action)) {
            Object minPriceObj = query.data.get("minPrice");
            Object maxPriceObj = query.data.get("maxPrice");
            String categoryKeyword = (String) query.data.get("categoryKeyword");

            Double minPrice = 0.0;
            Double maxPrice = Double.MAX_VALUE;

            if (minPriceObj != null) {
                if (minPriceObj instanceof Number) {
                    minPrice = ((Number) minPriceObj).doubleValue();
                }
            }

            if (maxPriceObj != null) {
                if (maxPriceObj instanceof Number) {
                    maxPrice = ((Number) maxPriceObj).doubleValue();
                }
            }

            List<ProductResponseDTO> targetProducts = products;
            if (categoryKeyword != null && !categoryKeyword.isEmpty()) {
                targetProducts = filterProductsByCategoryOrName(products, categoryKeyword);
            }

            final Double finalMinPrice = minPrice;
            final Double finalMaxPrice = maxPrice;

            List<ProductResponseDTO> filteredProducts = targetProducts.stream()
                    .filter(p -> {
                        double effectivePrice = p.getPrice();
                        if (p.getSalePercent() != null && p.getSalePercent() > 0 && p.getSalePrice() != null) {
                            effectivePrice = p.getSalePrice();
                        }
                        return effectivePrice >= finalMinPrice && effectivePrice <= finalMaxPrice;
                    })
                    .sorted(Comparator.comparing(p -> {
                        if (p.getSalePercent() != null && p.getSalePercent() > 0 && p.getSalePrice() != null) {
                            return p.getSalePrice();
                        }
                        return p.getPrice();
                    }))
                    .collect(Collectors.toList());

            if (filteredProducts.isEmpty()) {
                String categoryMsg = (categoryKeyword != null && !categoryKeyword.isEmpty())
                        ? " thuộc nhóm " + translateCategory(categoryKeyword)
                        : "";

                String priceMsg = buildPriceRangeMessage(minPrice, maxPrice);

                query.message = String.format(
                        "Không tìm thấy sản phẩm%s trong khoảng giá %s.",
                        categoryMsg,
                        priceMsg
                );

                query.data.put("products", new ArrayList<>());
            } else {
                String categoryMsg = (categoryKeyword != null && !categoryKeyword.isEmpty())
                        ? " " + translateCategory(categoryKeyword)
                        : "";

                String priceMsg = buildPriceRangeMessage(minPrice, maxPrice);

                query.message = String.format(
                        "Tìm thấy %d sản phẩm%s trong khoảng giá %s 👇",
                        filteredProducts.size(),
                        categoryMsg,
                        priceMsg
                );

                query.data.put("products", filteredProducts);
                query.data.put("priceRange", Map.of(
                        "min", minPrice,
                        "max", maxPrice
                ));

                query.data.put("showImage", true);
            }
        }

        if (foundProduct != null) {
            sessionContextStore.setLastProduct(sessionId, foundProduct);

            query.data.put("product", foundProduct);

            boolean shouldShowImage = false;

            if ("product_detail".equals(action) ||
                    "image_search_result".equals(action) ||
                    "prepare_order".equals(action)) {

                shouldShowImage = true;
            }

            query.data.put("showImage", shouldShowImage);
        }

        logProductQuery(userId, sessionId, action, productName, query.message, foundProduct);

        return query;
    }

    private void logProductQuery(UUID userId, String sessionId, String action, String productName, String aiMessage, ProductResponseDTO foundProduct) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("product_action", action);
            metadata.put("product_name_query", productName);
            metadata.put("aiResponseText", aiMessage);
            metadata.put("modelVersion", MODEL_VERSION);

            if (foundProduct != null) {
                metadata.put("found_product_id", foundProduct.getId());
                metadata.put("found_product_name", foundProduct.getName());
                metadata.put("retrievedItemIds", List.of(foundProduct.getId()));
                metadata.put("item_price_snapshot", foundProduct.getPrice());
                metadata.put("item_category", foundProduct.getCategoryName());
            }

            logPublisherService.publishEvent(
                    userId != null ? userId.toString() : null,
                    sessionId,
                    "CHAT_PRODUCT_QUERY",
                    foundProduct != null ? foundProduct.getId().toString() : null,
                    foundProduct != null ? "PRODUCT" : null,
                    metadata, "AI_Chatbot"
            );
        } catch (Exception e) {
            System.err.println("Không thể ghi log CHAT_PRODUCT_QUERY: " + e.getMessage());
        }
    }

    private String buildPriceRangeMessage(Double minPrice, Double maxPrice) {
        if (maxPrice >= Double.MAX_VALUE - 1) {
            return "trên " + formatMoney(minPrice) + " VNĐ";
        } else if (minPrice == 0 || minPrice < 1) {
            return "dưới " + formatMoney(maxPrice) + " VNĐ";
        } else {
            return "từ " + formatMoney(minPrice) + " đến " + formatMoney(maxPrice) + " VNĐ";
        }
    }

    private List<ProductResponseDTO> filterProductsByCategoryOrName(List<ProductResponseDTO> products, String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return products;
        }

        String finalKeyword = keyword.toLowerCase().trim();

        return products.stream()
                .filter(p -> isProductMatchingKeyword(p, finalKeyword))
                .collect(Collectors.toList());
    }

    private boolean isProductMatchingKeyword(ProductResponseDTO p, String keyword) {
        String pName = (p.getName() != null) ? p.getName().toLowerCase() : "";
        String pCat = (p.getCategoryName() != null) ? p.getCategoryName().toLowerCase() : "";

        String pTags = "";
        if (p.getTags() != null && !p.getTags().isEmpty()) {
            pTags = p.getTags().stream()
                    .map(String::toLowerCase)
                    .collect(Collectors.joining(" "));
        }

        if (pName.contains(keyword) || pCat.contains(keyword)) {
            return true;
        }

        if (keyword.equals("accessories") || keyword.contains("phụ kiện")) {
            if (pCat.contains("clothing") || pCat.contains("shirt") || pCat.contains("pant") ||
                    pCat.contains("jacket") || pCat.contains("hoodie") || pCat.contains("dress") ||
                    pCat.contains("shoes") || pCat.contains("footwear") || pCat.contains("sneaker")) {
                if (!pCat.contains("sock")) {
                    return false;
                }
            }
            if (pCat.contains("accessories") || pCat.contains("phụ kiện")) return true;

            boolean isBag = pName.contains("bag") || pName.contains("túi") || pTags.contains("túi");
            boolean isHat = pName.contains("hat") || pName.contains("nón") || pName.contains("mũ") || pTags.contains("mũ");
            boolean isSock = pName.contains("sock") || pName.contains("tất") || pName.contains("vớ");
            boolean isGlove = pName.contains("glove") || pName.contains("găng");

            return isBag || isHat || isSock || isGlove;
        }

        if (keyword.equals("bags and backpacks") || keyword.contains("bag") || keyword.contains("túi")) {
            return pName.contains("bag") || pName.contains("túi") || pName.contains("balo") ||
                    pName.contains("backpack") ||
                    pCat.contains("bag") || pCat.contains("túi") ||
                    pTags.contains("túi") || pTags.contains("balo");
        }

        if (keyword.equals("shoes") || keyword.equals("footwear") || keyword.contains("giày")) {
            return pName.contains("shoe") || pName.contains("giày") || pName.contains("sneaker") ||
                    pCat.contains("shoe") || pCat.contains("footwear");
        }

        if (keyword.equals("clothing") || keyword.contains("quần áo") || keyword.contains("đồ")) {
            return pName.contains("shirt") || pName.contains("áo") ||
                    pName.contains("pant") || pName.contains("quần") ||
                    pName.contains("short") || pName.contains("dress") ||
                    pCat.contains("clothing") || pCat.contains("wear");
        }

        return pTags.contains(keyword);
    }

    private String formatMoney(Double amount) {
        return String.format("%,.0f", amount);
    }

    private BookingQuery handleWeatherQuery(BookingQuery query, String sessionId) {
        if (query.data == null) {
            query.data = new HashMap<>();
        }

        Object cityObj = query.data.get("city");
        String city = (cityObj != null) ? cityObj.toString() : "Hà Nội";

        try {
            String weather = weatherService.getCurrentWeather(city);
            PitchEnvironment env = suggestEnvironmentByWeather(weather);

            List<PitchResponseDTO> suggestedPitches =
                    getAllPitchesCached().stream()
                            .filter(p -> p.getEnvironment() == env)
                            .limit(5)
                            .toList();

            query.message = String.format(
                    "Thời tiết ở %s hiện là %s 🌤️. Tôi gợi ý bạn chọn sân %s.",
                    city,
                    weather,
                    env == PitchEnvironment.INDOOR
                            ? "trong nhà (Indoor)"
                            : "ngoài trời (Outdoor)"
            );

            query.data.clear();
            query.data.put("action", "weather_pitch_suggestion");
            query.data.put("environment", env.name());
            query.data.put("pitches", suggestedPitches);

        } catch (Exception e) {
            e.printStackTrace();
            query.message = "Không thể lấy dữ liệu thời tiết lúc này.";
            query.data.clear();
        }

        try {
            UUID userId = resolveCurrentUserId(sessionId);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("weather_city", city);
            metadata.put("aiResponseText", query.message);
            metadata.put("modelVersion", MODEL_VERSION);
            // Log suggested pitch IDs for ML
            if (query.data.containsKey("pitches") && query.data.get("pitches") instanceof List) {
                List<?> pitches = (List<?>) query.data.get("pitches");
                List<String> pitchIds = pitches.stream()
                        .filter(p -> p instanceof PitchResponseDTO)
                        .map(p -> ((PitchResponseDTO) p).getPitchId().toString())
                        .collect(Collectors.toList());
                metadata.put("retrievedItemIds", pitchIds);
                metadata.put("suggested_pitch_count", pitchIds.size());
            }
            logPublisherService.publishEvent(
                    userId != null ? userId.toString() : null,
                    sessionId,
                    "CHAT_WEATHER_QUERY",
                    null, null,
                    metadata, "AI_Chatbot"
            );
        } catch (Exception ex) {
            System.err.println("Không thể ghi log CHAT_WEATHER_QUERY: " + ex.getMessage());
        }

        return query;
    }

    /**
     * Brand user nêu thẳng trong query, đối chiếu với brand CÓ THẬT trong catalog (không bịa).
     * Trả brand canonical (đúng case DB) để ranker so khớp p.getBrand(); null nếu không có.
     * Ưu tiên brand dài nhất khớp (vd "New Balance" thắng "Balance").
     */
    private String detectQueryBrand(UUID userId, String productName, List<String> tags, String userInput) {
        StringBuilder hayB = new StringBuilder();
        if (productName != null) hayB.append(' ').append(productName);
        if (userInput != null) hayB.append(' ').append(userInput);
        if (tags != null) {
            for (String t : tags) {
                if (t != null) hayB.append(' ').append(t);
            }
        }
        String hay = hayB.toString().toLowerCase();
        if (hay.isBlank()) return null;

        String best = null;
        int bestLen = 0;
        Set<String> seen = new HashSet<>();
        for (ProductResponseDTO p : getProductsForAiAssistantCached(userId)) {
            String brand = p.getBrand();
            if (brand == null || brand.isBlank()) continue;
            String b = brand.toLowerCase();
            if (!seen.add(b)) continue;
            if (containsQueryToken(hay, b) && b.length() > bestLen) {
                best = brand;
                bestLen = b.length();
            }
        }
        return best;
    }

    static boolean containsQueryToken(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isBlank()) return false;
        Pattern pattern = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])"
                + Pattern.quote(needle)
                + "(?![\\p{L}\\p{N}])");
        return pattern.matcher(haystack).find();
    }

    static StrictTypeFilterResult strictTypeFilter(
            List<ProductResponseDTO> products,
            List<Double> scores,
            String productType,
            CategoryService categoryService) {
        List<ProductResponseDTO> filteredProducts = new ArrayList<>();
        List<Double> filteredScores = new ArrayList<>();
        if (products == null || scores == null || productType == null || categoryService == null) {
            return new StrictTypeFilterResult(filteredProducts, filteredScores);
        }
        for (int i = 0; i < products.size(); i++) {
            ProductResponseDTO product = products.get(i);
            if (categoryService.productMatchesType(product, productType)) {
                filteredProducts.add(product);
                filteredScores.add(i < scores.size() ? scores.get(i) : 0.0);
            }
        }
        return new StrictTypeFilterResult(filteredProducts, filteredScores);
    }

    record StrictTypeFilterResult(List<ProductResponseDTO> products, List<Double> scores) {}

    @SuppressWarnings("unchecked")
    private BookingQuery handleRecommendByActivity(BookingQuery query, String sessionId, String userInput) {
        UUID userId = resolveCurrentUserId(sessionId);
        String activity = (String) query.data.get("activity");
        List<String> tags = (List<String>) query.data.get("tags");
        List<String> aiCategories = (List<String>) query.data.get("suggestedCategories");
        String aiProductType = normalizeAiProductType(query.data.get("productType"));

        System.out.println("🟢 recommend_by_activity | userInput='" + userInput + "'");
        System.out.println("   AI parsed: action=" + query.data.get("action")
                + " categoryKeyword=" + query.data.get("categoryKeyword")
                + " activity=" + activity
                + " productType=" + aiProductType
                + " tags=" + tags
                + " suggestedCategories=" + aiCategories);

        if (activity != null && sessionId != null) {
            sessionContextStore.setLastActivity(sessionId, activity);
        }

        if (tags == null || tags.isEmpty()) {
            tags = (activity != null) ? List.of(activity) : List.of("sport");
        }

        // Build description: prepend categoryKeyword × 3 (text repetition → FAISS embed lean về category)
        // Tránh trường hợp query "giày bóng rổ" trả ra Basketball Clothing
        String categoryKeyword = (String) query.data.get("categoryKeyword");
        List<String> descParts = new ArrayList<>();
        if (categoryKeyword != null && !categoryKeyword.isEmpty()) {
            descParts.add(categoryKeyword);
            descParts.add(categoryKeyword);
            descParts.add(categoryKeyword);
        }
        if (activity != null) descParts.add(activity);
        if (tags != null) descParts.addAll(tags);
        String description = String.join(" ", descParts).trim();

        // Try ML retrieve first (Personalized RAG); fallback to local vector search
        String retrievalSource = "VECTOR_LOCAL";
        List<ProductResponseDTO> results = null;
        List<Double> retrievalScores = null;

        List<MLItemResult> mlHits = description.isEmpty()
                ? null
                : mlService.retrieve(description, userId != null ? userId.toString() : null, 20, "PRODUCT");
        System.out.println("🟢 ML retrieve hits: " + (mlHits != null ? mlHits.size() : "null"));
        if (mlHits != null && !mlHits.isEmpty()) {
            results = new ArrayList<>();
            retrievalScores = new ArrayList<>();

            // Batch fetch — avoid N+1 query (each getProductById = 3+ Hibernate queries)
            List<Long> pids = new ArrayList<>();
            for (MLItemResult h : mlHits) {
                try { pids.add(Long.parseLong(h.getItemId())); } catch (NumberFormatException ignored) {}
            }
            Map<Long, ProductResponseDTO> productMap = productService.getProductsByIds(pids, userId);

            int matched = 0, missing = 0;
            for (MLItemResult h : mlHits) {
                Long pid;
                try { pid = Long.parseLong(h.getItemId()); }
                catch (NumberFormatException e) {
                    System.out.println("  ⚠ ML invalid product_id: " + h.getItemId());
                    continue;
                }
                ProductResponseDTO p = productMap.get(pid);
                if (p != null) {
                    results.add(p);
                    retrievalScores.add(h.getFinalScore() != null ? h.getFinalScore() : (h.getScore() != null ? h.getScore() : 0.0));
                    matched++;
                } else {
                    missing++;
                    System.out.println("  ⚠ ML returned product_id=" + pid + " not in DB");
                }
            }
            System.out.println("🟢 ML match: " + matched + " / " + mlHits.size() + " (missing=" + missing + ")");
            if (!results.isEmpty()) {
                retrievalSource = "ML_RAG";
            }
        }

        if (retrievalSource.equals("VECTOR_LOCAL")) {
            List<Map.Entry<ProductResponseDTO, Double>> scoredResults = productService.findProductsByVectorWithScores(description);
            results = scoredResults.stream().map(Map.Entry::getKey).collect(Collectors.toList());
            retrievalScores = scoredResults.stream().map(Map.Entry::getValue).collect(Collectors.toList());
        }

        String detectedType = aiProductType != null
                ? aiProductType
                : categoryService.detectProductTypeFromQuery(userInput, tags, categoryKeyword);
        List<String> resolvedCategories = CategoryMapper.resolveCategories(activity, aiCategories, categoryKeyword);
        System.out.println("   resolved: detectedType=" + detectedType
                + " (source=" + (aiProductType != null ? "AI" : "JAVA") + ")"
                + " resolvedCategories=" + resolvedCategories);

        // Guarantee category candidates: the bi-encoder can miss in-category items whose name
        // is a model code with no category words (e.g. "LeBron XXII EP" → Basketball Shoes).
        // Pull every product in the resolved category straight from DB and union into the
        // candidate set (score 0.0) so the composite ranker can surface them.
        if (resolvedCategories != null && !resolvedCategories.isEmpty()) {
            if (results == null) {
                results = new ArrayList<>();
                retrievalScores = new ArrayList<>();
            } else {
                results = new ArrayList<>(results);
                retrievalScores = new ArrayList<>(retrievalScores);
            }
            Set<Long> haveIds = results.stream()
                    .map(ProductResponseDTO::getId).filter(Objects::nonNull)
                    .collect(Collectors.toCollection(HashSet::new));
            int added = 0;
            for (ProductResponseDTO p : getProductsForAiAssistantCached(userId)) {
                if (p.getId() == null || haveIds.contains(p.getId())) continue;
                // Union by category name OR detected type. Type match (via productMatchesType)
                // covers generic queries: "Shoes" → mọi subcat (Running/Football/Basketball Shoes),
                // nên pool không sụp về 2 khi ML down (circuit open → không có ML hits).
                boolean catHit = p.getCategoryName() != null
                        && resolvedCategories.contains(p.getCategoryName());
                boolean typeHit = detectedType != null && !detectedType.isBlank()
                        && categoryService.productMatchesType(p, detectedType);
                if (catHit || typeHit) {
                    results.add(p);
                    retrievalScores.add(0.0);
                    haveIds.add(p.getId());
                    added++;
                }
            }
            if (added > 0) {
                System.out.println("➕ Category augment: +" + added + " from " + resolvedCategories);
                retrievalSource = "ML_RAG+CAT";
            }
        }

        // Composite rank + strict type filter (e.g. "giày đá bóng" → chỉ SHOES, không fill quần tier 3/4)
        if (results != null && !results.isEmpty()) {
            // sexPref derive from tags
            String sexPrefForCtx = null;
            List<String> tagsLowerCtx = tags == null ? Collections.emptyList()
                    : tags.stream().map(t -> t == null ? "" : t.toLowerCase()).toList();
            if (tagsLowerCtx.contains("nữ") || tagsLowerCtx.contains("nu") || tagsLowerCtx.contains("women") || tagsLowerCtx.contains("woman")) {
                sexPrefForCtx = "WOMEN";
            } else if (tagsLowerCtx.contains("nam") || tagsLowerCtx.contains("men") || tagsLowerCtx.contains("man")) {
                sexPrefForCtx = "MEN";
            }

            // B.3: brand preference từ MongoDB user history (top 3)
            List<String> topBrands = userService.getUserTopBrands(userId, 3);

            // Brand user nêu thẳng trong query (vd "balo adidas") — match brand có thật trong catalog.
            // Khi có → ranker ưu tiên brand này và bỏ qua topBrands lịch sử (xem CompositeRanker).
            String queryBrand = detectQueryBrand(userId, (String) query.data.get("productName"), tags, userInput);

            boolean strictType = detectedType != null && !detectedType.isBlank();
            com.example.FieldFinder.ai.ranking.RankingContext ctx =
                    com.example.FieldFinder.ai.ranking.RankingContext.builder()
                            .productType(detectedType)
                            .activity(activity)
                            .genderPref(sexPrefForCtx)
                            .topBrands(topBrands)
                            .queryBrand(queryBrand)
                            .activityCats(new HashSet<>(resolvedCategories))
                            .strictProductType(strictType)
                            .build();

            List<Map.Entry<ProductResponseDTO, Double>> ranked =
                    compositeRanker.rank(results, retrievalScores, ctx);

            results = ranked.stream().map(Map.Entry::getKey).collect(Collectors.toList());
            retrievalScores = ranked.stream().map(Map.Entry::getValue).collect(Collectors.toList());

            if (strictType) {
                StrictTypeFilterResult filtered =
                        strictTypeFilter(results, retrievalScores, detectedType, categoryService);
                results = filtered.products();
                retrievalScores = filtered.scores();
                System.out.println("🔒 Strict type filter (" + detectedType + "): " + results.size() + " products");
            }
        }

        if ((results == null || results.isEmpty()) && !resolvedCategories.isEmpty()) {
            final String typeForFallback = detectedType;
            results = getProductsForAiAssistantCached(userId).stream()
                    .filter(p -> p.getCategoryName() != null &&
                            resolvedCategories.contains(p.getCategoryName()))
                    .filter(p -> typeForFallback == null
                            || categoryService.productMatchesType(p, typeForFallback))
                    .limit(12)
                    .toList();
            retrievalScores = Collections.nCopies(results.size(), 0.0); // No vector scores for category fallback
        }

        // Last resort: catalog-wide name/type scan. Surfaces items mis-filed by category,
        // e.g. skirts filed under "Shorts" but named "…Skirt" → khớp DRESS qua tên (productMatchesType 0a).
        if ((results == null || results.isEmpty()) && detectedType != null && !detectedType.isBlank()) {
            final String typeForScan = detectedType;
            results = getProductsForAiAssistantCached(userId).stream()
                    .filter(p -> categoryService.productMatchesType(p, typeForScan))
                    .limit(12)
                    .toList();
            retrievalScores = Collections.nCopies(results.size(), 0.0);
        }

        if (results == null || results.isEmpty()) {
            query.message = "Hiện tại shop chưa có sản phẩm phù hợp hoạt động này 😢";
            query.data.put("products", List.of());
            query.data.put("groupedProducts", Map.of());
            query.data.put("action", "recommend_by_activity");
            query.data.put("showImage", false);
            return query;
        }

        if (activity != null && !activity.isBlank() && !"null".equalsIgnoreCase(activity)) {
            query.message = String.format("Với hoạt động %s, bạn có thể tham khảo các sản phẩm sau 👇", activity);
        } else {
            query.message = "Bạn có thể tham khảo các sản phẩm sau 👇";
        }

        Map<String, List<Map<String, Object>>> groupedProducts = new LinkedHashMap<>();

        for (ProductResponseDTO p : results) {
            Map<String, Object> item = new HashMap<>();
            item.put("product", p);

            Map<String, Object> facts = new HashMap<>();
            facts.put("activity", activity);
            facts.put("category", p.getCategoryName());
            facts.put("description", p.getDescription());
            facts.put("tags", p.getTags());
            facts.put("brand", p.getBrand());
            facts.put("price", p.getPrice());
            facts.put("salePercent", p.getSalePercent());
            facts.put("totalSold", p.getTotalSold());
            facts.put("stock", p.getStockQuantity());

            item.put("facts", facts);

            String categoryKey = p.getCategoryName() != null ? p.getCategoryName() : "OTHER";
            groupedProducts.computeIfAbsent(categoryKey, k -> new ArrayList<>()).add(item);
        }

        query.data.put("groupedProducts", groupedProducts);
        query.data.put("products", results);
        query.data.put("explainContext", Map.of("style", "sales_consultant", "maxReasonLength", 25));
        query.data.put("action", "recommend_by_activity");
        query.data.put("showImage", true);

        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("activity", activity);
            metadata.put("tags", tags);
            metadata.put("aiResponseText", query.message);
            metadata.put("modelVersion", MODEL_VERSION);
            metadata.put("result_count", results.size());
            // Log retrievedItemIds + scores for ML
            List<Long> retrievedIds = results.stream()
                    .map(ProductResponseDTO::getId)
                    .collect(Collectors.toList());
            metadata.put("retrievedItemIds", retrievedIds);
            metadata.put("retrievalScore", retrievalScores);
            metadata.put("retrievalSource", retrievalSource);
            logPublisherService.publishEvent(
                    userId != null ? userId.toString() : null,
                    sessionId,
                    "CHAT_ACTIVITY_RECOMMEND",
                    null, null,
                    metadata, "AI_Chatbot"
            );
        } catch (Exception e) {
            System.err.println("Không thể ghi log CHAT_ACTIVITY_RECOMMEND: " + e.getMessage());
        }

        return query;
    }

    public BookingQuery parseBookingInput(String userInput, String sessionId) throws IOException, InterruptedException {
        return parseBookingInput(userInput, sessionId, null, null);
    }

    public BookingQuery parseBookingInput(String userInput, String sessionId, Double userLat, Double userLng) throws IOException, InterruptedException {
        if (isGreeting(userInput)) {
            BookingQuery query = new BookingQuery();
            query.message = "Xin chào! Tôi có thể giúp bạn đặt sân bóng hoặc tìm kiếm sản phẩm thể thao (giày, áo...).";
            query.slotList = new ArrayList<>();
            query.pitchType = "ALL";
            query.data = new HashMap<>();
            return query;
        }

        List<PitchResponseDTO> allPitches = getAllPitchesCached();
        String finalPrompt = buildSystemPrompt(allPitches);

        BookingQuery query;
        try {
            String cleanJson = callGeminiAPI(userInput, finalPrompt);
            System.out.println("🟢 Gemini parsed JSON: " + (cleanJson != null && cleanJson.length() > 800 ? cleanJson.substring(0, 800) + "..." : cleanJson));
            query = parseAIResponse(cleanJson);
        } catch (IOException e) {
            System.err.println("❌ Lỗi gọi Gemini trong parseBookingInput: " + e.getMessage());
            BookingQuery fallback = new BookingQuery();
            fallback.message = "Hệ thống AI tạm thời bận, bạn vui lòng thử lại sau ít phút nhé.";
            fallback.slotList = new ArrayList<>();
            fallback.pitchType = "ALL";
            fallback.data = new HashMap<>();
            return fallback;
        }

        if (query.slotList == null) query.slotList = new ArrayList<>();
        if (query.pitchType == null) query.pitchType = "ALL";
        if (query.data == null) query.data = new HashMap<>();

        if (query.data != null && query.data.containsKey("action")) {
            String action = (String) query.data.get("action");
            String productName = (String) query.data.get("productName");
            Object activityObj = query.data.get("activity");
            Object tagsObj = query.data.get("tags");

            // Override: nếu Gemini set activity nhưng action = price_range với min=max=0 → đó là intent recommend
            boolean hasActivity = activityObj != null && !((String) activityObj).isBlank();
            boolean hasNonemptyTags = tagsObj instanceof List<?> && !((List<?>) tagsObj).isEmpty();
            Number minP = (Number) query.data.getOrDefault("minPrice", 0);
            Number maxP = (Number) query.data.getOrDefault("maxPrice", 0);
            boolean priceRangeEmpty = minP.doubleValue() <= 0 && maxP.doubleValue() <= 0;
            if (hasActivity && (action == null || ("search_by_price_range".equals(action) && priceRangeEmpty))) {
                action = "recommend_by_activity";
                query.data.put("action", "recommend_by_activity");
            }

            if (action == null) {
                if (productName != null && !productName.isEmpty()) {
                    action = "check_stock";
                    query.data.put("action", "check_stock");
                } else if (hasActivity || hasNonemptyTags) {
                    action = "recommend_by_activity";
                    query.data.put("action", "recommend_by_activity");
                } else {
                    return query;
                }
            }

            if ("get_weather".equals(action)) {
                return handleWeatherQuery(query, sessionId);
            }
            if ("recommend_by_activity".equals(action)) {
                return handleRecommendByActivity(query, sessionId, userInput);
            }
            if ("list_pitches".equals(action) || "recommend_pitch".equals(action)
                    || "count_pitches_by_type".equals(action)
                    || "check_pitch_availability".equals(action) || "book_pitch".equals(action)
                    || "list_my_bookings".equals(action) || "cheapest_pitch".equals(action)
                    || "most_expensive_pitch".equals(action)) {
                return handlePitchQuery(query, userInput, sessionId, allPitches, userLat, userLng);
            }
            if (action.contains("product") || action.contains("stock") ||
                    action.contains("sales") || action.contains("sale") ||
                    action.contains("size") || action.contains("order") ||
                    action.contains("price") ||
                    "search_by_price_range".equals(action) ||
                    "cheapest_product".equals(action) ||
                    "most_expensive_product".equals(action)) {

                return handleProductQuery(query, userInput, sessionId);
            }
        }

        boolean isBookingRequest = query.bookingDate != null || !query.slotList.isEmpty() || !"ALL".equals(query.pitchType);

        if (isBookingRequest && query.data.get("action") == null) {
            PitchEnvironment requestedEnvironment = detectEnvironmentFromInput(userInput);

            List<PitchResponseDTO> matchedPitches = allPitches.stream()
                    .filter(p -> {
                        if (!"ALL".equals(query.pitchType)) {
                            if (!p.getType().name().equalsIgnoreCase(query.pitchType)) {
                                return false;
                            }
                        }
                        if (requestedEnvironment != null) {
                            return p.getEnvironment() == requestedEnvironment;
                        }
                        return true;
                    })
                    .collect(Collectors.toList());

            query.data.put("matchedPitches", matchedPitches);

            if (matchedPitches.isEmpty()) {
                String envMsg = requestedEnvironment != null ? " " + formatEnvironment(requestedEnvironment) : "";
                query.message = String.format("Rất tiếc, tôi không tìm thấy sân%s %s nào phù hợp trong hệ thống.", envMsg, formatPitchType(query.pitchType));
            } else {
                if (query.message == null || query.message.isEmpty()) {
                    String dateStr = query.bookingDate != null ? " ngày " + query.bookingDate : "";
                    String timeStr = !query.slotList.isEmpty() ? " khung giờ " + query.slotList : "";
                    String envStr = requestedEnvironment != null ? " " + formatEnvironment(requestedEnvironment) : "";
                    query.message = String.format("Đã tìm thấy %d sân%s %s phù hợp%s%s. Bạn xem danh sách bên dưới nhé 👇", matchedPitches.size(), envStr, formatPitchType(query.pitchType), dateStr, timeStr);
                }
            }
        }

        processSpecialCases(userInput, sessionId, query, allPitches);

        if (query.message == null || query.message.isBlank()) {
            query.message = "Mình chưa hiểu rõ yêu cầu. Bạn muốn tìm sân bóng, đặt sân, hay mua sản phẩm thể thao? Ví dụ: \"cho xem các sân 5 người\" hoặc \"giày rẻ nhất\".";
        }

        return query;
    }

    private BookingQuery handlePitchQuery(BookingQuery query, String userInput, String sessionId, List<PitchResponseDTO> allPitches, Double userLat, Double userLng) {
        if (query.data == null) query.data = new HashMap<>();
        if (query.slotList == null) query.slotList = new ArrayList<>();
        if (query.pitchType == null) query.pitchType = "ALL";

        String action = (String) query.data.get("action");
        // "gần tôi" without an explicit listing action -> treat as a personalized recommendation.
        if (query.nearMe && (action == null || "list_pitches".equals(action))) {
            action = "recommend_pitch";
        }
        PitchEnvironment env = null;
        if (query.environment != null) {
            try { env = PitchEnvironment.valueOf(query.environment); } catch (Exception ignored) {}
        }
        if (env == null) env = detectEnvironmentFromInput(userInput);
        final PitchEnvironment requestedEnvironment = env;

        List<PitchResponseDTO> matched = allPitches.stream()
                .filter(p -> "ALL".equals(query.pitchType) || p.getType().name().equalsIgnoreCase(query.pitchType))
                .filter(p -> requestedEnvironment == null || p.getEnvironment() == requestedEnvironment)
                .collect(Collectors.toList());

        // Hard filter theo khu vực user nêu (vd "sân 5 ở Gò Vấp") -> chỉ giữ sân có địa chỉ khớp.
        final String areaFilter = (query.location != null && !query.location.isBlank()) ? query.location.trim().toLowerCase() : null;
        if (areaFilter != null) {
            matched = matched.stream()
                    .filter(p -> p.getAddress() != null && p.getAddress().toLowerCase().contains(areaFilter))
                    .collect(Collectors.toList());
        }

        boolean isAvailabilityCheck = "book_pitch".equals(action) || "check_pitch_availability".equals(action);
        if (isAvailabilityCheck && query.bookingDate != null && !query.slotList.isEmpty()) {
            try {
                LocalDate date = LocalDate.parse(query.bookingDate);
                matched = matched.stream().filter(p -> {
                    List<Integer> booked = bookingService.getBookedTimeSlots(p.getPitchId(), date);
                    return query.slotList.stream().noneMatch(booked::contains);
                }).collect(Collectors.toList());
            } catch (Exception e) {}
        }

        String envStr = requestedEnvironment != null ? " " + formatEnvironment(requestedEnvironment) : "";
        String typeStr = formatPitchType(query.pitchType);

        switch (action) {
            case "count_pitches_by_type": {
                long five = allPitches.stream().filter(p -> p.getType().name().equals("FIVE_A_SIDE")).count();
                long seven = allPitches.stream().filter(p -> p.getType().name().equals("SEVEN_A_SIDE")).count();
                long eleven = allPitches.stream().filter(p -> p.getType().name().equals("ELEVEN_A_SIDE")).count();
                if (!"ALL".equals(query.pitchType)) {
                    query.message = String.format("Hệ thống đang có %d %s.", matched.size(), typeStr);
                } else {
                    query.message = String.format("Hệ thống có: %d sân 5, %d sân 7, %d sân 11.", five, seven, eleven);
                }
                Map<String, Long> counts = new HashMap<>();
                counts.put("FIVE_A_SIDE", five);
                counts.put("SEVEN_A_SIDE", seven);
                counts.put("ELEVEN_A_SIDE", eleven);
                query.data.put("pitchCounts", counts);
                query.data.put("matchedPitches", matched);
                break;
            }
            case "cheapest_pitch":
            case "most_expensive_pitch": {
                boolean cheapest = "cheapest_pitch".equals(action);
                boolean typeSpecified = !"ALL".equals(query.pitchType);
                List<PitchResponseDTO> searchPool = typeSpecified ? matched : allPitches;
                if (typeSpecified && requestedEnvironment == null) {
                    searchPool = allPitches.stream()
                            .filter(p -> p.getType().name().equalsIgnoreCase(query.pitchType))
                            .collect(Collectors.toList());
                }
                if (searchPool.isEmpty()) {
                    query.message = typeSpecified
                            ? String.format("Không tìm thấy%s %s nào trong hệ thống.", envStr, typeStr)
                            : "Hệ thống hiện chưa có sân nào.";
                    break;
                }
                PitchResponseDTO picked = findPitchByPrice(searchPool, cheapest);
                if (picked == null) {
                    query.message = "Không xác định được sân có giá phù hợp.";
                    break;
                }
                if (sessionId != null) sessionContextStore.setLastPitch(sessionId, picked);
                String scopeLabel = typeSpecified ? (" " + typeStr + envStr).trim() : " (tất cả loại sân)";
                query.message = String.format("Sân %s%s là \"%s\" - %s VNĐ (%s).",
                        cheapest ? "rẻ nhất" : "mắc nhất",
                        scopeLabel.isEmpty() ? "" : " " + scopeLabel.trim(),
                        picked.getName(),
                        picked.getPrice(),
                        picked.getAddress());
                query.data.put("pitch", picked);
                query.data.put("suggestedPitch", picked);
                query.data.put("showBookingButton", true);
                break;
            }
            case "check_pitch_availability": {
                if (matched.isEmpty()) {
                    if (query.bookingDate != null && !query.slotList.isEmpty()) {
                        query.message = String.format("Ngày %s: Rất tiếc, các%s %s đều đã được đặt kín trong khung giờ %s.", query.bookingDate, envStr, typeStr, query.slotList);
                    } else {
                        query.message = String.format("Không có%s %s nào phù hợp để kiểm tra.", envStr, typeStr);
                    }
                    break;
                }
                if (query.bookingDate == null) {
                    query.message = "Bạn muốn kiểm tra sân trống ngày nào? Vui lòng cho mình biết ngày (vd: " + LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).toString() + ").";
                    break;
                }
                try {
                    LocalDate date = LocalDate.parse(query.bookingDate);
                    List<Integer> desired = query.slotList.isEmpty() ? null : query.slotList;
                    List<Map<String, Object>> availability = new ArrayList<>();
                    List<PitchResponseDTO> finalMatched = new ArrayList<>();
                    for (PitchResponseDTO p : matched) {
                        List<Integer> booked = bookingService.getBookedTimeSlots(p.getPitchId(), date);
                        List<Integer> freeSlots = new ArrayList<>();
                        for (int s = 1; s <= 18; s++) if (!booked.contains(s)) freeSlots.add(s);
                        List<Integer> checkSlots = desired != null ? desired : freeSlots;
                        List<Integer> freeInRequested = checkSlots.stream().filter(freeSlots::contains).collect(Collectors.toList());
                        if (freeInRequested.isEmpty()) continue;

                        Map<String, Object> item = new HashMap<>();
                        item.put("pitchId", p.getPitchId());
                        item.put("name", p.getName());
                        item.put("address", p.getAddress());
                        item.put("availableSlots", freeInRequested);
                        availability.add(item);
                        finalMatched.add(p);
                    }
                    query.data.put("availability", availability);
                    query.data.put("matchedPitches", finalMatched);
                    long avail = finalMatched.size();
                    String slotStr = desired != null ? " khung giờ " + desired : "";
                    if (avail == 0) {
                        query.message = String.format("Ngày %s%s: Rất tiếc, các%s %s đều đã kín lịch.", query.bookingDate, slotStr, envStr, typeStr);
                    } else {
                        query.message = String.format("Ngày %s: có %d%s %s còn slot trống%s. Xem chi tiết bên dưới 👇",
                                query.bookingDate, avail, envStr, typeStr, slotStr);
                    }
                } catch (Exception e) {
                    query.message = "Ngày bạn cung cấp không hợp lệ. Vui lòng nhập theo dạng yyyy-MM-dd.";
                }
                break;
            }
            case "book_pitch": {
                if (matched.isEmpty()) {
                    if (query.bookingDate != null && !query.slotList.isEmpty()) {
                        query.message = String.format("Rất tiếc,%s %s đều đã kín lịch trong khung giờ %s ngày %s.", envStr, typeStr, query.slotList, query.bookingDate);
                    } else {
                        query.message = String.format("Không tìm thấy%s %s nào phù hợp để đặt.", envStr, typeStr);
                    }
                    break;
                }
                PitchResponseDTO target = null;
                if (sessionId != null && sessionContextStore.getLastPitch(sessionId) != null) {
                    PitchResponseDTO cached = sessionContextStore.getLastPitch(sessionId);
                    if (matched.stream().anyMatch(p -> p.getPitchId().equals(cached.getPitchId()))) {
                        target = cached;
                    }
                }
                if (target == null) {
                    String lowerInput = userInput.toLowerCase();
                    for (PitchResponseDTO pitch : matched) {
                        if (lowerInput.contains(pitch.getName().toLowerCase())) {
                            target = pitch;
                            break;
                        }
                    }
                }

                // Khi user không chỉ định cụ thể tên sân → show list (availability-style)
                // để user chọn sân muốn đặt, thay vì BE tự pick sân đầu tiên.
                if (target == null && query.bookingDate != null && !query.slotList.isEmpty()) {
                    try {
                        LocalDate date = LocalDate.parse(query.bookingDate);
                        List<Integer> desired = query.slotList;
                        List<Map<String, Object>> availability = new ArrayList<>();
                        List<PitchResponseDTO> finalMatched = new ArrayList<>();
                        for (PitchResponseDTO p : matched) {
                            List<Integer> booked = bookingService.getBookedTimeSlots(p.getPitchId(), date);
                            List<Integer> freeInRequested = desired.stream().filter(s -> !booked.contains(s)).collect(Collectors.toList());
                            if (freeInRequested.size() != desired.size()) continue; // cần đủ slot yêu cầu mới list

                            Map<String, Object> item = new HashMap<>();
                            item.put("pitchId", p.getPitchId());
                            item.put("name", p.getName());
                            item.put("address", p.getAddress());
                            item.put("availableSlots", freeInRequested);
                            availability.add(item);
                            finalMatched.add(p);
                        }
                        if (finalMatched.isEmpty()) {
                            query.message = String.format("Rất tiếc, các%s %s đều không còn trống đủ khung giờ %s ngày %s.",
                                    envStr, typeStr, query.slotList, query.bookingDate);
                        } else {
                            int minSlot = Collections.min(query.slotList);
                            int maxSlot = Collections.max(query.slotList);
                            int startHour = minSlot + 5;
                            int endHour = maxSlot + 6;
                            query.message = String.format("Có %d%s %s trống từ %dh đến %dh ngày %s. Chọn sân bên dưới để đặt 👇",
                                    finalMatched.size(), envStr, typeStr, startHour, endHour, query.bookingDate);
                            query.data.put("availability", availability);
                            query.data.put("matchedPitches", finalMatched);
                            query.data.put("pendingBooking", true);
                            query.data.put("showBookingButton", true);
                            query.data.put("bookingDate", query.bookingDate);
                            query.data.put("slotList", query.slotList);
                        }
                    } catch (Exception e) {
                        query.message = "Ngày bạn cung cấp không hợp lệ. Vui lòng nhập theo dạng yyyy-MM-dd.";
                    }
                    break;
                }

                if (target == null) target = matched.get(0);

                if (query.bookingDate != null && !query.slotList.isEmpty()) {
                    int minSlot = Collections.min(query.slotList);
                    int maxSlot = Collections.max(query.slotList);
                    int startHour = minSlot + 5;
                    int endHour = maxSlot + 6;
                    query.message = String.format("Mình đã giúp bạn chuẩn bị thông tin đặt sân \"%s\" từ %dh đến %dh ngày %s, bạn vui lòng nhấn vào đây để tiến hành thanh toán nhé 👇",
                            target.getName(), startHour, endHour, query.bookingDate);
                } else {
                    query.message = String.format("Bạn muốn đặt sân \"%s\" (%s%s) tại %s. Nhấn nút bên dưới để chọn ngày và khung giờ nhé 👇",
                            target.getName(), typeStr, envStr, target.getAddress());
                }

                query.data.put("pendingBooking", true);
                query.data.put("suggestedPitch", target);
                query.data.put("showBookingButton", true);
                if (query.bookingDate != null) query.data.put("bookingDate", query.bookingDate);
                if (!query.slotList.isEmpty()) query.data.put("slotList", query.slotList);
                break;
            }
            case "list_my_bookings": {
                UUID userId = resolveCurrentUserId(sessionId);
                if (userId == null) {
                    query.message = "Bạn cần đăng nhập để xem các đơn đặt sân của mình.";
                    break;
                }
                try {
                    var bookings = bookingService.getBookingsByUser(userId);
                    query.data.put("bookings", bookings);
                    query.message = bookings.isEmpty()
                            ? "Bạn chưa có đơn đặt sân nào."
                            : String.format("Bạn có %d đơn đặt sân. Xem chi tiết bên dưới 👇", bookings.size());
                } catch (Exception e) {
                    query.message = "Không lấy được danh sách đơn đặt sân lúc này, bạn thử lại sau nhé.";
                }
                break;
            }
            case "recommend_pitch": {
                PitchRankResult rr = rankRecommendedPitches(matched, sessionId, query.location, userLat, userLng, query.nearMe, 10);
                List<PitchResponseDTO> ranked = rr.pitches;
                if (ranked.isEmpty()) {
                    query.message = String.format("Hiện chưa có%s %s nào phù hợp để gợi ý cho bạn.", envStr, typeStr);
                } else if (query.message == null || query.message.isEmpty()) {
                    List<String> bits = new ArrayList<>();
                    if (rr.usedProximity) bits.add("gần bạn");
                    if (rr.usedHistory)   bits.add("theo lịch sử đặt/xem");
                    if (rr.usedProfile)   bits.add("hợp sở thích");
                    String basis = bits.isEmpty() ? "" : " (" + String.join(", ", bits) + ")";
                    query.message = String.format("Gợi ý %d sân phù hợp với bạn%s 👇", ranked.size(), basis);
                    if (query.nearMe && !rr.usedProximity) {
                        query.message += "\n(Bật chia sẻ vị trí để mình gợi ý sân gần bạn chính xác hơn nhé.)";
                    }
                }
                query.data.put("matchedPitches", ranked);
                break;
            }
            case "list_pitches":
            default: {
                String areaStr = areaFilter != null ? " ở " + query.location.trim() : "";
                if (matched.isEmpty()) {
                    query.message = String.format("Rất tiếc, không tìm thấy%s %s nào phù hợp%s.", envStr, typeStr, areaStr);
                } else {
                    query.message = String.format("Đã tìm thấy %d%s %s%s. Xem danh sách bên dưới 👇", matched.size(), envStr, typeStr, areaStr);
                }
                query.data.put("matchedPitches", matched);
                break;
            }
        }

        try {
            String userIdStr = null;
            if (sessionId != null) {
                UUID uid = resolveCurrentUserId(sessionId);
                if (uid != null) userIdStr = uid.toString();
            }
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("raw_user_input", userInput);
            metadata.put("pitch_action", action);
            metadata.put("matched_pitch_count", matched.size());
            metadata.put("requested_pitch_type", query.pitchType);
            metadata.put("aiResponseText", query.message);
            metadata.put("modelVersion", MODEL_VERSION);
            if (requestedEnvironment != null) metadata.put("requested_environment", requestedEnvironment.name());
            if (query.bookingDate != null) metadata.put("booking_date", query.bookingDate);
            if (!query.slotList.isEmpty()) metadata.put("slot_list", query.slotList);
            // Log matched pitch IDs for ML
            List<String> matchedPitchIds = matched.stream()
                    .map(p -> p.getPitchId().toString())
                    .collect(Collectors.toList());
            metadata.put("retrievedItemIds", matchedPitchIds);
            logPublisherService.publishEvent(userIdStr, sessionId, "CHAT_PITCH_QUERY", null, null, metadata, "AI_Chatbot");
        } catch (Exception e) {
            System.err.println("Không thể ghi log CHAT_PITCH_QUERY: " + e.getMessage());
        }

        if (query.message == null || query.message.isBlank()) {
            query.message = "Mình đã ghi nhận yêu cầu sân của bạn, nhưng chưa có dữ liệu phù hợp để trả lời.";
        }
        return query;
    }

    /** Result of personalized pitch ranking + which signal groups actually contributed. */
    private static class PitchRankResult {
        final List<PitchResponseDTO> pitches;
        final boolean usedProximity;
        final boolean usedHistory;
        final boolean usedProfile;
        PitchRankResult(List<PitchResponseDTO> p, boolean prox, boolean hist, boolean prof) {
            this.pitches = p; this.usedProximity = prox; this.usedHistory = hist; this.usedProfile = prof;
        }
    }

    /**
     * Personalized pitch recommendation ranking.
     * score = 0.40*proximity + 0.35*history + 0.15*profile + 0.10*explicitArea.
     * History (rebook/viewed/fav-type/fav-area) outweighs profile per product spec
     * (profile is editable, behaviour is stronger signal). Proximity uses request GPS
     * (userLat/userLng) and falls back to saved profile coords. Returns ≤ {@code limit}
     * pitches sorted by score desc — never the full catalog.
     */
    private PitchRankResult rankRecommendedPitches(List<PitchResponseDTO> candidates, String sessionId,
                                                   String explicitLocation, Double userLat, Double userLng,
                                                   boolean nearMe, int limit) {
        if (candidates == null || candidates.isEmpty()) {
            return new PitchRankResult(new ArrayList<>(), false, false, false);
        }

        UUID uid = resolveCurrentUserId(sessionId);
        PitchUserSignals sig = buildPitchUserSignals(uid, userLat, userLng);

        final Double lat = sig.lat;
        final Double lng = sig.lng;
        final boolean hasCoords = lat != null && lng != null;
        final String fArea = (explicitLocation != null && !explicitLocation.isBlank())
                ? explicitLocation.trim().toLowerCase() : null;

        // Flags: which signal groups actually moved the ranking (for the response message).
        boolean[] used = {false, false, false}; // [0]=proximity [1]=history [2]=profile

        List<PitchResponseDTO> ranked = candidates.stream()
                .sorted((a, b) -> Double.compare(
                        scorePitch(b, sig, hasCoords, lat, lng, fArea, used),
                        scorePitch(a, sig, hasCoords, lat, lng, fArea, used)))
                .limit(Math.max(1, limit))
                .collect(Collectors.toList());

        System.out.println("🎯 Pitch reco: candidates=" + candidates.size() + " returned=" + ranked.size()
                + " coords=" + hasCoords + " favType=" + sig.favType + " booked=" + sig.bookedPitchIds.size()
                + " viewed=" + sig.viewedPitchIds.size() + " district=" + sig.district);

        return new PitchRankResult(ranked, used[0], used[1], used[2]);
    }

    private double scorePitch(PitchResponseDTO p, PitchUserSignals sig, boolean hasCoords,
                              Double lat, Double lng, String area, boolean[] used) {
        double score = 0.0;
        String addr = p.getAddress() != null ? p.getAddress().toLowerCase() : "";

        // Proximity (0..1): closer = higher. 0km->1.0, 1km->0.5, 4km->0.2.
        if (hasCoords && p.getLatitude() != null && p.getLongitude() != null) {
            double km = haversineKm(lat, lng, p.getLatitude(), p.getLongitude());
            double prox = 1.0 / (1.0 + km);
            used[0] = true;
            score += 0.40 * prox;
        }

        // History (0..1): rebook > viewed, plus favourite type + favourite area.
        double hist = 0.0;
        if (p.getPitchId() != null && sig.bookedPitchIds.contains(p.getPitchId())) hist = 1.0;
        else if (p.getPitchId() != null && sig.viewedPitchIds.contains(p.getPitchId())) hist = 0.6;
        if (sig.favType != null && p.getType() != null && p.getType().name().equals(sig.favType)) hist += 0.4;
        if (!sig.bookedAreaTokens.isEmpty()) {
            for (String tok : sig.bookedAreaTokens) {
                if (addr.contains(tok)) { hist += 0.3; break; }
            }
        }
        hist = Math.min(1.0, hist);
        if (hist > 0) used[1] = true;
        score += 0.35 * hist;

        // Profile (0..1): district + preferred type (lower weight than history).
        double prof = 0.0;
        if (sig.district != null && !sig.district.isBlank() && addr.contains(sig.district)) prof += 0.6;
        if (sig.prefType != null && p.getType() != null && p.getType().name().equals(sig.prefType)) prof += 0.4;
        prof = Math.min(1.0, prof);
        if (prof > 0) used[2] = true;
        score += 0.15 * prof;

        // Explicit area keyword from the query (mild tie-breaker; usually already hard-filtered).
        if (area != null && addr.contains(area)) score += 0.10;

        return score;
    }

    /** Aggregated personalization signals for a user, computed once per request. */
    private static class PitchUserSignals {
        Double lat, lng;
        String district;
        String prefType;
        Set<UUID> bookedPitchIds = new HashSet<>();
        Set<UUID> viewedPitchIds = new HashSet<>();
        String favType;                                  // most-booked pitch type
        Set<String> bookedAreaTokens = new HashSet<>();  // address keywords from booked pitches
    }

    private PitchUserSignals buildPitchUserSignals(UUID uid, Double reqLat, Double reqLng) {
        PitchUserSignals s = new PitchUserSignals();
        s.lat = reqLat;
        s.lng = reqLng;
        if (uid == null) return s;

        // Profile: district, preferred type, fallback coords.
        try {
            var profile = userService.getUserById(uid);
            if (profile != null) {
                if (profile.getDistrict() != null) s.district = profile.getDistrict().toLowerCase();
                if (profile.getPreferredPitchType() != null) s.prefType = profile.getPreferredPitchType().name();
                if (s.lat == null || s.lng == null) {
                    s.lat = profile.getLatitude();
                    s.lng = profile.getLongitude();
                }
            }
        } catch (Exception e) {
            System.err.println("buildPitchUserSignals profile error: " + e.getMessage());
        }

        // Booking history: booked pitch ids, favourite type, booked-area tokens.
        try {
            Map<UUID, PitchResponseDTO> byId = new HashMap<>();
            for (PitchResponseDTO p : getAllPitchesCached()) {
                if (p.getPitchId() != null) byId.put(p.getPitchId(), p);
            }
            Map<String, Integer> typeCount = new HashMap<>();
            var bookings = bookingService.getBookingsByUser(uid);
            for (var b : bookings) {
                if (b.getPitchId() == null) continue;
                s.bookedPitchIds.add(b.getPitchId());
                PitchResponseDTO p = byId.get(b.getPitchId());
                if (p != null) {
                    if (p.getType() != null) typeCount.merge(p.getType().name(), 1, Integer::sum);
                    if (p.getAddress() != null) {
                        for (String tok : p.getAddress().toLowerCase().split("[,\\-]")) {
                            String t = tok.trim();
                            if (t.length() >= 4) s.bookedAreaTokens.add(t);
                        }
                    }
                }
            }
            s.favType = typeCount.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse(null);
        } catch (Exception e) {
            System.err.println("buildPitchUserSignals booking error: " + e.getMessage());
        }

        // Viewed-but-not-booked pitches from interaction logs (Mongo VIEW_PITCH).
        try {
            s.viewedPitchIds.addAll(loadViewedPitchIds(uid, 50));
        } catch (Exception e) {
            System.err.println("buildPitchUserSignals view error: " + e.getMessage());
        }

        return s;
    }

    /** Recently viewed pitch ids from Mongo interaction logs. Empty if Mongo unavailable. */
    private Set<UUID> loadViewedPitchIds(UUID userId, int limit) {
        Set<UUID> ids = new HashSet<>();
        if (mongoTemplate == null || userId == null) return ids;
        try {
            org.springframework.data.mongodb.core.query.Query q =
                    org.springframework.data.mongodb.core.query.Query.query(
                            org.springframework.data.mongodb.core.query.Criteria.where("userId").is(userId.toString())
                                    .and("eventType").is("VIEW_PITCH")
                                    .and("itemType").is("PITCH"))
                            .with(org.springframework.data.domain.Sort.by(
                                    org.springframework.data.domain.Sort.Direction.DESC, "timestamp"));
            q.limit(limit);
            List<com.example.FieldFinder.entity.log.InteractionLog> logs =
                    mongoTemplate.find(q, com.example.FieldFinder.entity.log.InteractionLog.class);
            for (var l : logs) {
                if (l.getItemId() == null) continue;
                try { ids.add(UUID.fromString(l.getItemId())); } catch (IllegalArgumentException ignored) {}
            }
        } catch (Exception e) {
            System.err.println("loadViewedPitchIds error: " + e.getMessage());
        }
        return ids;
    }

    /** Haversine great-circle distance in km. */
    private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private PitchEnvironment detectEnvironmentFromInput(String userInput) {
        if (userInput == null) return null;
        String input = userInput.toLowerCase();
        if (input.contains("ngoài trời") || input.contains("ngoai troi") || input.contains("outdoor") || input.contains("ngoài") || input.contains("bên ngoài")) return PitchEnvironment.OUTDOOR;
        if (input.contains("trong nhà") || input.contains("trong nha") || input.contains("indoor") || input.contains("trong") || input.contains("có mái") || input.contains("có mái che")) return PitchEnvironment.INDOOR;
        return null;
    }

    private String translateCategory(String categoryKeyword) {
        if (categoryKeyword == null) return "";
        String key = categoryKeyword.toLowerCase().trim();
        if (key.contains("tennis accessories")) return "phụ kiện tennis";
        if (key.contains("running shoes")) return "giày chạy bộ";
        if (key.contains("football shoes")) return "giày đá bóng";
        if (key.contains("basketball shoes")) return "giày bóng rổ";
        if (key.contains("tennis shoes")) return "giày tennis";

        switch (key) {
            case "shoes": case "footwear": return "giày dép";
            case "clothing": return "quần áo";
            case "accessories": return "phụ kiện";
            case "hats and headwears": return "nón/mũ";
            case "socks": return "tất/vớ";
            case "gloves": return "găng tay";
            case "bags and backpacks": return "túi/balo";
            case "jackets and gilets": return "áo khoác";
            case "hoodies and sweatshirts": return "áo hoodie";
            case "pants and leggings": return "quần dài";
            case "shorts": return "quần đùi";
            case "tops and t-shirts": return "áo thun";
            case "gym and training": return "đồ tập gym";
            case "sandals and slides": return "dép/sandal";
            default: return categoryKeyword;
        }
    }

    private String formatEnvironment(PitchEnvironment env) {
        if (env == PitchEnvironment.INDOOR) return "trong nhà";
        else if (env == PitchEnvironment.OUTDOOR) return "ngoài trời";
        return "";
    }

    private String formatPitchType(String type) {
        if (type.equals("FIVE_A_SIDE")) return "sân 5";
        if (type.equals("SEVEN_A_SIDE")) return "sân 7";
        if (type.equals("ELEVEN_A_SIDE")) return "sân 11";
        return type;
    }

    private boolean isGreeting(String s) {
        String t = s.toLowerCase().trim();
        return t.matches("^(hi|hey|hello|hola|halo|alo|yo|chào|xin chào|good morning|good evening|good afternoon)[\\s!?.]*$")
                || t.matches(".*\\b(xin chào|chào bạn|chào shop|hello|good morning|good evening|good afternoon)\\b.*");
    }

    private static final String DATA_ENRICHMENT_SYSTEM_PROMPT = """
        Bạn là chuyên gia quản lý kho hàng thời trang (Inventory Manager).
        Nhiệm vụ: Phân tích hình ảnh sản phẩm và sinh ra danh sách từ khóa (Tags) chi tiết để phục vụ tìm kiếm.
        
        HÃY QUAN SÁT KỸ VÀ TRẢ VỀ JSON CHỨA DANH SÁCH TAGS:
        1. Thương hiệu: Nhìn logo/chữ trên sản phẩm (Nike, Adidas, Puma...).
        2. Dòng sản phẩm: Tên cụ thể (Air Max, Jordan, Ultraboost, Stan Smith...).
        3. Màu sắc: Liệt kê TẤT CẢ màu nhìn thấy (Tiếng Việt + Tiếng Anh). VD: ["trắng", "white", "cam", "orange"].
        4. Đặc điểm hình dáng: 
           - Giày: Cổ cao/thấp, đế air, đế bằng, dây buộc, không dây...
           - Áo/Quần: Tay dài/ngắn, cổ tròn/tim, có mũ...
        5. Chất liệu: Da, vải lưới, nỉ, cotton...
        
        YÊU CẦU OUTPUT JSON:
        {
          "tags": ["danh sách khoảng 15-20 từ khóa, viết thường, bao gồm cả tiếng Anh và tiếng Việt"]
        }
        """;

    public List<String> generateTagsForProduct(String imageUrl) {
        try {
            // Yield to user chat requests — nếu user đang chat thì đợi
            while (enrichmentPaused) {
                Thread.sleep(2000);
            }
            String[] img = downloadImageWithMime(imageUrl);
            if (img == null) return new ArrayList<>();
            String base64Image = img[0];
            String mimeType = img[1];

            ObjectNode rootNode = mapper.createObjectNode();
            ObjectNode systemInstNode = rootNode.putObject("system_instruction");
            systemInstNode.putObject("parts").put("text", DATA_ENRICHMENT_SYSTEM_PROMPT);

            ArrayNode contentsArray = rootNode.putArray("contents");
            ObjectNode userMessage = contentsArray.addObject();
            userMessage.put("role", "user");
            ArrayNode parts = userMessage.putArray("parts");
            parts.addObject().put("text", "Hãy sinh tags cho sản phẩm này.");

            ObjectNode inlineData = parts.addObject().putObject("inline_data");
            inlineData.put("mime_type", mimeType);
            inlineData.put("data", base64Image);

            ObjectNode generationConfig = rootNode.putObject("generationConfig");
            generationConfig.put("temperature", 0.1);
            generationConfig.put("response_mime_type", "application/json");
            generationConfig.put("maxOutputTokens", 1024);
            // Gemini 2.5-flash bật "thinking" theo mặc định → nuốt output budget làm JSON bị cắt
            // (JsonEOFException). Tắt thinking để dành token cho output tags.
            generationConfig.putObject("thinkingConfig").put("thinkingBudget", 0);

            Request request = new Request.Builder()
                    .url(GEMINI_API_URL + GOOGLE_API_KEY)
                    .post(RequestBody.create(mapper.writeValueAsString(rootNode), MediaType.parse("application/json")))
                    .build();

            try (Response response = callWithRetry(request, "Product Tags Enrichment")) {
                String jsonRes = cleanJson(extractGeminiResponse(response.body().string()));
                JsonNode root = mapper.readTree(jsonRes);
                List<String> tags = mapper.convertValue(root.path("tags"), new TypeReference<List<String>>(){});
                return sanitizeTags(tags);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    private PitchEnvironment suggestEnvironmentByWeather(String weather) {
        String w = weather.toLowerCase();
        if (w.contains("mưa") || w.contains("rain") || w.contains("storm") || w.contains("bão") || w.contains("ẩm")) return PitchEnvironment.INDOOR;
        return PitchEnvironment.OUTDOOR;
    }

    /** Gemini Vision chấp nhận: png, jpeg, webp, heic, heif. KHÔNG hỗ trợ avif/gif. */
    private static boolean geminiSupportsMime(String mime) {
        return mime != null && (mime.equals("image/png") || mime.equals("image/jpeg")
                || mime.equals("image/webp") || mime.equals("image/heic") || mime.equals("image/heif"));
    }

    private static String guessMimeFromUrl(String url) {
        String u = url.toLowerCase();
        if (u.contains(".png")) return "image/png";
        if (u.contains(".webp")) return "image/webp";
        if (u.contains(".avif")) return "image/avif";
        if (u.contains(".heic")) return "image/heic";
        if (u.contains(".gif")) return "image/gif";
        return "image/jpeg";
    }

    /** Cloudinary: chèn transform f_jpg để buộc serve JPEG (xử ảnh avif/gif Gemini không nhận). */
    private static String cloudinaryAsJpg(String url) {
        if (url != null && url.contains("res.cloudinary.com") && url.contains("/upload/") && !url.contains("/f_jpg/")) {
            return url.replaceFirst("/upload/", "/upload/f_jpg/");
        }
        return null;
    }

    /** Trả [base64, mimeType] hợp lệ cho Gemini, hoặc null nếu fail. */
    private String[] downloadImageWithMime(String imageUrl) {
        String mimeGuess = guessMimeFromUrl(imageUrl);
        // Nếu định dạng Gemini không nhận (avif/gif) và là Cloudinary → đổi sang JPEG qua URL transform.
        if (!geminiSupportsMime(mimeGuess)) {
            String jpgUrl = cloudinaryAsJpg(imageUrl);
            if (jpgUrl != null) { imageUrl = jpgUrl; mimeGuess = "image/jpeg"; }
        }
        try {
            Request request = new Request.Builder().url(imageUrl)
                    .header("User-Agent", "Mozilla/5.0").build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return null;
                byte[] imageBytes = response.body().bytes();
                String mime = response.header("Content-Type");
                if (mime != null) mime = mime.split(";")[0].trim();
                if (!geminiSupportsMime(mime)) mime = mimeGuess;     // header rỗng/sai → đoán theo URL
                if (!geminiSupportsMime(mime)) {
                    System.err.println("Định dạng ảnh Gemini không hỗ trợ (" + mime + "): " + imageUrl);
                    return null;
                }
                return new String[]{ Base64.getEncoder().encodeToString(imageBytes), mime };
            }
        } catch (Exception e) {
            System.err.println("Không tải được ảnh: " + imageUrl);
            return null;
        }
    }

    private static final String IMAGE_ANALYSIS_SYSTEM_PROMPT = """
        Bạn là chuyên gia thời trang (Sneakerhead).
        Nhiệm vụ: Phân tích ảnh để tìm kiếm sản phẩm.

        1. XÁC ĐỊNH LOẠI SẢN PHẨM (`majorCategory`):
        - `FOOTWEAR` (Giày, Dép), `CLOTHING` (Quần, Áo, Váy), `ACCESSORY` (Balo, Nón, Túi...).

        2. XÁC ĐỊNH LOẠI CỤ THỂ (`productType`) — BẮT BUỘC chọn đúng 1:
        - `SHOES` — giày thể thao, giày tây, sneaker, boot
        - `SANDAL` — dép, sandal
        - `TOP` — áo (T-shirt, polo, hoodie, jacket, sơ mi)
        - `BOTTOM` — quần (short, jeans, kaki, jogger)
        - `DRESS` — váy, đầm
        - `BAG` — balo, túi xách, túi đeo chéo
        - `HAT` — nón, mũ, cap, beanie
        - `OTHER` — phụ kiện khác (kính, găng tay, vớ...)

        3. PHÂN TÍCH MÀU SẮC (RẤT QUAN TRỌNG):
        - Đừng chỉ chọn 1 màu. Hãy liệt kê TẤT CẢ màu sắc nhìn thấy.
        - Phân biệt: Màu chủ đạo (Dominant) và Màu phối (Accent).
        - Ví dụ: Giày trắng logo đỏ -> Tags phải có cả "trắng", "white", "đỏ", "red".
        - Các màu tương đồng: Nếu thấy "kem/cream/beige" -> Hãy thêm tag "trắng/white". Nếu thấy "xanh dương/navy" -> Thêm tag "xanh/blue".

        4. ĐỌC CHỮ (OCR):
        - Cố gắng đọc tên dòng sản phẩm trên thân/lưỡi gà (VD: Air Max, Jordan, Ultraboost).

        YÊU CẦU OUTPUT JSON:
        {
          "majorCategory": "FOOTWEAR",
          "productType": "SHOES",
          "productName": "Tên gợi ý (VD: Nike Air Max 1 White/Orange)",
          "color": "Mô tả màu (VD: Trắng phối Cam)",
          "tags": ["danh sách tags: nike, air max, trắng, white, cam, orange, giày, sneaker..."]
        }
        """;

    /** Chuẩn hóa productType từ Gemini (TOP, top, Shoes → SHOES). */
    private static String normalizeAiProductType(Object raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.toString().trim().toUpperCase(Locale.ROOT);
        if (t.isEmpty() || "NULL".equals(t)) {
            return null;
        }
        return switch (t) {
            case "SHOES", "SHOE", "FOOTWEAR" -> "SHOES";
            case "TOP", "TOPS", "SHIRT", "JERSEY" -> "TOP";
            case "BOTTOM", "BOTTOMS", "PANTS", "SHORTS" -> "BOTTOM";
            case "SANDAL", "SANDALS" -> "SANDAL";
            case "DRESS" -> "DRESS";
            case "BAG", "BAGS", "BACKPACK" -> "BAG";
            case "HAT", "CAP" -> "HAT";
            case "OTHER", "ACCESSORY", "ACCESSORIES" -> "OTHER";
            default -> t.matches("SHOES|TOP|BOTTOM|SANDAL|DRESS|BAG|HAT|OTHER") ? t : null;
        };
    }

    private int extractQuantityFromInput(String userInput, Object rawQty) {
        // Ưu tiên Gemini parse, nếu không có thì dùng regex trên userInput
        if (rawQty instanceof Number) {
            int q = ((Number) rawQty).intValue();
            if (q > 1) return q;
        }
        if (userInput != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d+)\\s*(đôi|cái|chiếc|cặp|pair|x|×)")
                    .matcher(userInput.toLowerCase());
            if (m.find()) return Math.max(1, Integer.parseInt(m.group(1)));
        }
        return 1;
    }

    private static final String SYSTEM_INSTRUCTION = """
        Bạn là trợ lý AI thông minh cho hệ thống FieldFinder (Đặt sân & Shop thể thao).
        Nhiệm vụ: Phân tích câu hỏi người dùng và trả về JSON cấu trúc để Backend xử lý.
        
        CẤU TRÚC JSON TRẢ VỀ:
        {
          "bookingDate": "yyyy-MM-dd" (hoặc null),
          "slotList": [1, 2...] (hoặc []),
          "pitchType": "FIVE_A_SIDE" | "SEVEN_A_SIDE" | "ELEVEN_A_SIDE" | "ALL",
          "message": "thông điệp mặc định" (hoặc null),
          "environment": "INDOOR" | "OUTDOOR" | null,
          "location": "tên khu vực/quận/đường nếu user nêu" (hoặc null),
          "nearMe": true | false,
          "data": {
            "action": "get_weather" | "check_stock" | "check_sales" | "check_size" | "prepare_order" | "list_on_sale" | "count_on_sale" | "max_discount_product" | "best_selling_product" | "search_by_price_range" | "cheapest_product" | "most_expensive_product" | "product_detail" | "recommend_by_activity" | "list_pitches" | "recommend_pitch" | "count_pitches_by_type" | "check_pitch_availability" | "book_pitch" | "list_my_bookings" | "cheapest_pitch" | "most_expensive_pitch" | null,
            "productName": "...",
            "city": "...",
            "size": "...",
            "quantity": 1,
            "categoryKeyword": "...",
            "productType": "SHOES" | "TOP" | "BOTTOM" | "SANDAL" | "DRESS" | "BAG" | "HAT" | "OTHER" | null,
            "minPrice": 0,
            "maxPrice": 0,
            "activity": "...",
            "suggestedCategories": [],
            "tags": [],
            "reasons": {}
          }
        }
        
        ❗️ QUY TẮC XỬ LÝ SÂN:
        - `pitchType`: Loại sân (5, 7, 11 người).
        - `environment`: "INDOOR" (trong nhà/có mái che), "OUTDOOR" (ngoài trời).
        - Giờ (từ 6h sáng đến 24h) được ánh xạ vào slot (1-18) như sau:
          Slot 1: 6h-7h, Slot 2: 7h-8h, Slot 3: 8h-9h, Slot 4: 9h-10h, Slot 5: 10h-11h ... đến Slot 18: 23h-24h.
          CHÚ Ý ĐẶC BIỆT: "từ 7h đến 11h" => `slotList`: [2, 3, 4, 5] (vì 7h-8h là slot 2, 8h-9h là slot 3, 9h-10h là slot 4, 10h-11h là slot 5). "từ 16h đến 18h" => `slotList`: [11, 12].
          LUÔN KIỂM TRA SỐ LƯỢNG SLOT: Số phần tử trong `slotList` PHẢI BẰNG (Giờ kết thúc - Giờ bắt đầu). Ví dụ: từ 11h đến 14h là 14 - 11 = 3 tiếng => CHỈ trả về đúng 3 slot [6, 7, 8]. TUYỆT ĐỐI KHÔNG trả về dư slot!
        - THỜI GIAN HỆ THỐNG: Hôm nay: {{today}}, Ngày mai: {{plus1}}, Năm: {{year}}.
        - "Mỗi loại sân có bao nhiêu sân?" -> data: {"pitchCounts": {"FIVE_A_SIDE": {{fiveASideCount}}, "SEVEN_A_SIDE": {{sevenASideCount}}, "ELEVEN_A_SIDE": {{elevenASideCount}}}}
        - Câu hỏi liệt kê / xem danh sách sân (vd: "có những sân nào", "cho xem danh sách sân", "sân 7 người ngoài trời có không") -> action: "list_pitches", kèm `pitchType` và `environment` nếu có.
        - Câu hỏi đếm số lượng sân (vd: "có bao nhiêu sân 5 người", "mấy sân 7") -> action: "count_pitches_by_type", kèm `pitchType`.
        - Câu hỏi kiểm tra sân trống theo ngày/giờ (vd: "sân 7 còn trống ngày mai 19h", "còn slot nào trống không") -> action: "check_pitch_availability", kèm `bookingDate` (yyyy-MM-dd), `slotList`, `pitchType`.
        - Câu đặt sân (vd: "đặt sân 5 ngày 20/4 slot 13", "book sân 7 chiều mai") -> action: "book_pitch", kèm đầy đủ `pitchType`, `bookingDate`, `slotList`.
        - Câu hỏi sân giá rẻ/đắt nhất:
          + Nếu KHÔNG nêu loại sân cụ thể (vd: "sân rẻ nhất", "tìm sân giá thấp nhất") -> action: "cheapest_pitch" hoặc "most_expensive_pitch", `pitchType`: "ALL" (tìm trên TOÀN BỘ sân, không giới hạn loại).
          + Nếu có nêu loại sân (vd: "sân 5 mắc nhất", "sân 7 rẻ nhất") -> action như trên NHƯNG `pitchType` PHẢI đúng loại ("FIVE_A_SIDE" / "SEVEN_A_SIDE" / "ELEVEN_A_SIDE") để chỉ tìm trong loại đó.
        - Câu hỏi đơn đặt của tôi -> action: "list_my_bookings".
        - VỊ TRÍ / KHU VỰC: nếu user nêu khu vực, quận, phường hoặc tên đường cụ thể (vd "sân 5 ở Gò Vấp", "khu vực quận 1", "sân ở Thủ Đức", "sân gần Bình Thạnh") -> điền `location` = đúng tên khu vực đó (vd "Gò Vấp", "quận 1", "Thủ Đức", "Bình Thạnh"). VẪN giữ `pitchType`/`environment` nếu user có nêu loại sân hoặc môi trường.
        - GẦN TÔI: nếu user nói "gần tôi", "gần đây", "quanh đây", "chỗ tôi", "gần chỗ tôi", "ở gần" (không nêu tên khu vực cụ thể) -> `nearMe`: true (và để `location` = null).
        - GỢI Ý SÂN: nếu user nhờ gợi ý sân CHUNG, không nêu loại/giá/khu vực cụ thể (vd "gợi ý sân giúp mình", "tìm sân cho tôi", "sân nào phù hợp với tôi", "có sân nào hay không", "đề xuất sân") -> action: "recommend_pitch", `pitchType`: "ALL". PHÂN BIỆT với "list_pitches" (user muốn xem TẤT CẢ của một loại cụ thể, vd "cho xem tất cả sân 5", "liệt kê sân 7").
        - VÍ DỤ SÂN:
          + "tìm sân 5 ở Gò Vấp" -> action: "list_pitches", pitchType: "FIVE_A_SIDE", location: "Gò Vấp"
          + "sân 7 ngoài trời khu vực quận 1" -> action: "list_pitches", pitchType: "SEVEN_A_SIDE", environment: "OUTDOOR", location: "quận 1"
          + "tìm sân gần tôi" / "sân nào gần đây" -> action: "recommend_pitch", pitchType: "ALL", nearMe: true
          + "gợi ý sân giúp mình" / "sân nào phù hợp với tôi" -> action: "recommend_pitch", pitchType: "ALL"
          + "cho xem tất cả sân 7" -> action: "list_pitches", pitchType: "SEVEN_A_SIDE"

        ❗️ QUY TẮC XỬ LÝ SẢN PHẨM:
        - Nếu hỏi về giá sản phẩm (rẻ nhất/mắc nhất), dùng action "cheapest_product" hoặc "most_expensive_product".
        - Cung cấp "categoryKeyword" CỤ THỂ NHẤT có thể dựa trên bảng mapping:
          + nón, mũ, cap -> "Hats And Headwears"
          + tất, vớ -> "Socks"
          + balo, túi, backpack -> "Bags And Backpacks"
          + áo khoác, jacket, gilet -> "Jackets And Gilets"
          + hoodie, áo nỉ, sweatshirt -> "Hoodies And Sweatshirts"
          + áo, tee, t-shirt, polo -> "Tops And T-Shirts"
          + quần, pants, leggings, jogger -> "Pants And Leggings"
          + short, quần short -> "Shorts"
          + dép, sandal, slide -> "Sandals And Slides"
          + giày đá banh, giày bóng đá, football boot -> "Football Shoes"
          + giày tennis, tennis shoe -> "Tennis Shoes"
          + giày bóng rổ, basketball shoe -> "Basketball Shoes"
          + giày chạy bộ, running shoe -> "Running Shoes"
          + áo bóng đá, áo đá banh, jersey, áo thi đấu -> categoryKeyword: "Football Clothing" hoặc "Tops And T-Shirts", productType: "TOP"
          + quần bóng đá, quần đá banh -> categoryKeyword: "Football Clothing" hoặc "Pants And Leggings", productType: "BOTTOM"
          + quần áo tennis, áo tennis -> "Tennis Clothing", productType: "TOP" nếu user nói áo
          + quần áo bóng rổ, jersey bóng rổ -> "Basketball Clothing"
          + quần áo chạy bộ -> "Running Clothing"
          + giày (chung không rõ loại) -> "Shoes", productType: "SHOES"
          + quần áo (chung) -> "Clothing", productType: null
        - Tìm kiếm theo giá: dùng action "search_by_price_range", cung cấp minPrice, maxPrice.

        ❗️ QUY TẮC productType (BẮT BUỘC khi recommend sản phẩm):
        - "áo", "jersey", "tee", "hoodie", "jacket", "polo", "sơ mi" → productType: "TOP"
        - "quần", "shorts", "jogger", "legging", "pants" → productType: "BOTTOM"
        - "giày", "sneaker", "boot", "cleats" → productType: "SHOES"
        - "váy", "đầm" → productType: "DRESS"
        - "balo", "túi" → productType: "BAG"
        - "nón", "mũ", "cap" → productType: "HAT"
        - "dép", "sandal" → productType: "SANDAL"
        - KHÔNG gán BOTTOM khi user chỉ hỏi áo; KHÔNG gán TOP khi user chỉ hỏi quần.

        ❗️ QUY TẮC TÌM/GỢI Ý/GIỚI THIỆU SẢN PHẨM (RAG retrieve):
        - Khi user nói "tìm", "cho xem", "giới thiệu", "gợi ý", "recommend", "show me", "có những...nào" + tên loại sản phẩm/môn thể thao:
          → action: "recommend_by_activity"
          → activity: tên môn thể thao nếu nhận được (tennis/football/basketball/running/gym), null nếu không
          → tags: list các keyword liên quan (vd "tìm giày tennis" → ["tennis", "giày tennis", "tennis shoes"])
          → suggestedCategories: list categoryKeyword phù hợp (vd ["Tennis Shoes"])
          → categoryKeyword: cụ thể nhất từ bảng trên
          → productType: theo quy tắc productType ở trên
        - VÍ DỤ:
          + "tìm giúp mình giày tennis" → action: "recommend_by_activity", activity: "tennis", tags: ["tennis", "giày tennis"], suggestedCategories: ["Tennis Shoes"], categoryKeyword: "Tennis Shoes", productType: "SHOES"
          + "giới thiệu giày đá banh" → action: "recommend_by_activity", activity: "football", tags: ["football", "đá banh", "giày bóng đá"], suggestedCategories: ["Football Shoes"], categoryKeyword: "Football Shoes", productType: "SHOES"
          + "tìm áo bóng đá" → action: "recommend_by_activity", activity: "football", tags: ["football", "áo bóng đá", "jersey"], suggestedCategories: ["Football Clothing"], categoryKeyword: "Football Clothing", productType: "TOP"
          + "có quần short nào không" → action: "recommend_by_activity", activity: null, tags: ["shorts", "quần short"], suggestedCategories: ["Shorts"], categoryKeyword: "Shorts", productType: "BOTTOM"
          + "cho xem balo" → action: "recommend_by_activity", activity: null, tags: ["balo", "túi"], suggestedCategories: ["Bags And Backpacks"], categoryKeyword: "Bags And Backpacks", productType: "BAG"
        
        ❗️ QUY TẮC ĐẶT HÀNG:
        - Nếu người dùng nói "đặt", "mua", "lấy", "cho mình X cái/đôi size Y" -> action: "prepare_order", điền "size" và "quantity" ngay trong cùng tin nhắn đó.
        - Nếu người dùng đã đề cập size trong cùng câu muốn đặt (VD: "đặt 2 đôi size 42") -> PHẢI trả về action: "prepare_order" với size: "42" và quantity: 2 ngay, KHÔNG dùng "check_size".
        - "quantity" là số lượng sản phẩm muốn mua (mặc định 1 nếu không đề cập).

        ❗️ QUY TẮC PHÂN BIỆT:
        - Nếu chứa từ 'sản phẩm', 'giày', 'áo'... -> Hỏi về SẢN PHẨM.
        - Nếu chứa từ 'sân', 'sân bóng', 'đặt sân', 'book sân'... -> Hỏi về SÂN; BẮT BUỘC gán một action sân tương ứng ở trên (không để action=null).
        """;

    public static class BookingQuery {
        public String bookingDate;
        public List<Integer> slotList;
        public String pitchType;
        public String message;
        public Map<String, Object> data;
        public String environment;
        public String location;
        public boolean nearMe;

        @Override
        public String toString() {
            return "BookingQuery{" +
                    "bookingDate='" + bookingDate + '\'' +
                    ", slotList=" + slotList +
                    ", pitchType='" + pitchType + '\'' +
                    ", message='" + message + '\'' +
                    ", data=" + data +
                    ",  environment='" + environment + '\'' +
                    ", location='" + location + '\'' +
                    ", nearMe=" + nearMe +
                    '}';
        }
    }

    public PitchResponseDTO findPitchByContext(String userInput) {
        List<PitchResponseDTO> pitches = getAllPitchesCached();
        if (userInput.contains("rẻ nhất")) {
            return pitches.stream()
                    .min(Comparator.comparing(PitchResponseDTO::getPrice))
                    .orElse(null);
        } else if (userInput.contains("mắc nhất")) {
            return pitches.stream()
                    .max(Comparator.comparing(PitchResponseDTO::getPrice))
                    .orElse(null);
        }
        return null;
    }
}
