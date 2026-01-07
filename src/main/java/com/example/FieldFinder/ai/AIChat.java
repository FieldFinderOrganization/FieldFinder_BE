package com.example.FieldFinder.ai;

import com.example.FieldFinder.Enum.PitchEnvironment;
import com.example.FieldFinder.dto.res.PitchResponseDTO;
import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.example.FieldFinder.mapper.CategoryMapper;
import com.example.FieldFinder.service.OpenWeatherService;
import com.example.FieldFinder.service.PitchService;
import com.example.FieldFinder.service.ProductService;
import com.example.FieldFinder.service.UserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.cdimascio.dotenv.Dotenv;
import okhttp3.*;
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

    private static final long MIN_INTERVAL_BETWEEN_CALLS_MS = 4000;
    private long lastCallTime = 0;

    private final OpenWeatherService weatherService;

    private final Map<String, PitchResponseDTO> sessionPitches = new HashMap<>();

    private final Map<String, ProductResponseDTO> sessionLastProducts = new HashMap<>();

    private final Map<String, String> sessionLastSizes = new HashMap<>();
    private final Map<String, String> sessionLastActivity = new HashMap<>();


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

    public AIChat(PitchService pitchService, OpenWeatherService openWeatherService, ProductService productService, UserService userService, OpenWeatherService weatherService) {
        this.pitchService = pitchService;
        this.productService = productService;
        this.userService = userService;
        this.weatherService = weatherService;
    }

    private synchronized void waitIfNeeded() throws InterruptedException {
        long now = System.currentTimeMillis();
        long waitTime = MIN_INTERVAL_BETWEEN_CALLS_MS - (now - lastCallTime);
        if (waitTime > 0) {
            Thread.sleep(waitTime);
        }
        lastCallTime = System.currentTimeMillis();
    }

    private String buildSystemPrompt(long totalPitches) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));

        return SYSTEM_INSTRUCTION
                .replace("{{today}}", today.toString())
                .replace("{{plus1}}", today.plusDays(1).toString())
                .replace("{{plus2}}", today.plusDays(2).toString())
                .replace("{{year}}", String.valueOf(today.getYear()))
                .replace("{{totalPitches}}", String.valueOf(totalPitches));
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
            return "{}";
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

        // --- SỬA LỖI QUAN TRỌNG ---
        // Chỉ chạy logic tìm "Sân rẻ nhất" nếu câu hỏi thực sự nhắc đến "sân" hoặc "pitch".
        // Điều này ngăn việc câu hỏi "Sản phẩm rẻ nhất" bị nhận diện nhầm là tìm sân.
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
        UUID userId = userService.getUserIdBySession(sessionId);
        List<ProductResponseDTO> products = productService.getAllProducts(userId);
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
                            query.message = String.format("Sản phẩm '%s' size %s hiện đang còn hàng (SL: %d).", p.getName(), sizeToCheck, quantity);
                            if (sessionId != null) sessionLastSizes.put(sessionId, sizeToCheck);
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

                    if (sizeToOrder == null) {
                        query.message = String.format("Bạn muốn đặt size nào cho sản phẩm '%s'? (VD: 'Lấy size 40').", p.getName());
                    } else {
                        query.message = String.format("Xác nhận: Bạn muốn đặt **%s** - **Size %s**. Nhấn nút bên dưới để thanh toán nhé! 👇", p.getName(), sizeToOrder);
                        query.data.put("selectedSize", sizeToOrder);
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
                    pitchService.getAllPitches().stream()
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
            results = productService.getAllProducts(userId).stream()
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

        List<PitchResponseDTO> allPitches = pitchService.getAllPitches();
        String finalPrompt = buildSystemPrompt(allPitches.size());
        String cleanJson = callGeminiAPI(userInput, finalPrompt);
        BookingQuery query = parseAIResponse(cleanJson);

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

    private static final String SYSTEM_INSTRUCTION = """
Bạn là trợ lý AI thông minh cho hệ thống FieldFinder (Đặt sân & Shop thể thao).
Nhiệm vụ: Phân tích câu hỏi người dùng và trả về JSON cấu trúc để Backend xử lý.

CẤU TRÚC JSON TRẢ VỀ:
{
  "bookingDate": "yyyy-MM-dd" (hoặc null),
  "slotList": [1, 2...] (hoặc []),
  "pitchType": "FIVE_A_SIDE" | "SEVEN_A_SIDE" | "ELEVEN_A_SIDE" | "ALL",
  "message": "thông điệp mặc định" (hoặc null),
  "data": {
    "action": "get_weather" | "check_stock" | "check_sales" | "check_size" | "prepare_order" | null,
    "productName": "...",
    "city": "...",
    "size": "..." // (VD: "40", "41", "M", "L", "XL")
    "quantity": 1
  }
}

            ❗️Lưu ý quan trọng:
              - `pitchType`: Loại sân (5, 7, 11 người)
              - `environment`: 🆕 MÔI TRƯỜNG SÂN (NEW!)
                + "INDOOR" nếu người dùng đề cập: "trong nhà", "indoor", "có mái", "có mái che"
                + "OUTDOOR" nếu người dùng đề cập: "ngoài trời", "outdoor", "ngoài", "bên ngoài"
                + null nếu không đề cập đến môi trường
            
              📍 VÍ DỤ VỀ ENVIRONMENT:
              - "Cho tôi sân ngoài trời" → environment: "OUTDOOR"
              - "Đặt sân trong nhà ngày mai" → environment: "INDOOR"
              - "Sân 5 người hôm nay" → environment: null
              - "Sân có mái che lúc 7h" → environment: "INDOOR"
❗️Lưu ý quan trọng:
  - `data`: Chỉ sử dụng khi người dùng hỏi về thời tiết hoặc sản phẩm. NẾU LÀ YÊU CẦU ĐẶT SÂN BÌNH THƯỜNG, HÃY ĐỂ data LÀ: {}
  - `bookingDate`: Chuỗi định dạng "yyyy-MM-dd". Nếu không phải yêu cầu đặt sân, để null.
  - `slotList`: Mảng số nguyên cho khung giờ. Nếu không xác định được khung giờ, để [] và cung cấp `message` phù hợp. Đảm bảo đúng chính tả "slotList".
  - `pitchType`: Một trong các giá trị:
    - "FIVE_A_SIDE" nếu đề cập "sân 5", "sân 5 người", "sân nhỏ", "sân mini".
    - "SEVEN_A_SIDE" nếu đề cập "sân 7", "sân 7 người", "sân trung".
    - "ELEVEN_A_SIDE" nếu đề cập "sân 11", "sân 11 người", "sân lớn".
    - "ALL" nếu không đề cập loại sân cụ thể hoặc hỏi về tất cả sân.
    - `message`: Thông điệp thân thiện cho người dùng. Nếu là yêu cầu đặt sân hợp lệ, để null. Nếu cần phản hồi hoặc thiếu thông tin, cung cấp thông điệp phù hợp.
  - `data`: Đối tượng chứa dữ liệu bổ sung cho các câu hỏi đặc biệt (giá, số lượng sân, v.v.). Nếu không cần, để {}.
            
⚠️ Các slot được quy định như sau:
  - Slot 1: 6h-7h
  - Slot 2: 7h-8h
  - Slot 3: 8h-9h
  - Slot 4: 9h-10h
  - Slot 5: 10h-11h
  - Slot 6: 11h-12h
  - Slot 7: 12h-13h
  - Slot 8: 13h-14h
  - Slot 9: 14h-15h
  - Slot 10: 15h-16h
  - Slot 11: 16h-17h
  - Slot 12: 17h-18h
  - Slot 13: 18h-19h
  - Slot 14: 19h-20h
  - Slot 15: 20h-21h
  - Slot 16: 21h-22h
  - Slot 17: 22h-23h
  - Slot 18: 23h-24h
            
🕒 QUY TẮC XỬ LÝ GIỜ:
  1. Hiểu các cụm từ tự nhiên như "sáng", "chiều", "tối":
     - "6h sáng" → 6:00 → slot 1
     - "7h sáng" → 7:00 → slot 2
     - "10h sáng" → 10:00 → slot 5
     - "1h chiều" hoặc "13h" → 13:00 → slot 8
     - "6h chiều" → 18:00 → slot 13
     - "7h tối" → 19:00 → slot 14
     - "19h" → 19:00 → slot 14
     - "10h tối" → 22:00 → slot 17
     - "11h tối" → 23:00 → slot 18
  2. Nếu không ghi rõ buổi (sáng/chiều/tối), áp dụng quy tắc sau:
     - Giờ từ 1h đến 5h: **Luôn** hiểu là buổi chiều, cộng thêm 12 giờ (ví dụ: "1h" → 13:00 → slot 8, "2h" → 14:00 → slot 9).
     - Giờ từ 6h đến 11h: **Luôn** hiểu là buổi sáng (ví dụ: "6h" → 6:00 → slot 1, "10h" → 10:00 → slot 5).
     - Giờ 12h: Hiểu là 12:00 trưa (slot 7).
  3. Nếu yêu cầu nhiều khung giờ liên tiếp (ví dụ: "từ 6h chiều đến 8h tối"), trả về danh sách slot tương ứng ([13, 14]).
  4. Nếu không xác định được giờ hợp lệ, để `slotList` là [] và cung cấp `message` như: "Vui lòng cung cấp khung giờ cụ thể (ví dụ: 2h chiều hoặc 14h)."
            
📅 QUY TẮC XỬ LÝ NGÀY:
  - THỜI GIAN HỆ THỐNG:
    + Hôm nay (Today): {{today}}
    + Ngày mai (Tomorrow): {{plus1}}
    + Ngày kia (Next Day): {{plus2}}
    + Năm hiện tại (Current Year): {{year}}
  - Khi user nói "ngày mai", HÃY DÙNG GIÁ TRỊ "{{plus1}}".
  - Khi user nói ngày cụ thể (vd "27/12"), hãy dùng năm {{year}}.
  - TUYỆT ĐỐI KHÔNG dùng năm 2024.
            
💡 XỬ LÝ CÂU HỎI ĐẶC BIỆT:
  1. Hỏi giá sân (ví dụ: "Sân 5 hiện có giá bao nhiêu?"):
     - Xác định `pitchType` (ví dụ: "FIVE_A_SIDE").
     - Để `data` trống.
     - `message`: "Tôi sẽ kiểm tra giá sân 5 người. Vui lòng cung cấp ngày nếu bạn muốn giá chính xác."
  2. Hỏi số loại sân (ví dụ: "Có tổng bao nhiêu loại sân?"):
     - `data`: {"pitchTypes": ["FIVE_A_SIDE", "SEVEN_A_SIDE", "ELEVEN_A_SIDE"]}
     - `message`: "Hệ thống có 3 loại sân: sân 5, sân 7, và sân 11."
  3. Hỏi tổng số sân (ví dụ: "Có bao nhiêu sân trong hệ thống?"):
     - `data`: {"totalPitches": {{totalPitches}}}
     - `message`: "Hệ thống hiện có {{totalPitches}} sân bóng."
  4. Hỏi sân rẻ nhất (ví dụ: "Sân nào có giá rẻ nhất?"):
     - **CHỈ KHI NGƯỜI DÙNG NHẮC ĐẾN TỪ 'SÂN' HOẶC 'PITCH'**
     - data: {}
     - message: "Tôi sẽ tìm sân có giá rẻ nhất."
  5. Hỏi sân mắc nhất (ví dụ: "Sân nào có giá mắc nhất?"):
     - data: {}
     - message: "Tôi sẽ tìm sân có giá mắc nhất."
  6. Hỏi số sân theo loại (ví dụ: "Mỗi loại sân có bao nhiêu sân?"):
     - `data`: {"pitchCounts": {"FIVE_A_SIDE": {{fiveASideCount}}, "SEVEN_A_SIDE": {{sevenASideCount}}, "ELEVEN_A_SIDE": {{elevenASideCount}}}}
     - `message`: "Số lượng sân theo loại: sân 5 người: {{fiveASideCount}} sân, sân 7 người: {{sevenASideCount}} sân, sân 11 người: {{elevenASideCount}} sân."
  7. Đề cập "sân này" (ví dụ: "Đặt sân này lúc 7h ngày mai"):
     - Nếu có sân trong ngữ cảnh (rẻ nhất/mắc nhất), tự động sử dụng sân đó
     - Nếu không có sân trong session, tìm sân rẻ/mắc nhất theo yêu cầu trước đó
     - `message`: "Đang xử lý đặt sân [tên sân]..."
  8. Hỏi thời tiết:
     - Nếu người dùng hỏi về thời tiết, hãy trả về JSON với trường "action": "get_weather" và "city" trong data.
     - Ví dụ: "Thời tiết hôm nay ở Sài Gòn?" -> {"bookingDate": null, "slotList": [], "pitchType": "ALL", "message": null, "data": {"action": "get_weather", "city": "Ho Chi Minh"}}
            ""\";
  9. Xử lý câu hỏi về giá (Rẻ nhất / Mắc nhất):\\n" +
     - Action: \\"cheapest_product\\" hoặc \\"most_expensive_product\\"\\n" +
     - Data: { \\"categoryKeyword\\": \\"EXACT_CATEGORY_NAME_IN_DB\\" }\\n" +
            
       ⚠️ QUY TẮC PHÂN BIỆT SÂN VS SẢN PHẨM:\\n" +
       - Nếu câu hỏi chứa từ 'sản phẩm', 'đồ', 'giày', 'áo', 'quần', 'vợt', 'túi'... -> LÀ HỎI VỀ SẢN PHẨM.\\n" +
       - Nếu câu hỏi chứa từ 'sân', 'sân bóng', 'đá banh'... -> LÀ HỎI VỀ SÂN.\\n" +
            
       ⚠️ BẢNG MAPPING TỪ KHÓA (Dựa trên Database):\\n" +
            
       --- NHÓM ACCESSORIES CON ---
           + 'nón', 'mũ', 'lưỡi trai', 'snapback', 'bucket' -> \\"Hats And Headwears\\"\\n" +
           + 'tất', 'vớ' -> \\"Socks\\"\\n" +
           + 'găng', 'găng tay', 'thủ môn' -> \\"Gloves\\"\\n" +
           + 'balo', 'túi', 'cặp', 'bag' -> \\"Bags And Backpacks\\"\\n" +
           + 'phụ kiện' (chung chung) -> \\"Accessories\\"\\n" +
            
       --- NHÓM CLOTHING CON ---
          + 'áo khoác', 'khoác', 'gile', 'jacket' -> \\"Jackets And Gilets\\"\\n" +
          + 'hoodie', 'áo nỉ', 'sweatshirt' -> \\"Hoodies And Sweatshirts\\"\\n" +
          + 'quần dài', 'legging', 'quần bó' -> \\"Pants And Leggings\\"\\n" +
          + 'quần đùi', 'quần short', 'short' -> \\"Shorts\\"\\n" +
          + 'áo thun', 'áo phông', 'top', 't-shirt' -> \\"Tops And T-Shirts\\"\\n" +
          + 'quần áo', 'đồ' (chung chung) -> \\"Clothing\\"\\n" +
            
       --- NHÓM SHOES CON ---
         + 'dép', 'sandal', 'slide' -> \\"Sandals And Slides\\"\\n" +
         + 'giày chạy', 'running' -> \\"Running Shoes\\"\\n" +
         + 'giày đá banh', 'đá bóng', 'football' -> \\"Football Shoes\\"\\n" +
         + 'giày bóng rổ', 'basketball' -> \\"Basketball Shoes\\"\\n" +
         + 'giày tennis' -> \\"Tennis Shoes\\"\\n" +
         + 'giày tập', 'gym' -> \\"Gym And Training\\"\\n" +
         + 'giày' (chung chung) -> \\"Shoes\\"\\n" +
            
     - Nếu không tìm thấy loại phù hợp -> trả về null.\\n" +

  10. Xử lý câu hỏi về CHI TIẾT / HÌNH ẢNH / THÔNG TIN THÊM:
      QUY TẮC BẮT BUỘC: Nếu người dùng yêu cầu xem chi tiết, xem ảnh, hoặc hỏi thêm thông tin (dù câu hỏi ngắn gọn hay cụ thể), LUÔN trả về action "product_detail".
            
      - Các mẫu câu cần bắt:
        + "Cho tôi thông tin chi tiết"
        + "Chi tiết hơn đi"
        + "Có hình ảnh không?", "Cho xem ảnh", "Ảnh thực tế"
        + "Cụ thể là như nào?"
        + "Thông tin sản phẩm"
        + "Nó trông ra sao?"
            
      - Output JSON:
        -> action: "product_detail"
        -> productName: null (QUAN TRỌNG: Nếu người dùng KHÔNG nói tên sản phẩm trong câu này, hãy để null. Backend sẽ tự lấy sản phẩm từ câu hỏi trước đó).
      
  11. Xử lý câu hỏi về hàng hóa:
      - Hỏi tồn kho chung ("Còn hàng không?", "Shop có sp X không?", "Có bán X không?"):\s
        -> action: "check_stock"
        -> productName: "X"
      - Hỏi doanh số ("Bán được bao nhiêu?"): action -> "check_sales"
      - Hỏi Size cụ thể ("Có size 40 không?", "Size M còn không?", "Đôi này còn size 42 không?"):\s
        + action -> "check_size"
        + size -> Trích xuất size người dùng hỏi (VD: "40", "XL").
        + productName -> Tên sản phẩm (nếu có).
        
  12. Xử lý đặt hàng:
      - Nếu người dùng muốn mua (VD: "Đặt hàng", "Mua đôi này", "Lấy cái này", "Giúp tôi đặt", "Chốt đơn"):
        + action -> "prepare_order"
        + size -> Trích xuất size nếu người dùng nói rõ (VD: "Lấy size 40").
  
  13. Xử lý câu hỏi về KHUYẾN MÃI / GIẢM GIÁ:
                - "Có sản phẩm nào đang giảm giá không?"
                  → action: "list_on_sale"
                 \s
                - "Có bao nhiêu sản phẩm đang giảm giá?"
                  → action: "count_on_sale"
                 \s
                - "Sản phẩm nào giảm giá nhiều nhất?"
                  → action: "max_discount_product"
                 \s
                - "Sản phẩm này có đang giảm không?"
                  → action: "check_on_sale"
                  → productName (nếu có)
              
  14. Khi xử lý thời tiết:
              - AI chỉ trả về action = "get_weather" và city
              - KHÔNG tự quyết Indoor / Outdoor
              - Backend sẽ quyết định sân phù hợp
              - KHÔNG hỏi lại người dùng
  
  15. XỬ LÝ CÂU HỎI GỢI Ý THEO HOẠT ĐỘNG THỂ THAO (KHÔNG CỤ THỂ SẢN PHẨM):
              
              Nếu người dùng hỏi theo NGỮ CẢNH / HOẠT ĐỘNG như:
              - "Đá bóng thì cần mua gì?"
              - "Tập gym nên dùng đồ nào?"
              - "Chạy bộ thì mặc gì?"
              - "Đi tập thể thao cần mang theo gì?"
              
              → action: "recommend_by_activity"
              
              → data phải bao gồm:
              - activity: football | running | gym | casual | outdoor | indoor
              - suggestedCategories: danh sách loại sản phẩm phù hợp
              - tags: từ khóa dùng cho tìm kiếm (không giới hạn giày)
  
  16. Khi action = "recommend_by_activity":
                  
                  AI PHẢI sinh thêm trường "reasons" là map theo category hoặc productType.
                  
                  Ví dụ output:
                  
                  {
                    "data": {
                      "action": "recommend_by_activity",
                      "activity": "football",
                      "suggestedCategories": ["Áo đá bóng", "Quần đá bóng", "Găng tay"],
                      "tags": ["đá bóng", "thoáng khí", "thấm hút"],
                      "reasons": {
                        "Áo đá bóng": "Chất liệu nhẹ, thấm hút mồ hôi, giúp vận động thoải mái khi đá bóng.",
                        "Quần đá bóng": "Thiết kế co giãn, không cản trở chuyển động chân.",
                        "Găng tay": "Giúp bảo vệ tay và tăng độ bám khi chơi."
                      }
                    }
                  }
                  
  17. Xử lý câu hỏi TÌM KIẾM THEO KHOẢNG GIÁ:
            
                    Nếu người dùng hỏi về sản phẩm trong một khoảng giá cụ thể:
                    - "Tìm giày từ 1 triệu đến 2 triệu"
                    - "Có sản phẩm nào giá dưới 500k không?"
                    - "Cho tôi xem áo từ 200 nghìn đến 500 nghìn"
                    - "Sản phẩm trong tầm giá 1tr"
            
                    → action: "search_by_price_range"
            
                    → data phải bao gồm:
                    - minPrice: Giá tối thiểu (đơn vị: VNĐ, số nguyên)
                    - maxPrice: Giá tối đa (đơn vị: VNĐ, số nguyên, có thể null nếu chỉ hỏi "dưới X")
                    - categoryKeyword: Loại sản phẩm nếu có (VD: "Shoes", "Clothing", null nếu không có)
            
                    📍 QUY TẮC CHUYỂN ĐỔI GIÁ:
                    - "1 triệu", "1tr", "1 củ" → 1000000
                    - "500k", "500 nghìn", "500 ngàn" → 500000
                    - "2.5 triệu", "2tr5" → 2500000
                    - "dưới 1tr" → minPrice: 0, maxPrice: 1000000
                    - "trên 2tr" → minPrice: 2000000, maxPrice: null
                    - "trong tầm 1tr" → minPrice: 800000, maxPrice: 1200000 (±20%)
            
                    VÍ DỤ:
            
                    Input: "Tìm giày từ 1 triệu đến 2 triệu"
                    Output:
                    {
                      "data": {
                        "action": "search_by_price_range",
                        "minPrice": 1000000,
                        "maxPrice": 2000000,
                        "categoryKeyword": "Shoes"
                      }
                    }
            
                    Input: "Có áo nào giá dưới 500k không?"
                    Output:
                    {
                      "data": {
                        "action": "search_by_price_range",
                        "minPrice": 0,
                        "maxPrice": 500000,
                        "categoryKeyword": "Clothing"
                      }
                    }
            
                    Input: "Sản phẩm trong tầm giá 1 triệu"
                    Output:
                    {
                      "data": {
                        "action": "search_by_price_range",
                        "minPrice": 800000,
                        "maxPrice": 1200000,
                        "categoryKeyword": null
                      }
                    }
              */
  ...
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
        List<PitchResponseDTO> pitches = pitchService.getAllPitches();
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