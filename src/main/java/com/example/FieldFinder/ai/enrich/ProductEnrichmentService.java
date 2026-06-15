package com.example.FieldFinder.ai.enrich;

import com.example.FieldFinder.ai.gemini.GeminiClient;
import com.example.FieldFinder.ai.util.AiTextUtil;
import com.example.FieldFinder.util.ColorVocab;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Sinh tags + màu chủ đạo cho ảnh sản phẩm (tạo SP / backfill) — tách khỏi AIChat.
 * Tự tải ảnh (xử mime avif/gif → JPEG qua Cloudinary), gọi GeminiClient vision, canonical hóa màu.
 * Có pause flag: khi user đang chat thì tạm dừng để nhường rate-limit Gemini.
 */
@Service
public class ProductEnrichmentService {

    private static final String DATA_ENRICHMENT_SYSTEM_PROMPT = """
        Bạn là chuyên gia quản lý kho hàng thời trang (Inventory Manager).
        Nhiệm vụ: Phân tích hình ảnh sản phẩm và sinh ra danh sách từ khóa (Tags) chi tiết để phục vụ tìm kiếm.

        HÃY QUAN SÁT KỸ VÀ TRẢ VỀ JSON:
        1. Thương hiệu: Nhìn logo/chữ trên sản phẩm (Nike, Adidas, Puma...).
        2. Dòng sản phẩm: Tên cụ thể (Air Max, Jordan, Ultraboost, Stan Smith...).
        3. MÀU CHỦ ĐẠO (`dominantColor`) — BẮT BUỘC, chọn ĐÚNG 1 màu chiếm DIỆN TÍCH LỚN NHẤT của
           sản phẩm, viết tiếng Việt thường, CHỈ dùng đúng 1 trong các giá trị sau:
           "đen", "trắng", "xám", "đỏ", "cam", "vàng", "hồng", "tím", "nâu", "xanh lá", "xanh dương".
           VD giày đen đế trắng → "đen"; áo trắng viền cam → "trắng". KHÔNG ghép nhiều màu ở đây.
        4. TẬP MÀU CHÍNH (`colors`) — danh sách các màu phủ DIỆN TÍCH ĐÁNG KỂ (mỗi màu ≥ ~25% sản
           phẩm), TỐI ĐA 3 màu, sắp theo diện tích giảm dần, phần tử ĐẦU = dominantColor. Dùng đúng
           bộ giá trị như mục 3. CHỈ thêm màu thật sự lớn — KHÔNG cho viền/logo/chi tiết nhỏ vào đây.
           VD giày nửa đen nửa trắng → ["đen","trắng"]; giày đen viền cam nhỏ → ["đen"] (cam là accent).
        5. Màu phụ/accent (cho `tags`): các màu phối nhỏ (viền, logo) nhìn thấy (Việt + Anh) — bổ trợ tìm kiếm.
        6. Đặc điểm hình dáng:
           - Giày: Cổ cao/thấp, đế air, đế bằng, dây buộc, không dây...
           - Áo/Quần: Tay dài/ngắn, cổ tròn/tim, có mũ...
        7. Chất liệu: Da, vải lưới, nỉ, cotton...

        YÊU CẦU OUTPUT JSON:
        {
          "dominantColor": "đen",
          "colors": ["đen", "trắng"],
          "tags": ["danh sách khoảng 15-20 từ khóa (gồm cả màu phụ/accent), viết thường, Anh + Việt"]
        }
        """;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(15))
            .readTimeout(Duration.ofSeconds(60))
            .writeTimeout(Duration.ofSeconds(30))
            .callTimeout(Duration.ofSeconds(90))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    private final GeminiClient geminiClient;

    // Pause flag: khi user đang chat thì tạm dừng background enrichment để nhường rate-limit.
    private volatile boolean enrichmentPaused = false;

    public ProductEnrichmentService(GeminiClient geminiClient) {
        this.geminiClient = geminiClient;
    }

    public void pauseEnrichment()  { this.enrichmentPaused = true; }
    public void resumeEnrichment() { this.enrichmentPaused = false; }

    /** Backward-compat: chỉ lấy tags. */
    public List<String> generateTagsForProduct(String imageUrl) {
        return enrichProductFromImage(imageUrl).tags;
    }

    /**
     * Gọi Gemini Vision 1 lần → trả tags + màu chủ đạo. Dùng khi tạo sản phẩm / backfill.
     * Màu chủ đạo được chuẩn hóa về bộ màu canonical ({@link ColorVocab}).
     */
    public ProductEnrichment enrichProductFromImage(String imageUrl) {
        ProductEnrichment out = new ProductEnrichment();
        try {
            // Yield to user chat requests — nếu user đang chat thì đợi
            while (enrichmentPaused) {
                Thread.sleep(2000);
            }
            String[] img = downloadImageWithMime(imageUrl);
            if (img == null) return out;
            String base64Image = img[0];
            String mimeType = img[1];

            // Gemini 2.5-flash bật "thinking" mặc định → nuốt output budget làm JSON bị cắt;
            // GeminiClient.visionJson đã tắt thinking + set maxOutputTokens 1024.
            JsonNode root = geminiClient.visionJson(
                    DATA_ENRICHMENT_SYSTEM_PROMPT, "Hãy sinh tags cho sản phẩm này.",
                    base64Image, mimeType, 0.1);
            if (root != null) {
                List<String> tags = mapper.convertValue(root.path("tags"), new TypeReference<List<String>>(){});
                out.tags = AiTextUtil.sanitizeTags(tags);
                // Màu chủ đạo: ưu tiên field "dominantColor", fallback dò từ chính tags.
                String rawColor = root.path("dominantColor").asText("");
                String canon = ColorVocab.canonical(rawColor);
                if (canon == null) {
                    for (String t : out.tags) {
                        canon = ColorVocab.canonical(t);
                        if (canon != null) break;
                    }
                }
                out.dominantColor = canon;
                // Tập màu chính: canonical hóa "colors", đưa dominant lên đầu, cap 3.
                List<String> rawColors = new ArrayList<>();
                try {
                    JsonNode colorsNode = root.path("colors");
                    if (colorsNode.isArray()) {
                        for (JsonNode cN : colorsNode) rawColors.add(cN.asText(""));
                    }
                } catch (Exception ignored) {}
                out.colors = new ArrayList<>(ColorVocab.canonicalSet(rawColors, canon, 3));
            }
            return out;
        } catch (Exception e) {
            e.printStackTrace();
            return out;
        }
    }

    // package-private (thay vì private) để unit-test trực tiếp logic mime — đây là chốt fix AVIF/Cloudinary.
    static boolean geminiSupportsMime(String mime) {
        return mime != null && (mime.equals("image/png") || mime.equals("image/jpeg")
                || mime.equals("image/webp") || mime.equals("image/heic") || mime.equals("image/heif"));
    }

    static String guessMimeFromUrl(String url) {
        String u = url.toLowerCase();
        if (u.contains(".png")) return "image/png";
        if (u.contains(".webp")) return "image/webp";
        if (u.contains(".avif")) return "image/avif";
        if (u.contains(".heic")) return "image/heic";
        if (u.contains(".gif")) return "image/gif";
        return "image/jpeg";
    }

    /** Cloudinary: chèn transform f_jpg để buộc serve JPEG (xử ảnh avif/gif Gemini không nhận). */
    static String cloudinaryAsJpg(String url) {
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
}
