package com.example.FieldFinder.ai;
//deploytest
import com.example.FieldFinder.Enum.PitchEnvironment;
import com.example.FieldFinder.ai.util.AiTextUtil;
import com.example.FieldFinder.ai.gemini.GeminiClient;
import com.example.FieldFinder.ai.enrich.ProductEnrichment;
import com.example.FieldFinder.ai.enrich.ProductEnrichmentService;
import com.example.FieldFinder.ai.cache.AiCatalogCache;
import com.example.FieldFinder.ai.handler.ImageSearchHandler;
import com.example.FieldFinder.ai.handler.ProductQueryHandler;
import com.example.FieldFinder.ai.handler.ActivityRecommendHandler;
import com.example.FieldFinder.ai.handler.PitchQueryHandler;
import com.example.FieldFinder.dto.res.MLItemResult;
import com.example.FieldFinder.dto.res.PitchResponseDTO;
import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.example.FieldFinder.service.BookingService;
import com.example.FieldFinder.service.CategoryService;
import com.example.FieldFinder.service.GeocodingService;
import com.example.FieldFinder.service.MLRecommendationService;
import com.example.FieldFinder.service.OpenWeatherService;
import com.example.FieldFinder.service.PhashIndex;
import com.example.FieldFinder.service.PitchService;
import com.example.FieldFinder.service.ProductService;
import com.example.FieldFinder.service.RedisService;
import com.example.FieldFinder.service.UserService;
import com.example.FieldFinder.service.log.LogPublisherService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class AIChat {

    private static final String GOOGLE_API_KEY;

    // Nhãn telemetry trong reasoning trace (model dùng cho chat/vision).
    private static final String MODEL_VERSION = "gemini-2.5-flash";

    private final ObjectMapper mapper = new ObjectMapper();
    private final GeminiClient geminiClient;
    private final ProductEnrichmentService enrichmentService;
    private final AiCatalogCache catalogCache;
    private final ImageSearchHandler imageSearchHandler;
    private final ProductQueryHandler productQueryHandler;
    private final ActivityRecommendHandler activityRecommendHandler;
    private final PitchQueryHandler pitchQueryHandler;
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

    // Pause/resume background enrichment — ủy quyền ProductEnrichmentService (caller: AIChatController).
    public void pauseEnrichment()  { enrichmentService.pauseEnrichment(); }
    public void resumeEnrichment() { enrichmentService.resumeEnrichment(); }

    private final OpenWeatherService weatherService;
    private final GeocodingService geocodingService;

    // Optional: present only when MongoDB is configured. Used to read VIEW_PITCH interaction logs
    // for "đã xem" personalization signals. Null-safe everywhere it's used.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    public AIChat(PitchService pitchService, ProductService productService, UserService userService, OpenWeatherService weatherService, GeocodingService geocodingService, LogPublisherService logPublisherService, BookingService bookingService, RedisService redisService, MLRecommendationService mlService, PhashIndex phashIndex, CategoryService categoryService, com.example.FieldFinder.ai.ranking.CompositeRanker compositeRanker, AiChatSessionContextStore sessionContextStore, GeminiClient geminiClient, ProductEnrichmentService enrichmentService, AiCatalogCache catalogCache, ImageSearchHandler imageSearchHandler, ProductQueryHandler productQueryHandler, ActivityRecommendHandler activityRecommendHandler, PitchQueryHandler pitchQueryHandler) {
        this.geminiClient = geminiClient;
        this.enrichmentService = enrichmentService;
        this.catalogCache = catalogCache;
        this.imageSearchHandler = imageSearchHandler;
        this.productQueryHandler = productQueryHandler;
        this.activityRecommendHandler = activityRecommendHandler;
        this.pitchQueryHandler = pitchQueryHandler;
        this.pitchService = pitchService;
        this.productService = productService;
        this.userService = userService;
        this.weatherService = weatherService;
        this.geocodingService = geocodingService;
        this.logPublisherService = logPublisherService;
        this.bookingService = bookingService;
        this.redisService = redisService;
        this.phashIndex = phashIndex;
        this.mlService = mlService;
        this.categoryService = categoryService;
        this.compositeRanker = compositeRanker;
        this.sessionContextStore = sessionContextStore;
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

    static {
        Dotenv dotenv = Dotenv.load();
        dotenv.entries().forEach(entry -> {
            System.setProperty(entry.getKey(), entry.getValue());
        });
        GOOGLE_API_KEY = dotenv.get("GOOGLE_API_KEY");
    }

    /** Embedding cho query — ủy quyền GeminiClient (giữ public cho ProductServiceImpl). */
    public List<Double> getEmbedding(String text) {
        return geminiClient.getEmbedding(text);
    }

    public static String buildSystemPrompt(List<PitchResponseDTO> allPitches) {
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

    /** Tìm kiếm sản phẩm theo ảnh — ủy quyền ImageSearchHandler. */
    public BookingQuery processImageSearchWithGemini(String base64Image, String sessionId) {
        return imageSearchHandler.process(base64Image, sessionId);
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
                PitchResponseDTO selectedPitch = AiTextUtil.findPitchByPrice(allPitches,
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


    private PitchResponseDTO findPitchByContext(String userInput, List<PitchResponseDTO> pitches) {
        if (userInput.contains("rẻ nhất")) {
            return AiTextUtil.findPitchByPrice(pitches, true);
        } else if (userInput.contains("mắc nhất")) {
            return AiTextUtil.findPitchByPrice(pitches, false);
        }
        return null;
    }

    private BookingQuery handleProductQuery(BookingQuery query, String userInput, String sessionId) {
        return productQueryHandler.handle(query, userInput, sessionId);
    }

    /** Bổ sung category/activity từ session khi user follow-up (vd "mắc quá" sau khi xem giày đá bóng). */
    private void enrichProductQueryFromSession(BookingQuery query, String sessionId) {
        if (query.data == null || sessionId == null) return;
        Object ck = query.data.get("categoryKeyword");
        if (ck == null || (ck instanceof String s && s.isBlank())) {
            String lastCat = sessionContextStore.getLastCategoryKeyword(sessionId);
            if (lastCat != null && !lastCat.isBlank()) {
                query.data.put("categoryKeyword", lastCat);
            }
        }
        Object act = query.data.get("activity");
        if (act == null || (act instanceof String s && s.isBlank())) {
            String lastAct = sessionContextStore.getLastActivity(sessionId);
            if (lastAct != null && !lastAct.isBlank()) {
                query.data.put("activity", lastAct);
            }
        }
        Object pt = query.data.get("productType");
        if (pt == null || (pt instanceof String s && s.isBlank())) {
            String lastPt = sessionContextStore.getLastProductType(sessionId);
            if (lastPt != null && !lastPt.isBlank()) {
                query.data.put("productType", lastPt);
            }
        }
    }

    private BookingQuery handleWeatherQuery(BookingQuery query, String sessionId, Double userLat, Double userLng) {
        if (query.data == null) {
            query.data = new HashMap<>();
        }

        Object cityObj = query.data.get("city");
        String explicitCity = (cityObj != null) ? cityObj.toString() : null;
        String city = resolveWeatherCity(explicitCity, sessionId, userLat, userLng);

        try {
            String weather = weatherService.getCurrentWeather(city);
            PitchEnvironment env = suggestEnvironmentByWeather(weather);
            String envLabel = env == PitchEnvironment.INDOOR ? "trong nhà (Indoor)" : "ngoài trời (Outdoor)";

            List<PitchResponseDTO> allCached = catalogCache.getAllPitchesCached();

            // User nêu RÕ một thành phố không có sân nào (vd "thời tiết Hà Nội") → vẫn báo thời tiết,
            // nhưng nói rõ chưa có sân ở đó thay vì gợi ý sân HCM gây hiểu nhầm.
            boolean cityExplicit = explicitCity != null && !explicitCity.isBlank();
            if (cityExplicit && !isCityServed(city, allCached)) {
                query.message = String.format(
                        "Thời tiết ở %s hiện là %s 🌤️\nRất tiếc, hiện SportsHub chưa có sân nào ở %s — các sân đang hoạt động đều ở khu vực TP. Hồ Chí Minh. Mong bạn thông cảm nhé!",
                        city, weather, city);
                query.data.clear();
                query.data.put("action", "weather_pitch_suggestion");
                query.data.put("weather", weather);
                query.data.put("weatherCity", city);
                query.data.put("matchedPitches", new ArrayList<>());
                query.data.put("pitches", new ArrayList<>());
                return logWeatherAndReturn(query, sessionId, city);
            }

            // User nêu RÕ thành phố → chỉ gợi ý sân TRONG thành phố đó (rank theo lịch sử/sở thích,
            // KHÔNG theo khoảng cách user vì user có thể ở tỉnh khác). Không nêu → giữ proximity user.
            List<PitchResponseDTO> envFiltered = allCached.stream()
                    .filter(p -> p.getEnvironment() == env)
                    .filter(p -> !cityExplicit || pitchInCity(p.getAddress(), city))
                    .collect(Collectors.toList());
            PitchQueryHandler.PitchRankResult rr = cityExplicit
                    ? pitchQueryHandler.rankRecommendedPitches(envFiltered, sessionId, null, null, null, false, 10)
                    : pitchQueryHandler.rankRecommendedPitches(envFiltered, sessionId, null, userLat, userLng, true, 10);
            List<PitchResponseDTO> suggestedPitches = rr.pitches;

            StringBuilder msg = new StringBuilder();
            msg.append(String.format("Thời tiết ở %s hiện là %s 🌤️\n", city, weather));
            msg.append(explainEnvironmentChoice(weather, env));
            if (!suggestedPitches.isEmpty()) {
                List<String> bits = new ArrayList<>();
                if (rr.usedProximity) bits.add("gần bạn");
                if (rr.usedHistory) bits.add("theo lịch sử đặt/xem");
                if (rr.usedProfile) bits.add("hợp sở thích");
                String basis = bits.isEmpty() ? "" : " (" + String.join(", ", bits) + ")";
                String atCity = cityExplicit ? " ở " + city : "";
                msg.append(String.format(
                        "\nDưới đây là %d sân %s%s phù hợp%s 👇",
                        suggestedPitches.size(), envLabel, atCity, basis));
            } else {
                msg.append(cityExplicit
                        ? String.format("\nHiện chưa có sân %s ở %s.", envLabel, city)
                        : String.format("\nHiện chưa có sân %s trong hệ thống.", envLabel));
            }
            query.message = msg.toString();

            query.data.clear();
            query.data.put("action", "weather_pitch_suggestion");
            query.data.put("environment", env.name());
            query.data.put("weather", weather);
            query.data.put("weatherCity", city);
            query.data.put("matchedPitches", suggestedPitches);
            query.data.put("pitches", suggestedPitches);

        } catch (Exception e) {
            e.printStackTrace();
            query.message = "Không thể lấy dữ liệu thời tiết lúc này.";
            query.data.clear();
        }

        return logWeatherAndReturn(query, sessionId, city);
    }

    /** Ghi telemetry CHAT_WEATHER_QUERY (kèm pitch IDs nếu có) rồi trả query — dùng chung mọi nhánh weather. */
    private BookingQuery logWeatherAndReturn(BookingQuery query, String sessionId, String city) {
        try {
            UUID userId = catalogCache.resolveCurrentUserId(sessionId);
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

    // Alias thành phố HCM — toàn bộ sân hiện thuộc khu vực này.
    private static final java.util.Set<String> HCM_CITY_ALIASES = java.util.Set.of(
            "hồ chí minh", "ho chi minh city", "ho chi minh", "tp hồ chí minh",
            "tp. hồ chí minh", "tphcm", "hcm", "sài gòn", "saigon");

    /** Thành phố có sân để gợi ý không: là HCM (alias) hoặc có sân nào địa chỉ chứa tên thành phố. */
    private static boolean isCityServed(String city, List<PitchResponseDTO> pitches) {
        if (city == null || city.isBlank()) return true;
        String c = city.trim().toLowerCase();
        if (HCM_CITY_ALIASES.contains(c)) return true;
        return pitches.stream().anyMatch(p -> pitchInCity(p.getAddress(), c));
    }

    /** Sân có thuộc thành phố/tỉnh `city` không (address chứa tên; HCM khớp mọi alias). */
    private static boolean pitchInCity(String address, String city) {
        if (address == null || city == null) return false;
        String a = address.toLowerCase();
        String c = city.trim().toLowerCase();
        if (a.contains(c)) return true;
        boolean cityIsHcm = HCM_CITY_ALIASES.contains(c);
        boolean addrIsHcm = a.contains("hồ chí minh") || a.contains("hcm") || a.contains("sài gòn");
        return cityIsHcm && addrIsHcm;
    }

    /**
     * Brand user nêu thẳng trong query, đối chiếu với brand CÓ THẬT trong catalog (không bịa).
     * Trả brand canonical (đúng case DB) để ranker so khớp p.getBrand(); null nếu không có.
     * Ưu tiên brand dài nhất khớp (vd "New Balance" thắng "Balance").
     */



    private BookingQuery handleRecommendByActivity(BookingQuery query, String sessionId, String userInput) {
        return activityRecommendHandler.handle(query, sessionId, userInput);
    }

    public BookingQuery parseBookingInput(String userInput, String sessionId) throws IOException, InterruptedException {
        return parseBookingInput(userInput, sessionId, null, null);
    }

    public BookingQuery parseBookingInput(String userInput, String sessionId, Double userLat, Double userLng) throws IOException, InterruptedException {
        if (AiTextUtil.isGreeting(userInput)) {
            BookingQuery query = new BookingQuery();
            query.message = "Xin chào! Tôi có thể giúp bạn đặt sân bóng hoặc tìm kiếm sản phẩm thể thao (giày, áo...).";
            query.slotList = new ArrayList<>();
            query.pitchType = "ALL";
            query.data = new HashMap<>();
            return query;
        }

        List<PitchResponseDTO> allPitches = catalogCache.getAllPitchesCached();
        String finalPrompt = buildSystemPrompt(allPitches);

        BookingQuery query;
        String cleanJson = null;
        try {
            cleanJson = geminiClient.chat(userInput, finalPrompt);
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

            // Override: query trộn (thuộc tính sản phẩm + giá) phải đi recommend flow để GIỮ ranking
            // brand→giới→màu→size; giá thành hard filter trong đó. search_by_price_range chỉ còn
            // cho query thuần giá ("sản phẩm dưới 500k" — không type/tags/category).
            boolean hasActivity = activityObj != null && !((String) activityObj).isBlank();
            boolean hasNonemptyTags = tagsObj instanceof List<?> && !((List<?>) tagsObj).isEmpty();
            Object ptObj = query.data.get("productType");
            Object ckObj = query.data.get("categoryKeyword");
            boolean hasProductAttrs = hasActivity || hasNonemptyTags
                    || (ptObj instanceof String pt && !pt.isBlank())
                    || (ckObj instanceof String ck && !ck.isBlank());
            if ((action == null && hasActivity)
                    || ("search_by_price_range".equals(action) && hasProductAttrs)) {
                action = "recommend_by_activity";
                query.data.put("action", "recommend_by_activity");
            }

            // "rẻ"/"mắc quá" (không phải "rẻ nhất") → danh sách sản phẩm giá thấp, giữ ngữ cảnh loại sp
            if ("cheapest_product".equals(action) && AiTextUtil.isAffordableListQuery(userInput)) {
                action = "recommend_by_activity";
                query.data.put("action", "recommend_by_activity");
                query.data.put("preferLowPrice", true);
                enrichProductQueryFromSession(query, sessionId);
            }
            if ("recommend_by_activity".equals(action) && AiTextUtil.isAffordableListQuery(userInput)) {
                query.data.put("preferLowPrice", true);
                enrichProductQueryFromSession(query, sessionId);
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
                return handleWeatherQuery(query, sessionId, userLat, userLng);
            }
            if ("recommend_by_activity".equals(action)) {
                return handleRecommendByActivity(query, sessionId, userInput);
            }
            if ("list_pitches".equals(action) || "recommend_pitch".equals(action)
                    || "count_pitches_by_type".equals(action)
                    || "check_pitch_availability".equals(action) || "book_pitch".equals(action)
                    || "list_my_bookings".equals(action) || "cheapest_pitch".equals(action)
                    || "most_expensive_pitch".equals(action)) {
                return handlePitchQuery(query, userInput, sessionId, allPitches, userLat, userLng, cleanJson);
            }
            if (action.contains("product") || action.contains("stock") ||
                    action.contains("sales") || action.contains("sale") ||
                    action.contains("discount") ||
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
            PitchEnvironment requestedEnvironment = AiTextUtil.detectEnvironmentFromInput(userInput);

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
                String envMsg = requestedEnvironment != null ? " " + AiTextUtil.formatEnvironment(requestedEnvironment) : "";
                query.message = String.format("Rất tiếc, tôi không tìm thấy sân%s %s nào phù hợp trong hệ thống.", envMsg, AiTextUtil.formatPitchType(query.pitchType));
            } else {
                if (query.message == null || query.message.isEmpty()) {
                    String dateStr = query.bookingDate != null ? " ngày " + query.bookingDate : "";
                    String timeStr = !query.slotList.isEmpty() ? " khung giờ " + query.slotList : "";
                    String envStr = requestedEnvironment != null ? " " + AiTextUtil.formatEnvironment(requestedEnvironment) : "";
                    query.message = String.format("Đã tìm thấy %d sân%s %s phù hợp%s%s. Bạn xem danh sách bên dưới nhé 👇", matchedPitches.size(), envStr, AiTextUtil.formatPitchType(query.pitchType), dateStr, timeStr);
                }
            }
        }

        processSpecialCases(userInput, sessionId, query, allPitches);

        if (query.message == null || query.message.isBlank()) {
            query.message = "Mình chưa hiểu rõ yêu cầu. Bạn muốn tìm sân bóng, đặt sân, hay mua sản phẩm thể thao? Ví dụ: \"cho xem các sân 5 người\" hoặc \"giày rẻ nhất\".";
        }

        return query;
    }

    private BookingQuery handlePitchQuery(BookingQuery query, String userInput, String sessionId,
                                          List<PitchResponseDTO> allPitches, Double userLat, Double userLng, String cleanJson) {
        return pitchQueryHandler.handle(query, userInput, sessionId, allPitches, userLat, userLng, cleanJson);
    }


    /** Backward-compat: chỉ lấy tags — ủy quyền ProductEnrichmentService. */
    public List<String> generateTagsForProduct(String imageUrl) {
        return enrichmentService.generateTagsForProduct(imageUrl);
    }

    /** Enrich ảnh sản phẩm (tags + màu) — ủy quyền ProductEnrichmentService (caller: ProductServiceImpl). */
    public ProductEnrichment enrichProductFromImage(String imageUrl) {
        return enrichmentService.enrichProductFromImage(imageUrl);
    }

    private String resolveWeatherCity(String explicitCity, String sessionId, Double userLat, Double userLng) {
        if (explicitCity != null && !explicitCity.isBlank()) {
            return explicitCity.trim();
        }
        if (userLat != null && userLng != null) {
            Optional<String> fromGps = geocodingService.reverseGeocodeCity(userLat, userLng);
            if (fromGps.isPresent()) return fromGps.get();
        }
        UUID uid = catalogCache.resolveCurrentUserId(sessionId);
        if (uid != null) {
            try {
                var profile = userService.getUserById(uid);
                if (profile != null) {
                    if (profile.getProvince() != null && !profile.getProvince().isBlank()) {
                        return profile.getProvince().trim();
                    }
                    if (profile.getLatitude() != null && profile.getLongitude() != null) {
                        Optional<String> fromProfile = geocodingService.reverseGeocodeCity(
                                profile.getLatitude(), profile.getLongitude());
                        if (fromProfile.isPresent()) return fromProfile.get();
                    }
                }
            } catch (Exception e) {
                System.err.println("resolveWeatherCity profile error: " + e.getMessage());
            }
        }
        return "Ho Chi Minh City";
    }

    static PitchEnvironment suggestEnvironmentByWeather(String weather) {
        String w = weather.toLowerCase();
        if (w.contains("quang") || w.contains("clear") || w.contains("nắng") || w.contains("sunny")
                || w.contains("ít mây") || w.contains("few clouds")) {
            return PitchEnvironment.OUTDOOR;
        }
        if (w.contains("mưa") || w.contains("rain") || w.contains("storm") || w.contains("bão")
                || w.contains("dông") || w.contains("sấm") || w.contains("sét") || w.contains("thunder")
                || w.contains("drizzle") || w.contains("shower") || w.contains("ẩm")
                || w.contains("mây đen") || w.contains("u ám") || w.contains("âm u")
                || w.contains("overcast") || w.contains("nhiều mây") || w.contains("broken clouds")
                || w.contains("scattered clouds") || w.contains("mây")) {
            return PitchEnvironment.INDOOR;
        }
        return PitchEnvironment.OUTDOOR;
    }

    static String explainEnvironmentChoice(String weather, PitchEnvironment env) {
        String w = weather.toLowerCase();
        if (env == PitchEnvironment.INDOOR) {
            if (w.contains("mưa") || w.contains("rain") || w.contains("drizzle") || w.contains("shower")) {
                return "Trời đang mưa nên mình gợi ý sân trong nhà để bạn không bị ướt và trơn sân.";
            }
            if (w.contains("bão") || w.contains("storm") || w.contains("dông") || w.contains("sấm") || w.contains("thunder")) {
                return "Thời tiết xấu (mưa bão/sấm sét) — sân trong nhà an toàn và ổn định hơn.";
            }
            if (w.contains("mây") || w.contains("u ám") || w.contains("âm u") || w.contains("overcast")) {
                return "Trời nhiều mây/u ám, có thể mưa bất chợt — mình gợi ý sân trong nhà để trận đấu không bị gián đoạn.";
            }
            if (w.contains("ẩm")) {
                return "Không khí ẩm ướt — sân trong nhà sẽ thoải mái hơn.";
            }
            return "Với điều kiện thời tiết hiện tại, sân trong nhà là lựa chọn phù hợp hơn.";
        }
        return "Thời tiết khá thuận lợi — sân ngoài trời sẽ thoáng mát và trải nghiệm tự nhiên hơn.";
    }




    /** Chuẩn hóa productType từ Gemini (TOP, top, Shoes → SHOES). */


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
            "action": "get_weather" | "check_stock" | "check_sales" | "check_size" | "prepare_order" | "list_on_sale" | "count_on_sale" | "max_discount_product" | "max_discount_brand" | "max_discount_category" | "best_selling_product" | "search_by_price_range" | "cheapest_product" | "most_expensive_product" | "product_detail" | "recommend_by_activity" | "list_pitches" | "recommend_pitch" | "count_pitches_by_type" | "check_pitch_availability" | "book_pitch" | "list_my_bookings" | "cheapest_pitch" | "most_expensive_pitch" | null,
            "productName": "...",
            "brand": "...",
            "city": "...",
            "size": "...",
            "quantity": 1,
            "categoryKeyword": "...",
            "productType": "SHOES" | "TOP" | "BOTTOM" | "SANDAL" | "DRESS" | "BAG" | "HAT" | "OTHER" | null,
            "color": "đen" | "trắng" | "xám" | "đỏ" | "cam" | "vàng" | "hồng" | "tím" | "nâu" | "xanh lá" | "xanh dương" | null,
            "minPrice": 0,
            "maxPrice": 0,
            "activity": "...",
            "suggestedCategories": [],
            "tags": [],
            "reasons": {}
          }
        }
        
        ❗️ QUY TẮC THỜI TIẾT:
        - Câu hỏi thời tiết (vd: "thời tiết hôm nay", "trời mưa không", "thời tiết thế nào") -> action: "get_weather".
        - QUAN TRỌNG: nếu user VỪA hỏi/nhắc thời tiết VỪA nhờ gợi ý/tìm sân trong cùng câu
          (vd "trời mưa không, gợi ý sân giúp mình", "thời tiết sao rồi tìm sân với", "trời nắng thì đặt sân nào") -> action: "get_weather"
          (KHÔNG dùng "recommend_pitch"). Backend sẽ tự gợi ý sân đã lọc theo thời tiết (mưa→trong nhà, nắng→ngoài trời) kèm lý do.
        - Nếu user KHÔNG nêu tên thành phố -> để `city` = null (backend sẽ tự lấy vị trí GPS hoặc tỉnh/thành trong profile).
        - Nếu user nêu thành phố cụ thể (vd "thời tiết Hồ Chí Minh") -> `city`: "Hồ Chí Minh" hoặc "Ho Chi Minh City".

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
        - PHÂN BIỆT giá rẻ (danh sách) vs rẻ nhất (1 sản phẩm):
          + "rẻ nhất", "mắc nhất", "giá thấp nhất", "đắt nhất" → action "cheapest_product" hoặc "most_expensive_product" (TRẢ 1 SẢN PHẨM).
          + "rẻ", "giá rẻ", "rẻ hơn", "mắc quá", "mấy đôi rẻ", "cho tôi option rẻ" (KHÔNG có "nhất") → action "recommend_by_activity"
            và GIỮ categoryKeyword/productType/activity của ngữ cảnh trước (vd Football Shoes nếu vừa xem giày đá bóng).
          + VD: "mắc quá cho tôi mấy đôi rẻ rẻ thôi" (sau khi xem giày đá bóng) → action: "recommend_by_activity",
            categoryKeyword: "Football Shoes", productType: "SHOES", activity: "football".
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
        - Tìm kiếm theo giá: action "search_by_price_range" + minPrice/maxPrice CHỈ KHI query thuần về giá,
          KHÔNG nêu loại sản phẩm/brand/màu/giới tính (vd "sản phẩm dưới 500k", "có gì tầm 1 triệu").
          Nếu query TÌM KIẾM có kèm khoảng giá (vd "giày nike đen dưới 2 triệu") -> action "recommend_by_activity"
          và VẪN điền minPrice/maxPrice (backend sẽ lọc giá + xếp hạng).

        ❗️ QUY TẮC GIẢM GIÁ / SALE / KHUYẾN MÃI:
        - Từ khóa "giảm giá", "sale", "khuyến mãi", "đang giảm", "ưu đãi", "deal", "giảm sốc":
          + Liệt kê sản phẩm đang giảm giá -> action: "list_on_sale".
          + Đếm số sản phẩm đang giảm giá -> action: "count_on_sale".
          + Sản phẩm GIẢM SÂU NHẤT (1 sản phẩm) -> action: "max_discount_product".
          + THƯƠNG HIỆU / BRAND giảm giá nhiều nhất -> action: "max_discount_brand".
          + DANH MỤC / LOẠI sản phẩm giảm giá nhiều nhất (1 danh mục) -> action: "max_discount_category".
          + LIỆT KÊ các DANH MỤC đang giảm giá (số nhiều, "các/những danh mục nào đang giảm") -> action: "list_discount_categories".
        - Nếu câu hỏi giảm giá có kèm THƯƠNG HIỆU và/hoặc LOẠI sản phẩm -> vẫn dùng "list_on_sale"
          (hoặc "count_on_sale"/"max_discount_product") và điền `brand` và/hoặc `productType`/`categoryKeyword`.
        - PHÂN BIỆT: "thương hiệu/brand/hãng nào" -> max_discount_brand;
          "danh mục/loại NÀO giảm NHIỀU NHẤT / SÂU NHẤT" (hỏi cái nhất) -> max_discount_category;
          "(các/những) danh mục NÀO đang giảm giá" (liệt kê danh sách, KHÔNG hỏi nhất) -> list_discount_categories.
        - VÍ DỤ:
          + "shop có gì đang sale" / "sản phẩm nào đang giảm giá" -> action: "list_on_sale"
          + "có bao nhiêu sản phẩm giảm giá" -> action: "count_on_sale"
          + "sản phẩm nào giảm sâu nhất" / "món nào giảm nhiều nhất" -> action: "max_discount_product"
          + "Nike đang sale gì" / "adidas có giảm giá không" -> action: "list_on_sale", brand: "Nike"/"adidas"
          + "giày nào đang giảm giá" -> action: "list_on_sale", productType: "SHOES"
          + "áo nike nào đang sale" -> action: "list_on_sale", brand: "Nike", productType: "TOP"
          + "thương hiệu nào giảm giá nhiều nhất" / "hãng nào đang sale mạnh nhất" -> action: "max_discount_brand"
          + "danh mục nào giảm giá nhiều nhất" / "loại sản phẩm nào đang giảm sâu nhất" -> action: "max_discount_category"
          + "shop có các danh mục nào đang giảm giá" / "những loại nào đang sale" / "có danh mục nào đang giảm không" -> action: "list_discount_categories"

        ❗️ QUY TẮC productType (BẮT BUỘC khi recommend sản phẩm):
        - "áo", "jersey", "tee", "hoodie", "jacket", "polo", "sơ mi" → productType: "TOP"
        - "quần", "shorts", "jogger", "legging", "pants" → productType: "BOTTOM"
        - "giày", "sneaker", "boot", "cleats" → productType: "SHOES"
        - "váy", "đầm" → productType: "DRESS"
        - "balo", "túi" → productType: "BAG"
        - "nón", "mũ", "cap" → productType: "HAT"
        - "dép", "sandal" → productType: "SANDAL"
        - KHÔNG gán BOTTOM khi user chỉ hỏi áo; KHÔNG gán TOP khi user chỉ hỏi quần.

        ❗️ QUY TẮC MÀU SẮC (`color`):
        - Nếu user nêu màu (vd "giày nike màu đen", "áo đỏ", "balo xanh") → điền `color` về ĐÚNG 1 giá trị
          canonical: "đen","trắng","xám","đỏ","cam","vàng","hồng","tím","nâu","xanh lá","xanh dương".
        - Quy đổi đồng nghĩa: black→"đen", white→"trắng", navy/xanh dương/xanh biển→"xanh dương",
          xanh lá/green→"xanh lá", grey/ghi/xám→"xám". Nếu user KHÔNG nêu màu → `color`: null.
        - VD: "tìm giày nike màu đen" → productType: "SHOES", color: "đen"; "áo thun trắng" → productType: "TOP", color: "trắng".

        ❗️ QUY TẮC SIZE & GIỚI TÍNH KHI TÌM KIẾM:
        - Query TÌM/GỢI Ý có nêu size (vd "tìm giày nike size 39") → VẪN action "recommend_by_activity",
          điền size: "39". CHỈ dùng "check_size" khi user hỏi tồn kho một sản phẩm cụ thể,
          "prepare_order" khi user muốn đặt/mua/lấy.
        - User nêu giới tính ("nam", "nữ", "cho nam", "đồ nữ", "men", "women") → thêm "nam" hoặc "nữ" vào tags.

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
          + "tìm giày nike nam màu đen size 39" → action: "recommend_by_activity", productType: "SHOES", color: "đen", size: "39", tags: ["nike", "nam", "giày"], categoryKeyword: "Shoes"
          + "giày nike đen dưới 2 triệu" → action: "recommend_by_activity", productType: "SHOES", color: "đen", maxPrice: 2000000, tags: ["nike", "giày"], categoryKeyword: "Shoes"
        
        ❗️ QUY TẮC ĐẶT HÀNG:
        - Nếu người dùng nói "đặt", "mua", "lấy", "cho mình X cái/đôi size Y" -> action: "prepare_order", điền "size" và "quantity" ngay trong cùng tin nhắn đó.
        - Nếu người dùng đã đề cập size trong cùng câu muốn đặt (VD: "đặt 2 đôi size 42") -> PHẢI trả về action: "prepare_order" với size: "42" và quantity: 2 ngay, KHÔNG dùng "check_size".
        - "quantity" là số lượng sản phẩm muốn mua (mặc định 1 nếu không đề cập).
        - NHIỀU SIZE trong cùng tin nhắn (VD "3 đôi size 39 và 2 đôi size 40") -> action: "prepare_order" và PHẢI trả về thêm mảng "orderItems": danh sách {"size","quantity"} cho TỪNG size. KHÔNG gộp size thành "39,40" hay cộng dồn quantity.
          + "đặt 3 đôi size 39 và 2 đôi size 40" → action: "prepare_order", orderItems: [{"size":"39","quantity":3},{"size":"40","quantity":2}]
          + Đặt 1 size thì vẫn dùng "size" + "quantity" như cũ (không bắt buộc orderItems).

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
        List<PitchResponseDTO> pitches = catalogCache.getAllPitchesCached();
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
