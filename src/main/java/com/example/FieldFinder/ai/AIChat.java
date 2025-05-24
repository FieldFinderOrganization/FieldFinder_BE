package com.example.FieldFinder.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;

@Component
public class AIChat {

    private static final String OPENROUTER_API_KEY;
    private static final String OPENROUTER_API_URL = "https://openrouter.ai/api/v1/chat/completions";
    private static final String MODEL_NAME = "mistralai/mistral-7b-instruct"; // hoặc llama-3...

    private final OkHttpClient client = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private static final long MIN_INTERVAL_BETWEEN_CALLS_MS = 1100;
    private long lastCallTime = 0;

    static {
        Dotenv dotenv = Dotenv.load();
        OPENROUTER_API_KEY = dotenv.get("OPENROUTER_API_KEY");
        if (OPENROUTER_API_KEY == null || OPENROUTER_API_KEY.isEmpty()) {
            throw new RuntimeException("OPENROUTER_API_KEY is not set in environment variables");
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

    public BookingQuery parseBookingInput(String userInput) throws IOException, InterruptedException {
        String prompt = buildPrompt(userInput);

        String requestBody = mapper.writeValueAsString(Map.of(
                "model", MODEL_NAME,
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_INSTRUCTION),
                        Map.of("role", "user", "content", prompt)
                ),
                "temperature", 0.3,
                "max_tokens", 200
        ));

        Request request = new Request.Builder()
                .url(OPENROUTER_API_URL)
                .addHeader("Authorization", "Bearer " + OPENROUTER_API_KEY)
                .addHeader("Content-Type", "application/json")
                .addHeader("HTTP-Referer", "https://yourdomain.com") // Tuỳ chọn
                .addHeader("User-Agent", "FieldFinderApp/1.0")       // BẮT BUỘC
                .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                .build();

        waitIfNeeded();

        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String responseBody = response.body().string();
                JsonNode root = mapper.readTree(responseBody);
                String content = root.at("/choices/0/message/content").asText();

                String cleanJson = content.replaceAll("```json", "").replaceAll("```", "").trim();
                return mapper.readValue(cleanJson, BookingQuery.class);
            } else {
                throw new IOException("OpenRouter API error: " + response.code() + " - " + response.message());
            }
        }
    }

    private String buildPrompt(String userInput) {
        return """
                Phân tích yêu cầu đặt sân sau và trả về thông tin ngày và các slot tương ứng:
                "%s"
                """.formatted(userInput);
    }

    private static final String SYSTEM_INSTRUCTION = """
Bạn là trợ lý AI chuyên xử lý đặt sân thể thao. Hãy trích xuất ngày và khoảng thời gian đặt sân từ yêu cầu của người dùng và trả về dưới dạng JSON **THUẦN** với định dạng sau:

{
  "bookingDate": "yyyy-MM-dd",
  "slotList": [danh_sách_số_slot]
}

❗️Lưu ý quan trọng:
- `bookingDate`: là chuỗi định dạng "yyyy-MM-dd"
- `slotList`: là MẢNG GỒM CÁC SỐ NGUYÊN. Không bao gồm đối tượng JSON nào trong mảng này.

⚠️ Các slot được quy định như sau:
- Slot 1: 6h - 7h
- Slot 2: 7h - 8h
- ...
- Slot 18: 23h - 24h

🕒 QUY TẮC XỬ LÝ GIỜ:
1. Hiểu các cụm từ tự nhiên như "sáng", "chiều", "tối":
   - "6h sáng" → 6:00 → slot 1
   - "1h chiều" hoặc "13h" → 13:00 → slot 8
   - "6h tối" hoặc "18h" → 18:00 → slot 13
2. Nếu người dùng không ghi rõ buổi (sáng/chiều/tối), xử lý theo ngữ cảnh thông thường:
   - Giờ từ 1h đến 5h mặc định là chiều → cộng thêm 12 giờ
   - Giờ từ 6h đến 11h mặc định là sáng
   - Giờ từ 7h tối trở đi hiểu là buổi tối (19h+)

📅 QUY TẮC XỬ LÝ NGÀY:
- Nếu người dùng ghi "hôm nay", sử dụng ngày hiện tại (ví dụ: "2025-05-22")
- Nếu ghi "ngày mai", cộng thêm 1 ngày
- Nếu ghi "ngày kia", cộng thêm 2 ngày
- Nếu có ngày cụ thể như "20/5", chuyển về định dạng yyyy-MM-dd

💡 Nếu không xác định được ngày hoặc giờ hợp lệ, trả về slotList rỗng và bookingDate là null hoặc rỗng.

🎯 Chỉ trả về JSON thuần. Không kèm theo bất kỳ giải thích, markdown, hoặc ký tự khác.

📌 Ví dụ:
- Input: "Tôi cần đặt sân vào ngày mai lúc 6h tối"
- Output: {"bookingDate": "2025-05-23", "slotList": [13]}

- Input: "Đặt sân hôm nay từ 1h đến 2h chiều"
- Output: {"bookingDate": "2025-05-22", "slotList": [8]}

- Input: "Tôi muốn đặt sân vào ngày kia từ 8h sáng tới 10h sáng"
- Output: {"bookingDate": "2025-05-24", "slotList": [3, 4]}
""";



    public static class BookingQuery {
        public String bookingDate;
        public List<Integer> slotList;

        @Override
        public String toString() {
            return "BookingQuery{" +
                    "bookingDate='" + bookingDate + '\'' +
                    ", slotList=" + slotList +
                    '}';
        }
    }
}
