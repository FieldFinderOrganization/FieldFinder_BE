package com.example.FieldFinder.ai;

import com.example.FieldFinder.dto.req.ShopQueryDTO;
import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.example.FieldFinder.service.ProductService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.cdimascio.dotenv.Dotenv;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;

@Component
public class AIChatShop {

    /* =========================
       GEMINI CONFIG
     ========================= */
    private static final String GOOGLE_API_KEY;
    private static final String GEMINI_API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=";

    static {
        Dotenv dotenv = Dotenv.load();
        GOOGLE_API_KEY = dotenv.get("GOOGLE_API_KEY");
    }

    private final OkHttpClient client = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProductService productService;

    // Session context
    private final Map<String, ProductResponseDTO> sessionLastProducts = new HashMap<>();
    private final Map<String, String> sessionLastSizes = new HashMap<>();

    public AIChatShop(ProductService productService) {
        this.productService = productService;
    }

    /* =========================
       SYSTEM PROMPT
     ========================= */
    private static final String SHOP_SYSTEM_INSTRUCTION = """
Bạn là trợ lý AI cho SHOP thể thao của FieldFinder.
Nhiệm vụ: Phân tích câu hỏi người dùng và trả về JSON cho backend xử lý.

JSON FORMAT:
{
  "message": null,
  "data": {
    "action": "cheapest_product | most_expensive_product | best_selling_product | check_stock | check_size | prepare_order",
    "productName": "...",
    "size": "...",
    "quantity": 1
  }
}

QUY TẮC:
- "rẻ nhất" → cheapest_product
- "đắt nhất", "mắc nhất" → most_expensive_product
- "bán chạy" → best_selling_product
- "còn hàng", "hết hàng" → check_stock
- "size 40", "size M" → check_size
- "mua", "đặt", "chốt đơn" → prepare_order

CHỈ TRẢ JSON. KHÔNG markdown. KHÔNG giải thích.
""";

    /* =========================
       ENTRY POINT
     ========================= */
    public ShopQueryDTO handleShopChat(String userInput, String sessionId) {
        ShopQueryDTO query = parseIntent(userInput);

        if (query.data == null || !query.data.containsKey("action")) {
            query.message = "Tôi có thể giúp bạn tìm sản phẩm, kiểm tra size hoặc đặt hàng.";
            return query;
        }

        String action = (String) query.data.get("action");

        return switch (action) {
            case "cheapest_product" -> handleCheapestProduct(query, sessionId);
            case "most_expensive_product" -> handleMostExpensiveProduct(query, sessionId);
            case "best_selling_product" -> handleBestSellingProduct(query, sessionId);
            case "check_stock" -> handleCheckStock(query, sessionId);
            case "check_size" -> handleCheckSize(query, sessionId);
            case "prepare_order" -> handlePrepareOrder(query, sessionId);
            default -> {
                query.message = "Hiện tôi chưa hỗ trợ yêu cầu này.";
                yield query;
            }
        };
    }

    /* =========================
       GEMINI PARSER
     ========================= */
    private ShopQueryDTO parseIntent(String userInput) {
        try {
            String cleanJson = callGeminiAPI(userInput, SHOP_SYSTEM_INSTRUCTION);
            return mapper.readValue(cleanJson, ShopQueryDTO.class);
        } catch (Exception e) {
            e.printStackTrace();
            ShopQueryDTO fallback = new ShopQueryDTO();
            fallback.message = "Xin lỗi, tôi chưa hiểu rõ yêu cầu của bạn.";
            fallback.data = new HashMap<>();
            return fallback;
        }
    }

    private String callGeminiAPI(String userInput, String systemPrompt) throws IOException {
        ObjectNode root = mapper.createObjectNode();

        ObjectNode systemInst = root.putObject("system_instruction");
        systemInst.putObject("parts").put("text", systemPrompt);

        ArrayNode contents = root.putArray("contents");
        ObjectNode user = contents.addObject();
        user.put("role", "user");
        user.putObject("parts").put("text", userInput);

        ObjectNode config = root.putObject("generationConfig");
        config.put("temperature", 0.1);
        config.put("response_mime_type", "application/json");

        Request request = new Request.Builder()
                .url(GEMINI_API_URL + GOOGLE_API_KEY)
                .post(RequestBody.create(
                        mapper.writeValueAsString(root),
                        MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Gemini API Error: " + response.code());
            }
            return cleanJson(extractGeminiResponse(response.body().string()));
        }
    }

    private String extractGeminiResponse(String rawJson) throws IOException {
        JsonNode root = mapper.readTree(rawJson);
        if (!root.has("candidates")) return "{}";

        return root.path("candidates")
                .get(0)
                .path("content")
                .path("parts")
                .get(0)
                .path("text")
                .asText();
    }

    private String cleanJson(String raw) {
        if (raw == null) return "{}";
        String cleaned = raw.trim();
        if (cleaned.startsWith("```json")) cleaned = cleaned.substring(7);
        else if (cleaned.startsWith("```")) cleaned = cleaned.substring(3);
        if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
        return cleaned.trim();
    }

    /* =========================
       HANDLERS
     ========================= */

    private void saveLastProduct(String sessionId, ProductResponseDTO product) {
        if (sessionId != null && product != null) {
            sessionLastProducts.put(sessionId, product);
        }
    }

    private void saveLastSize(String sessionId, String size) {
        if (sessionId != null && size != null) {
            sessionLastSizes.put(sessionId, size);
        }
    }

    private ShopQueryDTO handleCheapestProduct(ShopQueryDTO query, String sessionId) {
        ProductResponseDTO product = productService.getAllProducts()
                .stream()
                .min(Comparator.comparing(ProductResponseDTO::getPrice))
                .orElse(null);

        if (product == null) {
            query.message = "Hiện chưa có sản phẩm nào.";
            return query;
        }

        saveLastProduct(sessionId, product);
        query.message = String.format("Sản phẩm rẻ nhất là **%s** với giá %,.0f VNĐ.",
                product.getName(), product.getPrice());
        query.data.put("product", product);
        return query;
    }

    private ShopQueryDTO handleMostExpensiveProduct(ShopQueryDTO query, String sessionId) {
        ProductResponseDTO product = productService.getAllProducts()
                .stream()
                .max(Comparator.comparing(ProductResponseDTO::getPrice))
                .orElse(null);

        if (product == null) {
            query.message = "Hiện chưa có sản phẩm nào.";
            return query;
        }

        saveLastProduct(sessionId, product);
        query.message = String.format("Sản phẩm đắt nhất là **%s** với giá %,.0f VNĐ.",
                product.getName(), product.getPrice());
        query.data.put("product", product);
        return query;
    }

    private ShopQueryDTO handleBestSellingProduct(ShopQueryDTO query, String sessionId) {
        ProductResponseDTO product = productService.getAllProducts()
                .stream()
                .max(Comparator.comparing(p -> Optional.ofNullable(p.getTotalSold()).orElse(0)))
                .orElse(null);

        if (product == null) {
            query.message = "Chưa có dữ liệu sản phẩm bán chạy.";
            return query;
        }

        saveLastProduct(sessionId, product);
        query.message = String.format(
                "Sản phẩm bán chạy nhất là **%s**, đã bán %d sản phẩm.",
                product.getName(),
                Optional.ofNullable(product.getTotalSold()).orElse(0)
        );
        query.data.put("product", product);
        return query;
    }

    private ShopQueryDTO handleCheckStock(ShopQueryDTO query, String sessionId) {
        String productName = (String) query.data.get("productName");

        ProductResponseDTO product = (productName != null && !productName.isBlank())
                ? productService.getProductByName(productName)
                : sessionLastProducts.get(sessionId);

        if (product == null) {
            query.message = "Bạn muốn kiểm tra tồn kho của sản phẩm nào?";
            return query;
        }

        saveLastProduct(sessionId, product);
        int stock = product.getStockQuantity();

        query.message = stock > 0
                ? String.format("Sản phẩm **%s** còn %d sản phẩm trong kho.", product.getName(), stock)
                : String.format("Sản phẩm **%s** hiện đã hết hàng.", product.getName());

        query.data.put("product", product);
        return query;
    }

    private ShopQueryDTO handleCheckSize(ShopQueryDTO query, String sessionId) {
        String size = (String) query.data.get("size");
        String productName = (String) query.data.get("productName");

        ProductResponseDTO product = null;

        if (productName != null && !productName.isBlank()) {
            product = productService.getProductByName(productName);
        }

        if (product == null) {
            product = sessionLastProducts.get(sessionId);
        }

        if (product == null || size == null) {
            query.message = "Bạn muốn kiểm tra size nào cho sản phẩm nào?";
            return query;
        }

        if (product.getVariants() == null) {
            query.message = "Sản phẩm này không có thông tin size.";
            return query;
        }

        for (ProductResponseDTO.VariantDTO v : product.getVariants()) {
            if (v.getSize().equalsIgnoreCase(size)) {
                saveLastProduct(sessionId, product);
                saveLastSize(sessionId, size);

                query.message = v.getQuantity() > 0
                        ? String.format("Sản phẩm **%s** size **%s** còn %d sản phẩm.",
                        product.getName(), size, v.getQuantity())
                        : String.format("Sản phẩm **%s** size **%s** đã hết hàng.",
                        product.getName(), size);

                query.data.put("product", product);
                return query;
            }
        }

        query.message = String.format("Sản phẩm **%s** không có size %s.", product.getName(), size);
        return query;
    }

    private ShopQueryDTO handlePrepareOrder(ShopQueryDTO query, String sessionId) {
        ProductResponseDTO product = sessionLastProducts.get(sessionId);
        String size = (String) query.data.get("size");
        Integer quantity = (Integer) query.data.getOrDefault("quantity", 1);

        if (product == null) {
            query.message = "Bạn chưa chọn sản phẩm nào.";
            return query;
        }

        if (size == null) size = sessionLastSizes.get(sessionId);

        if (size == null) {
            query.message = "Bạn muốn đặt size nào?";
            return query;
        }

        Map<String, Object> orderItem = new HashMap<>();
        orderItem.put("productId", product.getId());
        orderItem.put("size", size);
        orderItem.put("quantity", quantity);

        query.message = String.format(
                "Xác nhận đặt **%s – Size %s – SL %d**. Nhấn nút bên dưới để thanh toán.",
                product.getName(), size, quantity
        );

        query.data.clear();
        query.data.put("action", "ready_to_order");
        query.data.put("items", List.of(orderItem));

        return query;
    }
}
