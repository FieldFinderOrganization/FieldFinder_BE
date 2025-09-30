package com.example.FieldFinder.ai;

import com.example.FieldFinder.dto.res.PitchResponseDTO;
import com.example.FieldFinder.service.PitchService;
import com.example.FieldFinder.service.ReviewService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class AIChat {

    private static final String OPENROUTER_API_KEY;
    private static final String OPENROUTER_API_URL = "https://openrouter.ai/api/v1/chat/completions";
    private static final String MODEL_NAME = "openai/gpt-3.5-turbo";

    private final OkHttpClient client = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final PitchService pitchService;
    private final ReviewService reviewService;

    private static final long MIN_INTERVAL_BETWEEN_CALLS_MS = 1100;
    private long lastCallTime = 0;

    // Bản đồ lưu trữ thông tin sân cho mỗi phiên
    private final Map<String, PitchResponseDTO> sessionPitches = new HashMap<>();

    static {
        Dotenv dotenv = Dotenv.load();
        OPENROUTER_API_KEY = dotenv.get("OPENROUTER_API_KEY");
        if (OPENROUTER_API_KEY == null || OPENROUTER_API_KEY.isEmpty()) {
            throw new RuntimeException("OPENROUTER_API_KEY is not set in environment variables");
        }
    }

    public AIChat(PitchService pitchService, ReviewService reviewService) {
        this.pitchService = pitchService;
        this.reviewService = reviewService;
    }

    private synchronized void waitIfNeeded() throws InterruptedException {
        long now = System.currentTimeMillis();
        long waitTime = MIN_INTERVAL_BETWEEN_CALLS_MS - (now - lastCallTime);
        if (waitTime > 0) {
            Thread.sleep(waitTime);
        }
        lastCallTime = System.currentTimeMillis();
    }

    private String buildSystemPrompt(long totalPitches, long fiveASideCount, long sevenASideCount, long elevenASideCount) {
        LocalDate today = LocalDate.now();
        return SYSTEM_INSTRUCTION
                .replace("{{today}}", today.toString())
                .replace("{{plus1}}", today.plusDays(1).toString())
                .replace("{{plus2}}", today.plusDays(2).toString())
                .replace("{{totalPitches}}", String.valueOf(totalPitches))
                .replace("{{fiveASideCount}}", String.valueOf(fiveASideCount))
                .replace("{{sevenASideCount}}", String.valueOf(sevenASideCount))
                .replace("{{elevenASideCount}}", String.valueOf(elevenASideCount));
    }

    private String callOpenRouterAPI(String userInput, String systemPrompt) throws IOException, InterruptedException {
        Map<String, Object> body = new HashMap<>();
        body.put("model", MODEL_NAME);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", buildPrompt(userInput))
        ));
        body.put("temperature", 0.3);
        body.put("max_tokens", 300);
        body.put("stop", List.of("\n\n", "```"));

        waitIfNeeded();

        Headers headers = new Headers.Builder()
                .add("Authorization", "Bearer " + OPENROUTER_API_KEY)
                .add("Content-Type", "application/json")
                .add("HTTP-Referer", "https://yourdomain.com")
                .add("User-Agent", "FieldFinderApp/1.0")
                .build();

        Request request = new Request.Builder()
                .url(OPENROUTER_API_URL)
                .post(RequestBody.create(mapper.writeValueAsString(body),
                        MediaType.parse("application/json")))
                .headers(headers)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("API error: " + response.code() + " - " + response.message());
            }
            return extractPureJson(mapper.readTree(response.body().string())
                    .at("/choices/0/message/content").asText());
        }
    }

    private BookingQuery parseAIResponse(String cleanJson) throws IOException {
        JsonNode jsonNode = mapper.readTree(cleanJson);
        if (!jsonNode.has("slotList") || !jsonNode.has("bookingDate") ||
                !jsonNode.has("pitchType") || !jsonNode.has("message") || !jsonNode.has("data")) {
            throw new IOException("Invalid JSON structure from AI");
        }
        return mapper.readValue(cleanJson, BookingQuery.class);
    }

    private void processSpecialCases(String userInput, String sessionId, BookingQuery query, List<PitchResponseDTO> allPitches) {
        String pitchType = extractPitchType(userInput); // Trích xuất loại sân từ input

        if (query.message != null) {
            if (query.message.contains("giá rẻ nhất") || query.message.contains("giá mắc nhất")) {
                boolean findCheapest = query.message.contains("giá rẻ nhất");
                PitchResponseDTO selectedPitch = findPitchByPrice(allPitches, findCheapest, pitchType);
                if (selectedPitch != null) {
                    sessionPitches.put(sessionId, selectedPitch);
                    query.data.put("selectedPitch", selectedPitch);
                    query.message = findCheapest
                            ? "Sân rẻ nhất loại " + formatPitchType(pitchType) + " là " + selectedPitch.getName() + " với giá " + selectedPitch.getPrice() + " VNĐ."
                            : "Sân mắc nhất loại " + formatPitchType(pitchType) + " là " + selectedPitch.getName() + " với giá " + selectedPitch.getPrice() + " VNĐ.";
                } else {
                    query.message = "Không tìm thấy sân nào loại " + formatPitchType(pitchType) + ".";
                }
            } else if (query.message.contains("đánh giá cao nhất") ||
                    query.message.contains("sân tốt nhất") ||
                    query.message.contains("sân đánh giá tốt nhất")) {
                PitchResponseDTO selectedPitch = findPitchByHighestRating(allPitches, pitchType);
                if (selectedPitch != null) {
                    sessionPitches.put(sessionId, selectedPitch);
                    query.data.put("selectedPitch", selectedPitch);
                    query.message = "Sân có đánh giá cao nhất loại " + formatPitchType(pitchType) + " là " + selectedPitch.getName() + ".";
                } else {
                    query.message = "Không tìm thấy sân nào có đánh giá loại " + formatPitchType(pitchType) + ".";
                }
            }
        }

        if (userInput.contains("sân này")) {
            PitchResponseDTO selectedPitch = sessionPitches.get(sessionId);
            if (selectedPitch == null) {
                selectedPitch = findPitchByContext(userInput, allPitches);
                if (selectedPitch != null) {
                    sessionPitches.put(sessionId, selectedPitch);
                }
            }
            if (selectedPitch != null) {
                query.data.put("selectedPitch", selectedPitch);
                if (query.message == null) {
                    query.message = "Đang xử lý đặt sân " + selectedPitch.getName() + "...";
                }
            } else {
                query.message = "Không tìm thấy sân phù hợp. Vui lòng chọn sân trước.";
            }
        }
    }

    private PitchResponseDTO findPitchByPrice(List<PitchResponseDTO> pitches, boolean findCheapest, String pitchType) {
        List<PitchResponseDTO> filteredPitches = pitches;
        if (!pitchType.equals("ALL")) {
            filteredPitches = pitches.stream()
                    .filter(p -> p.getType().name().equals(pitchType))
                    .collect(Collectors.toList());
        }
        if (filteredPitches.isEmpty()) return null;

        return findCheapest
                ? filteredPitches.stream().min(Comparator.comparing(PitchResponseDTO::getPrice)).orElse(null)
                : filteredPitches.stream().max(Comparator.comparing(PitchResponseDTO::getPrice)).orElse(null);
    }

    private PitchResponseDTO findPitchByHighestRating(List<PitchResponseDTO> pitches, String pitchType) {
        List<PitchResponseDTO> filteredPitches = pitches;
        if (!pitchType.equals("ALL")) {
            filteredPitches = pitches.stream()
                    .filter(p -> p.getType().name().equals(pitchType))
                    .collect(Collectors.toList());
        }
        if (filteredPitches.isEmpty()) return null;

        return filteredPitches.stream()
                .map(pitch -> new AbstractMap.SimpleEntry<>(pitch, reviewService.getAverageRating(pitch.getPitchId())))
                .filter(entry -> entry.getValue() != null)
                .max(Comparator.comparingDouble(entry -> entry.getValue()))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private String extractPitchType(String userInput) {
        String lowerInput = userInput.toLowerCase();
        if (lowerInput.contains("sân 5") || lowerInput.contains("sân 5 người") || lowerInput.contains("sân nhỏ") || lowerInput.contains("sân mini")) {
            return "FIVE_A_SIDE";
        } else if (lowerInput.contains("sân 7") || lowerInput.contains("sân 7 người") || lowerInput.contains("sân trung")) {
            return "SEVEN_A_SIDE";
        } else if (lowerInput.contains("sân 11") || lowerInput.contains("sân 11 người") || lowerInput.contains("sân lớn")) {
            return "ELEVEN_A_SIDE";
        }
        return "ALL";
    }

    private PitchResponseDTO findPitchByContext(String userInput, List<PitchResponseDTO> pitches) {
        String pitchType = extractPitchType(userInput);
        if (userInput.contains("rẻ nhất")) {
            return findPitchByPrice(pitches, true, pitchType);
        } else if (userInput.contains("mắc nhất")) {
            return findPitchByPrice(pitches, false, pitchType);
        } else if (isHighestRatedPitchQuestion(userInput)) {
            return findPitchByHighestRating(pitches, pitchType);
        }
        return null;
    }

    private boolean isHighestRatedPitchQuestion(String input) {
        String lowerInput = input.toLowerCase();
        return lowerInput.contains("đánh giá cao nhất") ||
                lowerInput.contains("sân tốt nhất") ||
                lowerInput.contains("sân đánh giá tốt nhất");
    }

    public BookingQuery parseBookingInput(String userInput, String sessionId) throws IOException, InterruptedException {
        if (isGreeting(userInput)) {
            BookingQuery query = new BookingQuery();
            query.message = "Xin chào! Tôi là trợ lý đặt sân thể thao. Bạn muốn đặt sân vào ngày nào và khung giờ nào?";
            query.slotList = new ArrayList<>();
            query.pitchType = "ALL";
            query.data = new HashMap<>();
            return query;
        }

        List<PitchResponseDTO> allPitches = pitchService.getAllPitches();
        Map<String, Long> pitchCount = allPitches.stream()
                .collect(Collectors.groupingBy(p -> p.getType().name(), Collectors.counting()));

        String finalPrompt = buildSystemPrompt(
                allPitches.size(),
                pitchCount.getOrDefault("FIVE_A_SIDE", 0L),
                pitchCount.getOrDefault("SEVEN_A_SIDE", 0L),
                pitchCount.getOrDefault("ELEVEN_A_SIDE", 0L)
        );

        String cleanJson = callOpenRouterAPI(userInput, finalPrompt);
        System.out.println("Cleaned JSON: " + cleanJson);

        BookingQuery query = parseAIResponse(cleanJson);

        if (isTotalPitchesQuestion(userInput)) {
            int totalPitches = pitchService.getAllPitches().size();
            return createBasicResponse("Hệ thống hiện có " + totalPitches + " sân");
        }

        if (isPitchTypesQuestion(userInput)) {
            return handlePitchTypesQuestion();
        }

        if (isPitchCountByTypeQuestion(userInput)) {
            return handlePitchCountByTypeQuestion();
        }

        if (isHighestRatedPitchQuestion(userInput)) {
            String pitchType = extractPitchType(userInput);
            PitchResponseDTO highestRatedPitch = findPitchByHighestRating(allPitches, pitchType);
            BookingQuery response = new BookingQuery();
            response.message = highestRatedPitch != null
                    ? "Sân có đánh giá cao nhất loại " + formatPitchType(pitchType) + " là " + highestRatedPitch.getName() + "."
                    : "Không tìm thấy sân nào có đánh giá loại " + formatPitchType(pitchType) + ".";
            response.bookingDate = null;
            response.slotList = new ArrayList<>();
            response.pitchType = pitchType;
            response.data = new HashMap<>();
            if (highestRatedPitch != null) {
                response.data.put("selectedPitch", highestRatedPitch);
                sessionPitches.put(sessionId, highestRatedPitch);
            }
            return response;
        }

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

        StringBuilder message = new StringBuilder("Số lượng sân theo loại: ");

        List<Map.Entry<String, Long>> sortedEntries = new ArrayList<>(pitchCounts.entrySet());
        sortedEntries.sort(Comparator.comparing(entry -> {
            String type = entry.getKey();
            if ("FIVE_A_SIDE".equals(type)) return 1;
            if ("SEVEN_A_SIDE".equals(type)) return 2;
            if ("ELEVEN_A_SIDE".equals(type)) return 3;
            return 4;
        }));

        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Long> entry : sortedEntries) {
            String typeName = formatPitchType(entry.getKey());
            parts.add(typeName + ": " + entry.getValue() + " sân");
        }

        message.append(String.join(", ", parts));

        BookingQuery query = new BookingQuery();
        query.message = message.toString();
        query.bookingDate = null;
        query.slotList = new ArrayList<>();
        query.pitchType = "Tất cả";
        query.data = new HashMap<>();

        return query;
    }

    private BookingQuery handlePitchTypesQuestion() {
        List<PitchResponseDTO> allPitches = pitchService.getAllPitches();

        Set<String> pitchTypes = allPitches.stream()
                .map(p -> p.getType().name())
                .collect(Collectors.toSet());

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

    private String extractPureJson(String content) throws IllegalArgumentException {
        String cleanedContent = content
                .replaceAll("(?s)```json\\s*(\\{[\\s\\S]*?})\\s*```", "$1")
                .replaceAll("(?s)```\\s*(\\{[\\s\\S]*?})\\s*```", "$1")
                .trim();

        try {
            mapper.readTree(cleanedContent);
            return cleanedContent;
        } catch (IOException e) {
            System.err.println("Failed to parse cleaned content: " + cleanedContent);
        }

        try {
            int start = cleanedContent.indexOf('{');
            int end = cleanedContent.lastIndexOf('}');
            if (start == -1 || end == -1 || start > end) {
                throw new IllegalArgumentException("Không tìm thấy JSON hợp lệ trong: " + content);
            }
            String jsonCandidate = cleanedContent.substring(start, end + 1);
            mapper.readTree(jsonCandidate);
            return jsonCandidate;
        } catch (IOException e) {
            throw new IllegalArgumentException("Không tìm thấy JSON hợp lệ trong: " + content, e);
        }
    }

    private boolean isGreeting(String input) {
        String lowerInput = input.toLowerCase();
        return lowerInput.contains("xin chào") || lowerInput.contains("chào") || lowerInput.contains("hello");
    }

    private String buildPrompt(String userInput) {
        return """
                Phân tích yêu cầu đặt sân sau và trả về thông tin ngày và các slot tương ứng:
                "%s"
                """.formatted(userInput);
    }

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
        String pitchType = extractPitchType(userInput);
        if (userInput.contains("rẻ nhất")) {
            return findPitchByPrice(pitches, true, pitchType);
        } else if (userInput.contains("mắc nhất")) {
            return findPitchByPrice(pitches, false, pitchType);
        }
        return null;
    }

    private static final String SYSTEM_INSTRUCTION = """
Bạn là trợ lý AI chuyên xử lý các yêu cầu liên quan đến sân thể thao. Hãy phân tích input của người dùng và trả về JSON **THUẦN** với định dạng sau, đảm bảo không có lỗi cú pháp và đúng chính tả:

{
  "bookingDate": "yyyy-MM-dd",
  "slotList": [danh_sách_số_slot],
  "pitchType": "FIVE_A_SIDE" | "SEVEN_A_SIDE" | "ELEVEN_A_SIDE" | "ALL",
  "message": "thông_điệp_phản_hồi",
  "data": {}
}

❗️Lưu ý quan trọng:
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
7. Hỏi sân có đánh giá cao nhất (ví dụ: "Sân nào có đánh giá cao nhất?"):
   - `data`: {"selectedPitch": {"pitchId": "uuid", "name": "tên sân", "price": số, "description": "mô tả", "type": "FIVE_A_SIDE | SEVEN_A_SIDE | ELEVEN_A_SIDE"}}
   - `message`: "Sân có đánh giá cao nhất là [tên sân]."
8. Đề cập "sân này" (ví dụ: "Đặt sân này lúc 7h ngày mai"):
   - Nếu có sân trong ngữ cảnh (rẻ nhất/mắc nhất/đánh giá cao nhất), tự động sử dụng sân đó
   - Nếu không có sân trong session, tìm sân rẻ/mắc nhất/đánh giá cao nhất theo yêu cầu trước đó
   - `message`: "Đang xử lý đặt sân [tên sân]..."
""";
}