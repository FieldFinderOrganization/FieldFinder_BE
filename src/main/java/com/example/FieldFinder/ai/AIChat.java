package com.example.FieldFinder.ai;

import com.example.FieldFinder.dto.res.PitchResponseDTO;
import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.example.FieldFinder.service.OpenWeatherService;
import com.example.FieldFinder.service.PitchService;
import com.example.FieldFinder.service.ProductService;
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
import java.util.*;
import java.util.stream.Collectors;

@Component
public class AIChat {

    private static final String GOOGLE_API_KEY;

    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=";

    private final OkHttpClient client = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final PitchService pitchService;
    private final ProductService productService;

    private static final long MIN_INTERVAL_BETWEEN_CALLS_MS = 4000;
    private long lastCallTime = 0;

    private final OpenWeatherService weatherService;

    private final Map<String, PitchResponseDTO> sessionPitches = new HashMap<>();

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

    public AIChat(PitchService pitchService, OpenWeatherService openWeatherService, ProductService productService, OpenWeatherService weatherService) {
        this.pitchService = pitchService;
        this.productService = productService;
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
        LocalDate today = LocalDate.now();
        return SYSTEM_INSTRUCTION
                .replace("{{today}}", today.toString())
                .replace("{{plus1}}", today.plusDays(1).toString())
                .replace("{{plus2}}", today.plusDays(2).toString())
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

    public BookingQuery processImageSearchWithGemini(String base64Image) {
        BookingQuery result = new BookingQuery();
        result.data = new HashMap<>();
        result.slotList = new ArrayList<>();
        result.pitchType = "ALL";

        try {
            waitIfNeeded();

            ObjectNode rootNode = mapper.createObjectNode();

            // System Prompt
            ObjectNode systemInstNode = rootNode.putObject("system_instruction");
            systemInstNode.putObject("parts").put("text", IMAGE_ANALYSIS_SYSTEM_PROMPT);

            ArrayNode contentsArray = rootNode.putArray("contents");
            ObjectNode userMessage = contentsArray.addObject();
            userMessage.put("role", "user");
            ArrayNode parts = userMessage.putArray("parts");

            // 1. Gửi Text yêu cầu
            parts.addObject().put("text", "Phân tích ảnh này và trích xuất Tags.");

            if (base64Image != null && !base64Image.isEmpty()) {
                ObjectNode inlineData = parts.addObject().putObject("inline_data");

                // Tự động phát hiện mime type (jpeg/png) dựa trên header của chuỗi base64
                String mimeType = "image/jpeg"; // Mặc định
                String cleanBase64 = base64Image;

                if (base64Image.contains(",")) {
                    String[] tokens = base64Image.split(",");
                    // tokens[0] ví dụ: "data:image/png;base64"
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
                if (!response.isSuccessful()) { /* ... xử lý lỗi ... */ }

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

                List<ProductResponseDTO> similarProducts = productService.findProductsByImage(cleanTags, majorCategory);

                if (!similarProducts.isEmpty()) {
                    result.message = String.format("Dựa trên hình ảnh %s (%s), tôi tìm thấy %d sản phẩm tương tự:",
                            productName, color, similarProducts.size());
                    result.data.put("action", "image_search_result");
                    result.data.put("products", similarProducts);
                    result.data.put("extractedTags", cleanTags);
                } else {
                    result.message = String.format(
                            "Tôi nhận diện được đây là %s màu %s. Tuy nhiên, hiện tại cửa hàng không có sản phẩm nào khớp với các đặc điểm này.",
                            productName, color
                    );

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
        // Xử lý sân rẻ nhất/mắc nhất
        if (query.message != null) {
            if (query.message.contains("giá rẻ nhất") || query.message.contains("giá mắc nhất")) {
                PitchResponseDTO selectedPitch = findPitchByPrice(allPitches,
                        query.message.contains("giá rẻ nhất"));

                if (selectedPitch != null) {
                    sessionPitches.put(sessionId, selectedPitch);
                    query.data.put("selectedPitch", selectedPitch);
                }
            }
        }

        // Xử lý "sân này" với fallback
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

    private BookingQuery handleProductQuery(BookingQuery query, String userInput) {
        List<ProductResponseDTO> products = productService.getAllProducts();
        String action = (String) query.data.get("action");
        String productName = (String) query.data.get("productName"); // Tên sản phẩm user hỏi

        if ("cheapest_product".equals(action)) {
            ProductResponseDTO p = products.stream().min(Comparator.comparing(ProductResponseDTO::getPrice)).orElse(null);
            if (p != null) {
                query.message = String.format("Sản phẩm rẻ nhất là %s với giá %s VNĐ.", p.getName(), formatMoney(p.getPrice()));
                query.data.put("product", p);
            }
        } else if ("most_expensive_product".equals(action)) {
            ProductResponseDTO p = products.stream().max(Comparator.comparing(ProductResponseDTO::getPrice)).orElse(null);
            if (p != null) {
                query.message = String.format("Sản phẩm đắt nhất là %s với giá %s VNĐ.", p.getName(), formatMoney(p.getPrice()));
                query.data.put("product", p);
            }
        } else if ("best_selling_product".equals(action)) {
            // Giả sử bạn đã implement hàm getTopSellingProducts
            List<ProductResponseDTO> top = productService.getTopSellingProducts(1);
            if (!top.isEmpty()) {
                ProductResponseDTO p = top.get(0);
                query.message = String.format("Sản phẩm bán chạy nhất là %s.", p.getName());
                query.data.put("product", p);
            } else {
                query.message = "Chưa có dữ liệu về sản phẩm bán chạy.";
            }
        } else if ("product_detail".equals(action) && productName != null) {
            // Tìm sản phẩm theo tên (fuzzy search đơn giản)
            Optional<ProductResponseDTO> productOpt = products.stream()
                    .filter(p -> p.getName().toLowerCase().contains(productName.toLowerCase()))
                    .findFirst();

            if (productOpt.isPresent()) {
                ProductResponseDTO p = productOpt.get();
                StringBuilder detail = new StringBuilder();
                detail.append(String.format("Sản phẩm: %s\n", p.getName()));
                detail.append(String.format("Thương hiệu: %s\n", p.getBrand()));
                detail.append(String.format("Giá: %s VNĐ\n", formatMoney(p.getPrice())));

                if (p.getVariants() != null && !p.getVariants().isEmpty()) {
                    detail.append("Các size hiện có: ");
                    String sizes = p.getVariants().stream()
                            .map(v -> v.getSize() + " (Còn " + v.getQuantity() + ")")
                            .collect(Collectors.joining(", "));
                    detail.append(sizes);
                } else {
                    detail.append("Hiện hết hàng hoặc chưa cập nhật size.");
                }

                query.message = detail.toString();
                query.data.put("product", p);
            } else {
                query.message = "Xin lỗi, tôi không tìm thấy sản phẩm nào có tên \"" + productName + "\".";
            }
        } else if ("check_stock".equals(action) && productName != null) {
            Optional<ProductResponseDTO> productOpt = products.stream()
                    .filter(p -> p.getName().toLowerCase().contains(productName.toLowerCase()))
                    .findFirst();

            if (productOpt.isPresent()) {
                int total = productOpt.get().getStockQuantity();
                if (total > 0) {
                    query.message = String.format("Sản phẩm %s còn hàng (Tổng: %d).", productOpt.get().getName(), total);
                } else {
                    query.message = String.format("Sản phẩm %s hiện đã hết hàng.", productOpt.get().getName());
                }
            } else {
                query.message = "Không tìm thấy sản phẩm.";
            }
        }

        return query;
    }

    private String formatMoney(Double amount) {
        return String.format("%,.0f", amount);
    }

    private BookingQuery handleWeatherQuery(BookingQuery query) {
        String city = query.data.getOrDefault("city", "Hà Nội").toString();

        try {
            String weatherDescription = weatherService.getCurrentWeather(city);

            query.message = String.format("Thời tiết ở %s hiện là: %s", city, weatherDescription);
            query.data.clear(); // Xóa data tool
            return query;
        } catch (IOException e) {
            query.message = String.format("Xin lỗi, tôi không thể lấy dữ liệu thời tiết cho %s lúc này.", city);
            query.data.clear();
            return query;
        }
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
        // Cập nhật prompt
        String finalPrompt = buildSystemPrompt(allPitches.size());

        String cleanJson = callGeminiAPI(userInput, finalPrompt);
        System.out.println("Cleaned JSON: " + cleanJson);

        BookingQuery query = parseAIResponse(cleanJson);

        // Điều phối logic dựa trên action của AI
        if (query.data != null && query.data.containsKey("action")) {
            String action = (String) query.data.get("action");

            if ("get_weather".equals(action)) {
                return handleWeatherQuery(query);
            }
            // Các action liên quan đến Product
            if (action.contains("product") || action.contains("stock")) {
                return handleProductQuery(query, userInput);
            }
        }

        // Logic sân bóng cũ
        if (isTotalPitchesQuestion(userInput)) {
            int totalPitches = pitchService.getAllPitches().size();
            return createBasicResponse("Hệ thống hiện có " + totalPitches + " sân");
        }
        if (isPitchTypesQuestion(userInput)) return handlePitchTypesQuestion();
        if (isPitchCountByTypeQuestion(userInput)) return handlePitchCountByTypeQuestion();

        processSpecialCases(userInput, sessionId, query, allPitches);

        return query;
    }

    private BookingQuery handlePitchCountByTypeQuestion() {
        List<PitchResponseDTO> allPitches = pitchService.getAllPitches();

        Map<String, Long> pitchCounts = allPitches.stream()
                .collect(Collectors.groupingBy(
                        p -> p.getType().name(),
                        Collectors.counting()
                ));

        // Tạo thông điệp trả về - CHỈ MỘT DÒNG DUY NHẤT
        StringBuilder message = new StringBuilder("Số lượng sân theo loại: ");

        // Sắp xếp các loại sân theo thứ tự: 5, 7, 11
        List<Map.Entry<String, Long>> sortedEntries = new ArrayList<>(pitchCounts.entrySet());
        sortedEntries.sort(Comparator.comparing(entry -> {
            String type = entry.getKey();
            if ("FIVE_A_SIDE".equals(type)) return 1;
            if ("SEVEN_A_SIDE".equals(type)) return 2;
            if ("ELEVEN_A_SIDE".equals(type)) return 3;
            return 4;
        }));

        // Tạo danh sách các phần tử đã định dạng
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Long> entry : sortedEntries) {
            String typeName = formatPitchType(entry.getKey());
            parts.add(typeName + ": " + entry.getValue() + " sân");
        }

        // Ghép các phần tử thành một chuỗi duy nhất
        message.append(String.join(", ", parts));

        // Tạo response
        BookingQuery query = new BookingQuery();
        query.message = message.toString(); // CHỈ TRẢ VỀ MỘT CHUỖI
        query.bookingDate = null;
        query.slotList = new ArrayList<>();
        query.pitchType = "ALL";
        query.data = new HashMap<>();

        return query;
    }

    private BookingQuery handlePitchTypesQuestion() {
        List<PitchResponseDTO> allPitches = pitchService.getAllPitches();

        // Lấy tất cả các loại sân duy nhất
        Set<String> pitchTypes = allPitches.stream()
                .map(p -> p.getType().name())
                .collect(Collectors.toSet());

        // Tạo message trả về
        String message;
        if (pitchTypes.isEmpty()) {
            message = "Hiện không có sân nào trong hệ thống";
        } else {
            List<String> typeNames = pitchTypes.stream()
                    .sorted()
                    .map(this::formatPitchType)
                    .collect(Collectors.toList());

            message = "Hệ thống có " + pitchTypes.size() + " loại sân: " +
                    String.join(", ", typeNames);
        }

        BookingQuery query = new BookingQuery();
        query.message = message;
        query.bookingDate = null;
        query.slotList = new ArrayList<>();
        query.pitchType = "ALL";
        query.data = new HashMap<>();

        return query;
    }

    private boolean isPitchCountByTypeQuestion(String input) {
        String lowerInput = input.toLowerCase();
        return lowerInput.contains("số sân mỗi loại") ||
                lowerInput.contains("số lượng sân theo loại") ||
                lowerInput.contains("mỗi loại sân có bao nhiêu") ||
                lowerInput.contains("bao nhiêu sân mỗi loại");
    }

    private String formatPitchType(String type) {
        if (type.equals("FIVE_A_SIDE")) return "sân 5";
        if (type.equals("SEVEN_A_SIDE")) return "sân 7";
        if (type.equals("ELEVEN_A_SIDE")) return "sân 11";
        return type;
    }

    private boolean isPitchTypesQuestion(String input) {
        String lowerInput = input.toLowerCase();
        return lowerInput.contains("loại sân") ||
                lowerInput.contains("có bao nhiêu loại") ||
                lowerInput.contains("các loại sân");
    }

    private boolean isTotalPitchesQuestion(String input) {
        String lowerInput = input.toLowerCase();
        return lowerInput.contains("tổng số sân") ||
                lowerInput.contains("bao nhiêu sân") ||
                lowerInput.contains("có bao nhiêu sân");
    }

    private BookingQuery createBasicResponse(String message) {
        BookingQuery query = new BookingQuery();
        query.message = message;
        query.slotList = new ArrayList<>();
        query.pitchType = "ALL";
        query.data = new HashMap<>();
        return query;
    }

    private boolean isGreeting(String s) { return s.toLowerCase().matches(".*(xin chào|chào|hello).*"); }

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
    // Chỉ điền các trường này nếu action là 'get_weather', 'check_stock', v.v.
    // Nếu là đặt sân thông thường, hãy để object data rỗng: {}
    "action": "get_weather" | "cheapest_product" | "check_stock" | null,
    "productName": "...",
    "city": "..."
  }
}
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
  - "Hôm nay" → ngày hiện tại ("{{today}}").
  - "Ngày mai" → cộng 1 ngày ("{{plus1}}").
  - "Ngày kia" → cộng 2 ngày ("{{plus2}}").
  - Ngày cụ thể (ví dụ: "20/5", "20-5", "20 tháng 5") → chuyển về yyyy-MM-dd.
  - Nếu không xác định ngày, để `bookingDate` là null và cung cấp `message` phù hợp.
            
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
     - `data`: {}
     - `message`: "Tôi sẽ tìm sân có giá rẻ nhất."
  5. Hỏi sân mắc nhất (ví dụ: "Sân nào có giá mắc nhất?"):
     - `data`: {}
     - `message`: "Tôi sẽ tìm sân có giá mắc nhất."
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
            
  9. Nếu người dùng hỏi "rẻ nhất", "mắc nhất", "đắt nhất", "bán chạy nhất" MÀ KHÔNG nói rõ tên sản phẩm cụ thể -> Mặc định là tìm trong TOÀN BỘ CỬA HÀNG.
      - "Sản phẩm nào rẻ nhất?" -> action: "cheapest_product"
      - "Cái nào đắt nhất shop?" -> action: "most_expensive_product"
      - "Món nào bán chạy?" -> action: "best_selling_product"
  TUYỆT ĐỐI KHÔNG được hỏi ngược lại người dùng (ví dụ: "Bạn muốn tìm loại nào?"). Hãy trả về JSON action ngay.

  10. Nếu hỏi về tình trạng/chi tiết một sản phẩm cụ thể:
      - "Giày Nike Air còn không?" -> action: "check_stock", productName: "Nike Air"
      - "Thông tin áo Real Madrid?" -> action: "product_detail", productName: "áo Real Madrid"
      
VÍ DỤ MẪU:
- User: "Sản phẩm nào rẻ nhất?"
  JSON: { ..., "data": { "action": "cheapest_product" } }
  
- User: "Shop có món nào bán chạy nhất không?"
  JSON: { ..., "data": { "action": "best_selling_product" } }

Lưu ý: Luôn ưu tiên trả về JSON action hơn là message hỏi lại.
""";

    public static class BookingQuery {
        public String bookingDate;
        public List<Integer> slotList;
        public String pitchType;
        public String message;
        public Map<String, Object> data;

        @Override
        public String toString() {
            return "BookingQuery{" +
                    "bookingDate='" + bookingDate + '\'' +
                    ", slotList=" + slotList +
                    ", pitchType='" + pitchType + '\'' +
                    ", message='" + message + '\'' +
                    ", data=" + data +
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