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
- Nếu người dùng ghi "hôm nay", sử dụng ngày hiện tại (ví dụ: "2025-05-29")
- Nếu ghi "ngày mai", cộng thêm 1 ngày
- Nếu ghi "ngày kia", cộng thêm 2 ngày
- Nếu có ngày cụ thể như "20/5", chuyển về định dạng yyyy-MM-dd

💡 Nếu không xác định được ngày hoặc giờ hợp lệ, trả về slotList rỗng và bookingDate là null hoặc rỗng. Nếu không xác định được loại sân thì pitchType là null.

🎯 Chỉ trả về JSON thuần. Không kèm theo bất kỳ giải thích, markdown, hoặc ký tự khác.

📌 Ví dụ:
- Input: "Tôi cần đặt sân vào ngày mai lúc 6h tối"
- Output: {"bookingDate": "2025-05-30", "slotList": [13], "pitchType": null}

- Input: "Đặt sân hôm nay từ 1h đến 2h chiều"
- Output: {"bookingDate": "2025-05-29", "slotList": [8], "pitchType": null}

- Input: "Tôi muốn đặt sân vào ngày kia từ 8h sáng tới 10h sáng"
- Output: {"bookingDate": "2025-05-31", "slotList": [3, 4], "pitchType": null}

- Input: "Cho tôi đặt sân 5 lúc 6h-7h hôm nay"
- Output: {"bookingDate": "2025-05-29", "slotList": [1], "pitchType": "FIVE_A_SIDE"}

- Input: "Tôi muốn đặt sân lớn vào ngày mai từ 19h đến 21h"
- Output: {"bookingDate": "2025-05-30", "slotList": [14,15], "pitchType": "ELEVEN_A_SIDE"}

- Input: "Đặt sân lúc 9h hôm nay"
- Output: {"bookingDate": "2025-05-29", "slotList": [4], "pitchType": null}
""";




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
