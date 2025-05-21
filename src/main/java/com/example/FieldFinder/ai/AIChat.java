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

❗️Lưu ý:
- `bookingDate`: là chuỗi định dạng "yyyy-MM-dd"
- `slotList`: là MẢNG GỒM CÁC SỐ NGUYÊN. Không bao giờ được bao gồm đối tượng JSON nào bên trong mảng này (VD: KHÔNG được [{ "slot": 2 }])

Các slot thời gian được quy định như sau:
- Slot 1: 6h-7h
- ...
- Slot 18: 23h-24h

Quy tắc xử lý:
1. Chuyển tất cả ngày tháng về định dạng yyyy-MM-dd
2. Nếu nhập khoảng thời gian trùng nhiều slot, trả về tất cả các slot đó
3. Nếu nhập giờ không khớp chính xác, chọn slot gần nhất
4. Chỉ trả về JSON thuần. KHÔNG thêm bất kỳ giải thích hay ký tự nào khác (kể cả ```json hay markdown)
5. Nếu không xác định được ngày hoặc slot, trả về mảng rỗng.

Ví dụ:
- Input: "Tôi cần sân vào ngày 20/05/2025 từ 7h đến 8h sáng"
- Output: {"bookingDate": "2025-05-20", "slotList": [2]}

- Input: "Đặt sân ngày mai từ 14h đến 16h"
- Output: {"bookingDate": "2025-05-22", "slotList": [9, 10]}
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
