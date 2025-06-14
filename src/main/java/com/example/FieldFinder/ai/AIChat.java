package com.example.FieldFinder.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

@Component
public class AIChat {

    private static final String OPENROUTER_API_KEY;
    private static final String OPENROUTER_API_URL = "https://openrouter.ai/api/v1/chat/completions";
    private static final String MODEL_NAME = "openai/gpt-3.5-turbo";
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

        Map<String, Object> body = new HashMap<>();
        body.put("model", MODEL_NAME);
        body.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_INSTRUCTION),
                Map.of("role", "user", "content", prompt)
        ));
        body.put("temperature", 0.3);
        body.put("max_tokens", 200);
        body.put("stop", List.of("\n\n", "```")); // ✅ Ngắt trước khi giải thích hoặc markdown

        String requestBody = mapper.writeValueAsString(body);

        Request request = new Request.Builder()
                .url(OPENROUTER_API_URL)
                .addHeader("Authorization", "Bearer " + OPENROUTER_API_KEY)
                .addHeader("Content-Type", "application/json")
                .addHeader("HTTP-Referer", "https://yourdomain.com")
                .addHeader("User-Agent", "FieldFinderApp/1.0")
                .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                .build();

        waitIfNeeded();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("OpenRouter API error: " + response.code() + " - " + response.message());
            }

            String responseBody = response.body().string();
            JsonNode root = mapper.readTree(responseBody);
            String content = root.at("/choices/0/message/content").asText();

            String cleanJson = extractPureJson(content);

            try {
                return mapper.readValue(cleanJson, BookingQuery.class);
            } catch (Exception e) {
                System.err.println("❌ JSON không hợp lệ:\n" + cleanJson);
                throw e;
            }
        }
    }

    private String extractPureJson(String content) {
        // ✅ Loại bỏ markdown ```json ... ```
        content = content.replaceAll("(?s)```json\\s*(\\{.*?})\\s*```", "$1")
                .replaceAll("(?s)```\\s*(\\{.*?})\\s*```", "$1");

        // ✅ Tìm đoạn JSON hợp lệ
        Pattern pattern = Pattern.compile("\\{.*?}", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(content);

        while (matcher.find()) {
            String candidate = matcher.group();
            try {
                mapper.readTree(candidate); // check hợp lệ
                return candidate;
            } catch (Exception ignored) {}
        }

        throw new IllegalArgumentException("❌ Không tìm thấy JSON hợp lệ:\n" + content);
    }

    private String buildPrompt(String userInput) {
        return """
                Phân tích yêu cầu đặt sân sau và trả về thông tin ngày và các slot tương ứng:
                "%s"
                """.formatted(userInput);
    }

    private static final String SYSTEM_INSTRUCTION;

    static {
        LocalDate today = LocalDate.now();
        String todayStr = today.toString();
        String plus1 = today.plusDays(1).toString();
        String plus2 = today.plusDays(2).toString();

        SYSTEM_INSTRUCTION = """
Bạn là trợ lý AI chuyên xử lý đặt sân thể thao. Hãy trích xuất ngày, khoảng thời gian đặt sân, và loại sân từ yêu cầu của người dùng và trả về dưới dạng JSON **THUẦN** với định dạng sau:

{
  "bookingDate": "yyyy-MM-dd",
  "slotList": [danh_sách_số_slot],
  "pitchType": "FIVE_A_SIDE" | "SEVEN_A_SIDE" | "ELEVEN_A_SIDE" | null
}

❗️Lưu ý quan trọng:
- `bookingDate`: là chuỗi định dạng "yyyy-MM-dd"
- `slotList`: là MẢNG GỒM CÁC SỐ NGUYÊN. Không bao gồm đối tượng JSON nào trong mảng này.
- `pitchType`: là một trong các chuỗi sau:
  - "FIVE_A_SIDE" nếu người dùng nói "sân 5", "sân 5 người", "sân nhỏ", "sân mini"
  - "SEVEN_A_SIDE" nếu người dùng nói "sân 7", "sân 7 người", "sân trung"
  - "ELEVEN_A_SIDE" nếu người dùng nói "sân 11", "sân 11 người", "sân lớn"
  - null nếu không xác định được

⚠️ Các slot được quy định như sau:
- Slot 1: 6h - 7h
- Slot 2: 7h - 8h
- Slot 3: 8h - 9h
- Slot 4: 9h - 10h
- Slot 5 : 10h - 11h
- Slot 6: 11h - 12h
- Slot 7: 12h - 13h
- Slot 8: 13h - 14h
- Slot 9: 14h - 15h
- Slot 10: 15h - 16h
- Slot 11: 16h - 17h
- Slot 12: 17h - 18h
- Slot 13: 18h - 19h
- Slot 14: 19h - 20h
- Slot 15: 20h - 21h
- Slot 16: 21h - 22h
- Slot 17: 22h - 23h
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
- Nếu người dùng ghi "hôm nay", sử dụng ngày hiện tại (ví dụ: "%s")
- Nếu ghi "ngày mai", cộng thêm 1 ngày (%s)
- Nếu ghi "ngày kia", cộng thêm 2 ngày (%s)
- Nếu có ngày cụ thể như "20/5", chuyển về định dạng yyyy-MM-dd

💡 Nếu không xác định được ngày hoặc giờ hợp lệ, trả về slotList rỗng và bookingDate là null hoặc rỗng. Nếu không xác định được loại sân thì pitchType là null.

🎯 Chỉ trả về JSON thuần. Không kèm theo bất kỳ giải thích, markdown, hoặc ký tự khác.
""".formatted(todayStr, plus1, plus2);
    }

    public static class BookingQuery {
        public String bookingDate;
        public List<Integer> slotList;
        public String pitchType;

        @Override
        public String toString() {
            return "BookingQuery{" +
                    "bookingDate='" + bookingDate + '\'' +
                    ", slotList=" + slotList +
                    ", pitchType='" + pitchType + '\'' +
                    '}';
        }
    }
}
