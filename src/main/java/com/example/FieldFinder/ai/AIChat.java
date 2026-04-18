package com.example.FieldFinder.ai;

import com.example.FieldFinder.Enum.PitchEnvironment;
import com.example.FieldFinder.dto.res.PitchResponseDTO;
import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.example.FieldFinder.mapper.CategoryMapper;
import com.example.FieldFinder.service.BookingService;
import com.example.FieldFinder.service.OpenWeatherService;
import com.example.FieldFinder.service.PitchService;
import com.example.FieldFinder.service.ProductService;
import com.example.FieldFinder.service.RedisService;
import com.example.FieldFinder.service.UserService;
import com.example.FieldFinder.service.log.LogPublisherService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.cdimascio.dotenv.Dotenv;
import okhttp3.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class AIChat {

    private static final String GOOGLE_API_KEY;

    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=";

    private static final String EMBEDDING_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/text-embedding-004:embedContent?key=";

    private final OkHttpClient client = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final PitchService pitchService;
    private final ProductService productService;
    private final UserService userService;
    private final LogPublisherService logPublisherService;
    private final BookingService bookingService;
    private final RedisService redisService;

    private static final long MIN_INTERVAL_BETWEEN_CALLS_MS = 4000;
    private long lastCallTime = 0;

    private final OpenWeatherService weatherService;

    private final Map<String, PitchResponseDTO> sessionPitches = new HashMap<>();

    private final Map<String, ProductResponseDTO> sessionLastProducts = new HashMap<>();

    private final Map<String, String> sessionLastSizes = new HashMap<>();
    private final Map<String, String> sessionLastActivity = new HashMap<>();

    public AIChat(PitchService pitchService, ProductService productService, UserService userService, OpenWeatherService weatherService, LogPublisherService logPublisherService, BookingService bookingService, RedisService redisService) {
        this.pitchService = pitchService;
        this.productService = productService;
        this.userService = userService;
        this.weatherService = weatherService;
        this.logPublisherService = logPublisherService;
        this.bookingService = bookingService;
        this.redisService = redisService;
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
        try {
            waitIfNeeded();
            ObjectNode rootNode = mapper.createObjectNode();

            ObjectNode content = rootNode.putObject("content");
            content.putObject("parts").put("text", text);

            Request request = new Request.Builder()
                    .url(EMBEDDING_API_URL + GOOGLE_API_KEY)
                    .post(RequestBody.create(mapper.writeValueAsString(rootNode), MediaType.parse("application/json")))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) return new ArrayList<>();

                JsonNode root = mapper.readTree(response.body().string());
                JsonNode valuesNode = root.path("embedding").path("values");

                List<Double> vector = new ArrayList<>();
                if (valuesNode.isArray()) {
                    for (JsonNode val : valuesNode) {
                        vector.add(val.asDouble());
                    }
                }
                return vector;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    private synchronized void waitIfNeeded() throws InterruptedException {
        long now = System.currentTimeMillis();
        long waitTime = MIN_INTERVAL_BETWEEN_CALLS_MS - (now - lastCallTime);
        if (waitTime > 0) {
            Thread.sleep(waitTime);
        }
        lastCallTime = System.currentTimeMillis();
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
        waitIfNeeded();

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

        Request request = new Request.Builder()
                .url(GEMINI_API_URL + GOOGLE_API_KEY)
                .post(RequestBody.create(mapper.writeValueAsString(rootNode), MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Gemini API Error: " + response.code() + " " + response.body().string());
            }
            return cleanJson(extractGeminiResponse(response.body().string()));
        }
    }

    public BookingQuery processImageSearchWithGemini(String base64Image, String sessionId) {
        BookingQuery result = new BookingQuery();
        result.data = new HashMap<>();
        result.slotList = new ArrayList<>();
        result.pitchType = "ALL";

        try {
            waitIfNeeded();

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
                String cleanBase64 = base64Image;

                if (base64Image.contains(",")) {
                    String[] tokens = base64Image.split(",");
                    if (tokens[0].contains("png")) {
                        mimeType = "image/png";
                    }
                    cleanBase64 = tokens[1];
                }

                inlineData.put("mime_type", mimeType);
                inlineData.put("data", cleanBase64);
            }

            ObjectNode generationConfig = rootNode.putObject("generationConfig");
            generationConfig.put("response_mime_type", "application/json");

            Request request = new Request.Builder()
                    .url(GEMINI_API_URL + GOOGLE_API_KEY)
                    .post(RequestBody.create(mapper.writeValueAsString(rootNode), MediaType.parse("application/json")))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) { /* ... */ }

                String rawJson = extractGeminiResponse(response.body().string());
                String cleanJson = cleanJson(rawJson);

                JsonNode rootAiNode = mapper.readTree(cleanJson);

                List<String> rawTags = mapper.convertValue(
                        rootAiNode.path("tags"),
                        new TypeReference<List<String>>(){}
                );

                List<String> cleanTags = sanitizeTags(rawTags);

                List<String> expandedTags = expandColorTags(cleanTags);

                String majorCategory = rootAiNode.path("majorCategory").asText("ALL");
                String productName = rootAiNode.path("productName").asText("Sản phẩm");
                String color = rootAiNode.path("color").asText("");

                String description = String.format("%s %s %s", majorCategory, productName, String.join(" ", cleanTags));

                List<ProductResponseDTO> finalResults = productService.findProductsByVector(description);

                if (finalResults.isEmpty()) {
                    finalResults = productService.findProductsByImage(cleanTags, majorCategory);
                }

                if (!finalResults.isEmpty()) {
                    if (sessionId != null) {
                        sessionLastProducts.put(sessionId, finalResults.get(0));
                        System.out.println("✅ Image Search: Saved Context for Session " + sessionId + " -> " + finalResults.get(0).getName());
                    }

                    result.message = String.format("Dựa trên hình ảnh %s (%s), tôi tìm thấy %d sản phẩm tương tự:",
                            productName, color, finalResults.size());
                    result.data.put("action", "image_search_result");
                    result.data.put("products", finalResults);
                    result.data.put("extractedTags", cleanTags);
                } else {
                    result.message = String.format("Tôi nhận diện được đây là %s màu %s. Tuy nhiên, hiện tại cửa hàng không có sản phẩm nào khớp.", productName, color);
                    result.data.put("extractedTags", expandedTags);
                    result.data.put("products", new ArrayList<>());
                    result.data.put("action", "image_search_result");
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            result.message = "Lỗi khi xử lý ảnh: " + e.getMessage();
        }
        return result;
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
                    sessionPitches.put(sessionId, selectedPitch);
                    query.data.put("selectedPitch", selectedPitch);
                }
            }
        }

        // Xử lý "sân này" với fallback (Logic này đã check từ khóa 'sân này' nên an toàn)
        if (userInput.contains("sân này")) {
            PitchResponseDTO selectedPitch = sessionPitches.get(sessionId);
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

        UUID userId = userService.getUserIdBySession(sessionId);
        List<ProductResponseDTO> products = productService.getAllProducts(PageRequest.of(0, 500), null, null, null, userId).getContent();
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
            return query;
        }

        if ("count_on_sale".equals(action)) {
            long count = products.stream()
                    .filter(p -> p.getSalePercent() != null && p.getSalePercent() > 0)
                    .count();
            query.message = "Hiện tại shop có " + count + " sản phẩm đang giảm giá.";
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
                p = sessionLastProducts.get(sessionId);
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
                            query.message = String.format("Đây là hình ảnh thực tế của **%s**. Bạn xem bên dưới nhé 👇", p.getName());
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
                            if (sessionId != null) sessionLastSizes.put(sessionId, sizeToCheck);
                            boolean hasOrderIntent = userInput != null && (
                                    userInput.toLowerCase().contains("đặt") ||
                                            userInput.toLowerCase().contains("mua") ||
                                            userInput.toLowerCase().contains("lấy") ||
                                            userInput.toLowerCase().contains("order")
                            );
                            if (hasOrderIntent) {
                                // Chuyển thẳng sang prepare_order flow
                                int orderQty = extractQuantityFromInput(userInput, query.data.get("quantity"));
                                query.message = String.format("Xác nhận: Bạn muốn đặt **%d** đôi **%s** - **Size %s**. Nhấn nút bên dưới để thanh toán nhé! 👇", orderQty, p.getName(), sizeToCheck);
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
                        sizeToOrder = sessionLastSizes.get(sessionId);
                    }

                    int quantity = extractQuantityFromInput(userInput, query.data.get("quantity"));

                    if (sizeToOrder == null) {
                        query.message = String.format("Bạn muốn đặt size nào cho sản phẩm '%s'? (VD: 'Lấy size 40').", p.getName());
                    } else {
                        query.message = String.format("Xác nhận: Bạn muốn đặt **%d** đôi **%s** - **Size %s**. Nhấn nút bên dưới để thanh toán nhé! 👇", quantity, p.getName(), sizeToOrder);
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
            sessionLastProducts.put(sessionId, foundProduct);

            query.data.put("product", foundProduct);

            boolean shouldShowImage = false;

            if ("product_detail".equals(action) ||
                    "image_search_result".equals(action) ||
                    "prepare_order".equals(action)) {

                shouldShowImage = true;
            }

            query.data.put("showImage", shouldShowImage);
        }

        return query;
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

    private BookingQuery handleWeatherQuery(BookingQuery query) {
        if (query.data == null) {
            query.data = new HashMap<>();
        }

        Object cityObj = query.data.get("city");
        String city = (cityObj != null) ? cityObj.toString() : "Hà Nội";

        try {
            String weather = weatherService.getCurrentWeather(city);
            PitchEnvironment env = suggestEnvironmentByWeather(weather);

            List<PitchResponseDTO> suggestedPitches =
                    pitchService.getAllPitches(PageRequest.of(0, 50), null, null, null).getContent().stream()
                            .filter(p -> p.getEnvironment() == env)
                            .limit(5)
                            .toList();

            query.message = String.format(
                    "Thời tiết ở %s hiện là **%s** 🌤️. Tôi gợi ý bạn chọn **sân %s**.",
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

            return query;

        } catch (Exception e) {
            e.printStackTrace();
            query.message = "Không thể lấy dữ liệu thời tiết lúc này.";
            query.data.clear();
            return query;
        }
    }

    @SuppressWarnings("unchecked")
    private BookingQuery handleRecommendByActivity(BookingQuery query, String sessionId) {
        UUID userId = userService.getUserIdBySession(sessionId);
        String activity = (String) query.data.get("activity");
        List<String> tags = (List<String>) query.data.get("tags");
        List<String> aiCategories = (List<String>) query.data.get("suggestedCategories");

        if (activity != null && sessionId != null) {
            sessionLastActivity.put(sessionId, activity);
        }

        if (tags == null || tags.isEmpty()) {
            tags = (activity != null) ? List.of(activity) : List.of("sport");
        }

        String description = String.join(" ",
                activity != null ? activity : "",
                String.join(" ", tags)
        );

        List<ProductResponseDTO> results = productService.findProductsByVector(description);
        List<String> resolvedCategories = CategoryMapper.resolveCategories(activity, aiCategories);

        if ((results == null || results.isEmpty()) && !resolvedCategories.isEmpty()) {
            results = productService.getAllProducts(PageRequest.of(0, 500), null, null, null, userId).getContent().stream()
                    .filter(p -> p.getCategoryName() != null &&
                            resolvedCategories.contains(p.getCategoryName()))
                    .limit(12)
                    .toList();
        }

        if (results == null || results.isEmpty()) {
            query.message = "Hiện tại shop chưa có sản phẩm phù hợp hoạt động này 😢";
            query.data.put("products", List.of());
            query.data.put("groupedProducts", Map.of());
            query.data.put("action", "recommend_by_activity");
            query.data.put("showImage", false);
            return query;
        }

        query.message = String.format("Với hoạt động **%s**, bạn có thể tham khảo các sản phẩm sau 👇", activity);

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

        return query;
    }

    public BookingQuery parseBookingInput(String userInput, String sessionId) throws IOException, InterruptedException {
        if (isGreeting(userInput)) {
            BookingQuery query = new BookingQuery();
            query.message = "Xin chào! Tôi có thể giúp bạn đặt sân bóng hoặc tìm kiếm sản phẩm thể thao (giày, áo...).";
            query.slotList = new ArrayList<>();
            query.pitchType = "ALL";
            query.data = new HashMap<>();
            return query;
        }

        List<PitchResponseDTO> allPitches = pitchService.getAllPitches(PageRequest.of(0, 50), null, null, null).getContent();
        String finalPrompt = buildSystemPrompt(allPitches);

        BookingQuery query;
        try {
            String cleanJson = callGeminiAPI(userInput, finalPrompt);
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

            if (action == null) {
                if (productName != null && !productName.isEmpty()) {
                    action = "check_stock";
                    query.data.put("action", "check_stock");
                } else {
                    return query;
                }
            }

            if ("get_weather".equals(action)) {
                return handleWeatherQuery(query);
            }
            if ("recommend_by_activity".equals(action)) {
                return handleRecommendByActivity(query, sessionId);
            }
            if ("list_pitches".equals(action) || "count_pitches_by_type".equals(action)
                    || "check_pitch_availability".equals(action) || "book_pitch".equals(action)
                    || "list_my_bookings".equals(action) || "cheapest_pitch".equals(action)
                    || "most_expensive_pitch".equals(action)) {
                return handlePitchQuery(query, userInput, sessionId, allPitches);
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

        try {
            String userId = null;
            if (sessionId != null) {
                UUID uid = userService.getUserIdBySession(sessionId);
                if (uid != null) userId = uid.toString();
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("raw_user_input", userInput);
            if (query.data != null && query.data.containsKey("action")) {
                metadata.put("intent_action", query.data.get("action"));
            }
            if (query.pitchType != null) metadata.put("requested_pitch_type", query.pitchType);
            if (query.environment != null) metadata.put("requested_environment", query.environment);

            logPublisherService.publishEvent(
                    userId, sessionId,
                    "CHAT_INTENT_RESOLVED",
                    null, null,
                    metadata, "AI_Chatbot"
            );
        } catch (Exception e) {
            System.err.println("Không thể ghi log AI Chat: " + e.getMessage());
        }

        if (query.message == null || query.message.isBlank()) {
            query.message = "Mình chưa hiểu rõ yêu cầu. Bạn muốn tìm sân bóng, đặt sân, hay mua sản phẩm thể thao? Ví dụ: \"cho xem các sân 5 người\" hoặc \"giày rẻ nhất\".";
        }

        return query;
    }

    private BookingQuery handlePitchQuery(BookingQuery query, String userInput, String sessionId, List<PitchResponseDTO> allPitches) {
        if (query.data == null) query.data = new HashMap<>();
        if (query.slotList == null) query.slotList = new ArrayList<>();
        if (query.pitchType == null) query.pitchType = "ALL";

        String action = (String) query.data.get("action");
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
                if (sessionId != null) sessionPitches.put(sessionId, picked);
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
                    query.message = String.format("Không có%s %s nào phù hợp để kiểm tra.", envStr, typeStr);
                    break;
                }
                if (query.bookingDate == null) {
                    query.message = "Bạn muốn kiểm tra sân trống ngày nào? Vui lòng cho mình biết ngày (vd: 2026-04-20).";
                    break;
                }
                try {
                    LocalDate date = LocalDate.parse(query.bookingDate);
                    List<Integer> desired = query.slotList.isEmpty() ? null : query.slotList;
                    List<Map<String, Object>> availability = new ArrayList<>();
                    for (PitchResponseDTO p : matched) {
                        List<Integer> booked = bookingService.getBookedTimeSlots(p.getPitchId(), date);
                        List<Integer> freeSlots = new ArrayList<>();
                        for (int s = 1; s <= 18; s++) if (!booked.contains(s)) freeSlots.add(s);
                        List<Integer> checkSlots = desired != null ? desired : freeSlots;
                        List<Integer> freeInRequested = checkSlots.stream().filter(freeSlots::contains).collect(Collectors.toList());
                        Map<String, Object> item = new HashMap<>();
                        item.put("pitchId", p.getPitchId());
                        item.put("name", p.getName());
                        item.put("address", p.getAddress());
                        item.put("availableSlots", freeInRequested);
                        availability.add(item);
                    }
                    query.data.put("availability", availability);
                    query.data.put("matchedPitches", matched);
                    long avail = availability.stream().filter(a -> !((List<?>) a.get("availableSlots")).isEmpty()).count();
                    String slotStr = desired != null ? " khung giờ " + desired : "";
                    query.message = String.format("Ngày %s: có %d/%d%s %s còn slot trống%s. Xem chi tiết bên dưới 👇",
                            query.bookingDate, avail, matched.size(), envStr, typeStr, slotStr);
                } catch (Exception e) {
                    query.message = "Ngày bạn cung cấp không hợp lệ. Vui lòng nhập theo dạng yyyy-MM-dd.";
                }
                break;
            }
            case "book_pitch": {
                if (matched.isEmpty()) {
                    query.message = String.format("Không tìm thấy%s %s nào phù hợp để đặt.", envStr, typeStr);
                    break;
                }
                PitchResponseDTO target = null;
                if (sessionId != null && sessionPitches.get(sessionId) != null) {
                    PitchResponseDTO cached = sessionPitches.get(sessionId);
                    if (matched.stream().anyMatch(p -> p.getPitchId().equals(cached.getPitchId()))) {
                        target = cached;
                    }
                }
                if (target == null) target = matched.get(0);

                query.message = String.format("Bạn muốn đặt sân \"%s\" (%s%s) tại %s. Nhấn nút bên dưới để chọn ngày và khung giờ nhé 👇",
                        target.getName(), typeStr, envStr, target.getAddress());
                query.data.put("pendingBooking", true);
                query.data.put("suggestedPitch", target);
                query.data.put("showBookingButton", true);
                if (query.bookingDate != null) query.data.put("bookingDate", query.bookingDate);
                if (!query.slotList.isEmpty()) query.data.put("slotList", query.slotList);
                query.data.put("matchedPitches", matched);
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
            case "list_pitches":
            default: {
                if (matched.isEmpty()) {
                    query.message = String.format("Rất tiếc, không tìm thấy%s %s nào phù hợp.", envStr, typeStr);
                } else {
                    query.message = String.format("Đã tìm thấy %d%s %s. Xem danh sách bên dưới 👇", matched.size(), envStr, typeStr);
                }
                query.data.put("matchedPitches", matched);
                break;
            }
        }

        try {
            String userIdStr = null;
            if (sessionId != null) {
                UUID uid = userService.getUserIdBySession(sessionId);
                if (uid != null) userIdStr = uid.toString();
            }
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("raw_user_input", userInput);
            metadata.put("pitch_action", action);
            metadata.put("matched_pitch_count", matched.size());
            metadata.put("requested_pitch_type", query.pitchType);
            if (requestedEnvironment != null) metadata.put("requested_environment", requestedEnvironment.name());
            if (query.bookingDate != null) metadata.put("booking_date", query.bookingDate);
            if (!query.slotList.isEmpty()) metadata.put("slot_list", query.slotList);
            logPublisherService.publishEvent(userIdStr, sessionId, "CHAT_INTENT_RESOLVED", null, null, metadata, "AI_Chatbot");
        } catch (Exception e) {
            System.err.println("Không thể ghi log AI Chat (pitch): " + e.getMessage());
        }

        if (query.message == null || query.message.isBlank()) {
            query.message = "Mình đã ghi nhận yêu cầu sân của bạn, nhưng chưa có dữ liệu phù hợp để trả lời.";
        }
        return query;
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

    private boolean isGreeting(String s) { return s.toLowerCase().matches(".*(xin chào|chào|hello).*"); }

    private static final String DATA_ENRICHMENT_SYSTEM_PROMPT = """
        Bạn là chuyên gia quản lý kho hàng thời trang (Inventory Manager).
        Nhiệm vụ: Phân tích hình ảnh sản phẩm và sinh ra danh sách từ khóa (Tags) chi tiết để phục vụ tìm kiếm.
        
        HÃY QUAN SÁT KỸ VÀ TRẢ VỀ JSON CHỨA DANH SÁCH TAGS:
        1. **Thương hiệu**: Nhìn logo/chữ trên sản phẩm (Nike, Adidas, Puma...).
        2. **Dòng sản phẩm**: Tên cụ thể (Air Max, Jordan, Ultraboost, Stan Smith...).
        3. **Màu sắc**: Liệt kê TẤT CẢ màu nhìn thấy (Tiếng Việt + Tiếng Anh). VD: ["trắng", "white", "cam", "orange"].
        4. **Đặc điểm hình dáng**: 
           - Giày: Cổ cao/thấp, đế air, đế bằng, dây buộc, không dây...
           - Áo/Quần: Tay dài/ngắn, cổ tròn/tim, có mũ...
        5. **Chất liệu**: Da, vải lưới, nỉ, cotton...
        
        YÊU CẦU OUTPUT JSON:
        {
          "tags": ["danh sách khoảng 15-20 từ khóa, viết thường, bao gồm cả tiếng Anh và tiếng Việt"]
        }
        """;

    public List<String> generateTagsForProduct(String imageUrl) {
        try {
            waitIfNeeded();
            String base64Image = downloadImageAsBase64(imageUrl);
            if (base64Image == null) return new ArrayList<>();

            ObjectNode rootNode = mapper.createObjectNode();
            ObjectNode systemInstNode = rootNode.putObject("system_instruction");
            systemInstNode.putObject("parts").put("text", DATA_ENRICHMENT_SYSTEM_PROMPT);

            ArrayNode contentsArray = rootNode.putArray("contents");
            ObjectNode userMessage = contentsArray.addObject();
            userMessage.put("role", "user");
            ArrayNode parts = userMessage.putArray("parts");
            parts.addObject().put("text", "Hãy sinh tags cho sản phẩm này.");

            ObjectNode inlineData = parts.addObject().putObject("inline_data");
            inlineData.put("mime_type", "image/jpeg");
            inlineData.put("data", base64Image);

            ObjectNode generationConfig = rootNode.putObject("generationConfig");
            generationConfig.put("temperature", 0.1);
            generationConfig.put("response_mime_type", "application/json");

            Request request = new Request.Builder()
                    .url(GEMINI_API_URL + GOOGLE_API_KEY)
                    .post(RequestBody.create(mapper.writeValueAsString(rootNode), MediaType.parse("application/json")))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) return new ArrayList<>();
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

    private String downloadImageAsBase64(String imageUrl) {
        try {
            Request request = new Request.Builder().url(imageUrl).build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return null;
                byte[] imageBytes = response.body().bytes();
                return Base64.getEncoder().encodeToString(imageBytes);
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
        
        2. PHÂN TÍCH MÀU SẮC (RẤT QUAN TRỌNG):
        - Đừng chỉ chọn 1 màu. Hãy liệt kê **TẤT CẢ** màu sắc nhìn thấy.
        - Phân biệt: Màu chủ đạo (Dominant) và Màu phối (Accent).
        - Ví dụ: Giày trắng logo đỏ -> Tags phải có cả "trắng", "white", "đỏ", "red".
        - Các màu tương đồng: Nếu thấy "kem/cream/beige" -> Hãy thêm tag "trắng/white". Nếu thấy "xanh dương/navy" -> Thêm tag "xanh/blue".
        
        3. ĐỌC CHỮ (OCR):
        - Cố gắng đọc tên dòng sản phẩm trên thân/lưỡi gà (VD: Air Max, Jordan, Ultraboost).
        
        YÊU CẦU OUTPUT JSON:
        {
          "majorCategory": "FOOTWEAR",
          "productName": "Tên gợi ý (VD: Nike Air Max 1 White/Orange)",
          "color": "Mô tả màu (VD: Trắng phối Cam)",
          "tags": ["danh sách tags: nike, air max, trắng, white, cam, orange, giày, sneaker..."]
        }
        """;

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
          "data": {
            "action": "get_weather" | "check_stock" | "check_sales" | "check_size" | "prepare_order" | "list_on_sale" | "count_on_sale" | "max_discount_product" | "best_selling_product" | "search_by_price_range" | "cheapest_product" | "most_expensive_product" | "product_detail" | "list_pitches" | "count_pitches_by_type" | "check_pitch_availability" | "book_pitch" | "list_my_bookings" | "cheapest_pitch" | "most_expensive_pitch" | null,
            "productName": "...",
            "city": "...",
            "size": "...",
            "quantity": 1,
            "categoryKeyword": "...",
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
        - Slot 1-18 tương ứng 6h-24h (mỗi slot 1 tiếng).
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

        ❗️ QUY TẮC XỬ LÝ SẢN PHẨM:
        - Nếu hỏi về giá sản phẩm (rẻ nhất/mắc nhất), dùng action "cheapest_product" hoặc "most_expensive_product".
        - Cung cấp "categoryKeyword" dựa trên bảng mapping:
          + nón, mũ -> "Hats And Headwears"
          + tất, vớ -> "Socks"
          + balo, túi -> "Bags And Backpacks"
          + áo khoác -> "Jackets And Gilets"
          + hoodie -> "Hoodies And Sweatshirts"
          + giày đá banh -> "Football Shoes"
          + giày (chung) -> "Shoes"
          + quần áo -> "Clothing"
        - Tìm kiếm theo giá: dùng action "search_by_price_range", cung cấp minPrice, maxPrice.
        
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

        @Override
        public String toString() {
            return "BookingQuery{" +
                    "bookingDate='" + bookingDate + '\'' +
                    ", slotList=" + slotList +
                    ", pitchType='" + pitchType + '\'' +
                    ", message='" + message + '\'' +
                    ", data=" + data +
                    ",  environment='" + environment + '\'' +
                    '}';
        }
    }

    public PitchResponseDTO findPitchByContext(String userInput) {
        List<PitchResponseDTO> pitches = pitchService.getAllPitches(PageRequest.of(0, 50), null, null, null).getContent();
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