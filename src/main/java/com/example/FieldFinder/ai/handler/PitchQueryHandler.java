package com.example.FieldFinder.ai.handler;

import com.example.FieldFinder.Enum.PitchEnvironment;
import com.example.FieldFinder.ai.AIChat;
import com.example.FieldFinder.ai.AiChatSessionContextStore;
import com.example.FieldFinder.ai.cache.AiCatalogCache;
import com.example.FieldFinder.ai.util.AiTextUtil;
import com.example.FieldFinder.dto.res.PitchResponseDTO;
import com.example.FieldFinder.dto.res.ProviderBookingResponseDTO;
import com.example.FieldFinder.entity.log.InteractionLog;
import com.example.FieldFinder.service.BookingService;
import com.example.FieldFinder.service.UserService;
import com.example.FieldFinder.service.log.LogPublisherService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Intent đặt/tìm sân (list/recommend/availability/book/my-bookings/cheapest...) — tách khỏi AIChat.
 * Gồm ranking sân (proximity/history/profile), tín hiệu user, phát hiện lịch trùng. Logic giữ nguyên 1:1.
 */
@Component
public class PitchQueryHandler {

    private static final String MODEL_VERSION = "gemini-2.5-flash";

    private final AiCatalogCache catalogCache;
    private final BookingService bookingService;
    private final AiChatSessionContextStore sessionContextStore;
    private final UserService userService;
    private final LogPublisherService logPublisherService;

    @Autowired(required = false)
    private MongoTemplate mongoTemplate;

    public PitchQueryHandler(AiCatalogCache catalogCache, BookingService bookingService,
                             AiChatSessionContextStore sessionContextStore, UserService userService,
                             LogPublisherService logPublisherService) {
        this.catalogCache = catalogCache;
        this.bookingService = bookingService;
        this.sessionContextStore = sessionContextStore;
        this.userService = userService;
        this.logPublisherService = logPublisherService;
    }

    public AIChat.BookingQuery handle(AIChat.BookingQuery query, String userInput, String sessionId,
                                          List<PitchResponseDTO> allPitches, Double userLat, Double userLng,
                                          String geminiRawJson) {
        if (query.data == null) query.data = new HashMap<>();
        if (query.slotList == null) query.slotList = new ArrayList<>();
        if (query.pitchType == null) query.pitchType = "ALL";

        AiReasoningTrace reasoning = new AiReasoningTrace();
        reasoning.step("📥 User input: \"" + userInput + "\"");
        if (geminiRawJson != null) {
            reasoning.parsed("geminiRawJson", geminiRawJson.length() > 1200
                    ? geminiRawJson.substring(0, 1200) + "..." : geminiRawJson);
        }
        recordGeminiPitchParse(reasoning, query, userLat, userLng);

        String action = (String) query.data.get("action");
        String originalAction = action;
        // "gần tôi" without an explicit listing action -> treat as a personalized recommendation.
        if (query.nearMe && (action == null || "list_pitches".equals(action))) {
            action = "recommend_pitch";
            reasoning.step("🔄 Override action: " + originalAction + " → recommend_pitch (vì nearMe=true)");
        }
        PitchEnvironment env = null;
        if (query.environment != null) {
            try { env = PitchEnvironment.valueOf(query.environment); } catch (Exception ignored) {}
        }
        if (env == null) env = AiTextUtil.detectEnvironmentFromInput(userInput);
        final PitchEnvironment requestedEnvironment = env;

        List<PitchResponseDTO> matched = allPitches.stream()
                .filter(p -> "ALL".equals(query.pitchType) || p.getType().name().equalsIgnoreCase(query.pitchType))
                .filter(p -> requestedEnvironment == null || p.getEnvironment() == requestedEnvironment)
                .collect(Collectors.toList());
        reasoning.step(String.format("🔍 Lọc loại sân (%s) + môi trường (%s): %d/%d sân",
                query.pitchType,
                requestedEnvironment != null ? requestedEnvironment.name() : "không lọc",
                matched.size(), allPitches.size()));

        // Hard filter theo khu vực user nêu (vd "sân 5 ở Gò Vấp") -> chỉ giữ sân có địa chỉ khớp.
        final String areaFilter = (query.location != null && !query.location.isBlank()) ? query.location.trim().toLowerCase() : null;
        if (areaFilter != null) {
            int beforeArea = matched.size();
            matched = matched.stream()
                    .filter(p -> p.getAddress() != null && p.getAddress().toLowerCase().contains(areaFilter))
                    .collect(Collectors.toList());
            reasoning.step(String.format("📍 Lọc khu vực '%s': %d/%d sân", query.location, matched.size(), beforeArea));
        } else {
            reasoning.step("📍 Không lọc khu vực (user không nêu location)");
        }

        boolean isAvailabilityCheck = "book_pitch".equals(action) || "check_pitch_availability".equals(action);
        if (isAvailabilityCheck && query.bookingDate != null && !query.slotList.isEmpty()) {
            try {
                LocalDate date = LocalDate.parse(query.bookingDate);
                int beforeAvail = matched.size();
                matched = matched.stream().filter(p -> {
                    List<Integer> booked = bookingService.getBookedTimeSlots(p.getPitchId(), date);
                    return query.slotList.stream().noneMatch(booked::contains);
                }).collect(Collectors.toList());
                reasoning.step(String.format("⏰ Lọc slot trống (ngày %s, slot %s): %d/%d sân còn trống",
                        query.bookingDate, query.slotList, matched.size(), beforeAvail));
            } catch (Exception e) {
                reasoning.step("⚠️ Lỗi parse ngày khi lọc availability: " + e.getMessage());
            }
        } else if (isAvailabilityCheck) {
            reasoning.step("⏰ Bỏ qua lọc slot (thiếu bookingDate hoặc slotList)");
        }

        String envStr = requestedEnvironment != null ? " " + AiTextUtil.formatEnvironment(requestedEnvironment) : "";
        String typeStr = AiTextUtil.formatPitchType(query.pitchType);

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
                PitchResponseDTO picked = AiTextUtil.findPitchByPrice(searchPool, cheapest);
                if (picked == null) {
                    query.message = "Không xác định được sân có giá phù hợp.";
                    break;
                }
                if (sessionId != null) sessionContextStore.setLastPitch(sessionId, picked);
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
                reasoning.step("🎯 Xử lý check_pitch_availability");
                if (matched.isEmpty()) {
                    if (query.bookingDate != null && !query.slotList.isEmpty()) {
                        query.message = String.format("Ngày %s: Rất tiếc, các%s %s đều đã được đặt kín trong khung giờ %s.", query.bookingDate, envStr, typeStr, query.slotList);
                    } else {
                        query.message = String.format("Không có%s %s nào phù hợp để kiểm tra.", envStr, typeStr);
                    }
                    break;
                }
                if (query.bookingDate == null) {
                    query.message = "Bạn muốn kiểm tra sân trống ngày nào? Vui lòng cho mình biết ngày (vd: " + LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).toString() + ").";
                    break;
                }
                try {
                    LocalDate date = LocalDate.parse(query.bookingDate);
                    List<Integer> desired = query.slotList.isEmpty() ? null : query.slotList;
                    List<Map<String, Object>> availability = new ArrayList<>();
                    List<PitchResponseDTO> finalMatched = new ArrayList<>();
                    for (PitchResponseDTO p : matched) {
                        List<Integer> booked = bookingService.getBookedTimeSlots(p.getPitchId(), date);
                        List<Integer> freeSlots = new ArrayList<>();
                        for (int s = 1; s <= 18; s++) if (!booked.contains(s)) freeSlots.add(s);
                        List<Integer> checkSlots = desired != null ? desired : freeSlots;
                        List<Integer> freeInRequested = checkSlots.stream().filter(freeSlots::contains).collect(Collectors.toList());
                        if (freeInRequested.isEmpty()) continue;

                        Map<String, Object> item = new HashMap<>();
                        item.put("pitchId", p.getPitchId());
                        item.put("name", p.getName());
                        item.put("address", p.getAddress());
                        item.put("availableSlots", freeInRequested);
                        availability.add(item);
                        finalMatched.add(p);
                    }
                    query.data.put("availability", availability);
                    query.data.put("matchedPitches", finalMatched);
                    long avail = finalMatched.size();
                    String slotStr = desired != null ? " khung giờ " + desired : "";
                    if (avail == 0) {
                        query.message = String.format("Ngày %s%s: Rất tiếc, các%s %s đều đã kín lịch.", query.bookingDate, slotStr, envStr, typeStr);
                    } else {
                        query.message = String.format("Ngày %s: có %d%s %s còn slot trống%s. Xem chi tiết bên dưới 👇",
                                query.bookingDate, avail, envStr, typeStr, slotStr);
                    }
                } catch (Exception e) {
                    query.message = "Ngày bạn cung cấp không hợp lệ. Vui lòng nhập theo dạng yyyy-MM-dd.";
                }
                break;
            }
            case "book_pitch": {
                reasoning.step("🎯 Xử lý book_pitch");
                if (query.bookingDate != null && !query.slotList.isEmpty()) {
                    UUID bookUserId = catalogCache.resolveCurrentUserId(sessionId);
                    try {
                        LocalDate bookDate = LocalDate.parse(query.bookingDate);
                        Optional<ProviderBookingResponseDTO> conflict =
                                findUserScheduleConflict(bookUserId, bookDate, query.slotList);
                        if (conflict.isPresent()) {
                            ProviderBookingResponseDTO existing = conflict.get();
                            reasoning.step(String.format("❌ Trùng lịch cá nhân: đã có đơn \"%s\" (%s) ngày %s trùng slot %s",
                                    existing.getPitchName(), formatSlotRange(existing.getSlots()),
                                    query.bookingDate, query.slotList));
                            reasoning.parsed("outcome", "schedule_conflict");
                            query.message = String.format(
                                    "Bạn đã có lịch đặt sân \"%s\" (%s) ngày %s — trùng với khung giờ %s bạn muốn đặt. Vui lòng chọn giờ khác hoặc hủy đơn cũ trước nhé.",
                                    existing.getPitchName() != null ? existing.getPitchName() : "không xác định",
                                    formatSlotRange(existing.getSlots()),
                                    query.bookingDate,
                                    formatSlotRange(query.slotList));
                            query.data.put("scheduleConflict", true);
                            query.data.put("conflictingBooking", existing);
                            break;
                        }
                        reasoning.step("✅ Không trùng lịch cá nhân");
                    } catch (Exception e) {
                        reasoning.step("⚠️ Lỗi kiểm tra trùng lịch: " + e.getMessage());
                    }
                } else {
                    reasoning.step("⏭️ Bỏ qua kiểm tra trùng lịch (thiếu ngày hoặc slot)");
                }
                if (matched.isEmpty()) {
                    reasoning.step("❌ Không còn sân phù hợp sau các bước lọc");
                    reasoning.parsed("outcome", "no_pitch_matched");
                    if (query.bookingDate != null && !query.slotList.isEmpty()) {
                        query.message = String.format("Rất tiếc,%s %s đều đã kín lịch trong khung giờ %s ngày %s.", envStr, typeStr, query.slotList, query.bookingDate);
                    } else {
                        query.message = String.format("Không tìm thấy%s %s nào phù hợp để đặt.", envStr, typeStr);
                    }
                    break;
                }
                PitchResponseDTO target = null;
                if (sessionId != null && sessionContextStore.getLastPitch(sessionId) != null) {
                    PitchResponseDTO cached = sessionContextStore.getLastPitch(sessionId);
                    if (matched.stream().anyMatch(p -> p.getPitchId().equals(cached.getPitchId()))) {
                        target = cached;
                        reasoning.step("🏟️ Chọn sân từ session cache: \"" + cached.getName() + "\"");
                    }
                }
                if (target == null) {
                    String lowerInput = userInput.toLowerCase();
                    for (PitchResponseDTO pitch : matched) {
                        if (lowerInput.contains(pitch.getName().toLowerCase())) {
                            target = pitch;
                            reasoning.step("🏟️ Khớp tên sân trong input: \"" + pitch.getName() + "\"");
                            break;
                        }
                    }
                    if (target == null) {
                        reasoning.step("🏟️ Không khớp tên sân cụ thể trong input → sẽ list sân hoặc chọn mặc định");
                    }
                }

                // Khi user không chỉ định cụ thể tên sân → show list (availability-style)
                // để user chọn sân muốn đặt, thay vì BE tự pick sân đầu tiên.
                if (target == null && query.bookingDate != null && !query.slotList.isEmpty()) {
                    try {
                        LocalDate date = LocalDate.parse(query.bookingDate);
                        List<Integer> desired = query.slotList;
                        List<Map<String, Object>> availability = new ArrayList<>();
                        List<PitchResponseDTO> finalMatched = new ArrayList<>();
                        for (PitchResponseDTO p : matched) {
                            List<Integer> booked = bookingService.getBookedTimeSlots(p.getPitchId(), date);
                            List<Integer> freeInRequested = desired.stream().filter(s -> !booked.contains(s)).collect(Collectors.toList());
                            if (freeInRequested.size() != desired.size()) continue; // cần đủ slot yêu cầu mới list

                            Map<String, Object> item = new HashMap<>();
                            item.put("pitchId", p.getPitchId());
                            item.put("name", p.getName());
                            item.put("address", p.getAddress());
                            item.put("availableSlots", freeInRequested);
                            availability.add(item);
                            finalMatched.add(p);
                        }
                        if (finalMatched.isEmpty()) {
                            reasoning.step("❌ Không sân nào còn đủ slot yêu cầu (sau kiểm tra chi tiết từng sân)");
                            reasoning.parsed("outcome", "all_slots_full");
                            query.message = String.format("Rất tiếc, các%s %s đều không còn trống đủ khung giờ %s ngày %s.",
                                    envStr, typeStr, query.slotList, query.bookingDate);
                        } else {
                            int minSlot = Collections.min(query.slotList);
                            int maxSlot = Collections.max(query.slotList);
                            int startHour = minSlot + 5;
                            int endHour = maxSlot + 6;
                            reasoning.step(String.format("📋 Chế độ list: %d sân trống đủ slot %s (%dh-%dh)",
                                    finalMatched.size(), query.slotList, startHour, endHour));
                            reasoning.parsed("outcome", "list_pitches_for_booking");
                            reasoning.parsed("listedPitchCount", finalMatched.size());
                            query.message = String.format("Có %d%s %s trống từ %dh đến %dh ngày %s. Chọn sân bên dưới để đặt 👇",
                                    finalMatched.size(), envStr, typeStr, startHour, endHour, query.bookingDate);
                            query.data.put("availability", availability);
                            query.data.put("matchedPitches", finalMatched);
                            query.data.put("pendingBooking", true);
                            query.data.put("showBookingButton", true);
                            query.data.put("bookingDate", query.bookingDate);
                            query.data.put("slotList", query.slotList);
                        }
                    } catch (Exception e) {
                        reasoning.step("⚠️ Lỗi parse ngày trong book_pitch list mode: " + e.getMessage());
                        query.message = "Ngày bạn cung cấp không hợp lệ. Vui lòng nhập theo dạng yyyy-MM-dd.";
                    }
                    break;
                }

                if (target == null) {
                    target = matched.get(0);
                    reasoning.step("🏟️ Fallback chọn sân đầu tiên trong danh sách: \"" + target.getName() + "\"");
                }

                if (query.bookingDate != null && !query.slotList.isEmpty()) {
                    int minSlot = Collections.min(query.slotList);
                    int maxSlot = Collections.max(query.slotList);
                    int startHour = minSlot + 5;
                    int endHour = maxSlot + 6;
                    query.message = String.format("Mình đã giúp bạn chuẩn bị thông tin đặt sân \"%s\" từ %dh đến %dh ngày %s, bạn vui lòng nhấn vào đây để tiến hành thanh toán nhé 👇",
                            target.getName(), startHour, endHour, query.bookingDate);
                } else {
                    query.message = String.format("Bạn muốn đặt sân \"%s\" (%s%s) tại %s. Nhấn nút bên dưới để chọn ngày và khung giờ nhé 👇",
                            target.getName(), typeStr, envStr, target.getAddress());
                }

                reasoning.parsed("outcome", "direct_booking");
                reasoning.parsed("selectedPitch", target.getName());
                reasoning.step("✅ Chuẩn bị đặt trực tiếp sân \"" + target.getName() + "\"");
                query.data.put("pendingBooking", true);
                query.data.put("suggestedPitch", target);
                query.data.put("showBookingButton", true);
                if (query.bookingDate != null) query.data.put("bookingDate", query.bookingDate);
                if (!query.slotList.isEmpty()) query.data.put("slotList", query.slotList);
                break;
            }
            case "list_my_bookings": {
                UUID userId = catalogCache.resolveCurrentUserId(sessionId);
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
            case "recommend_pitch": {
                reasoning.step("🎯 Xử lý recommend_pitch (cá nhân hóa)");
                PitchRankResult rr = rankRecommendedPitches(matched, sessionId, query.location, userLat, userLng, query.nearMe, 10);
                List<PitchResponseDTO> ranked = rr.pitches;
                if (ranked.isEmpty()) {
                    query.message = String.format("Hiện chưa có%s %s nào phù hợp để gợi ý cho bạn.", envStr, typeStr);
                } else if (query.message == null || query.message.isEmpty()) {
                    List<String> bits = new ArrayList<>();
                    if (rr.usedProximity) bits.add("gần bạn");
                    if (rr.usedHistory)   bits.add("theo lịch sử đặt/xem");
                    if (rr.usedProfile)   bits.add("hợp sở thích");
                    String basis = bits.isEmpty() ? "" : " (" + String.join(", ", bits) + ")";
                    query.message = String.format("Gợi ý %d sân phù hợp với bạn%s 👇", ranked.size(), basis);
                    if (query.nearMe && !rr.usedProximity) {
                        query.message += "\n(Bật chia sẻ vị trí để mình gợi ý sân gần bạn chính xác hơn nhé.)";
                    }
                }
                reasoning.step(String.format("✅ Gợi ý %d sân (proximity=%s, history=%s, profile=%s)",
                        ranked.size(), rr.usedProximity, rr.usedHistory, rr.usedProfile));
                reasoning.parsed("outcome", "recommend_pitch");
                query.data.put("matchedPitches", ranked);
                break;
            }
            case "list_pitches":
            default: {
                reasoning.step("🎯 Xử lý " + action);
                String areaStr = areaFilter != null ? " ở " + query.location.trim() : "";
                if (matched.isEmpty()) {
                    query.message = String.format("Rất tiếc, không tìm thấy%s %s nào phù hợp%s.", envStr, typeStr, areaStr);
                } else {
                    query.message = String.format("Đã tìm thấy %d%s %s%s. Xem danh sách bên dưới 👇", matched.size(), envStr, typeStr, areaStr);
                }
                query.data.put("matchedPitches", matched);
                break;
            }
        }

        reasoning.step("💬 Response: " + (query.message != null ? query.message : "(null)"));
        reasoning.parsed("finalAction", action);
        reasoning.parsed("finalMatchedCount", matched.size());
        query.data.put("aiReasoning", reasoning.toMap());

        try {
            String userIdStr = null;
            if (sessionId != null) {
                UUID uid = catalogCache.resolveCurrentUserId(sessionId);
                if (uid != null) userIdStr = uid.toString();
            }
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("raw_user_input", userInput);
            metadata.put("pitch_action", action);
            metadata.put("matched_pitch_count", matched.size());
            metadata.put("requested_pitch_type", query.pitchType);
            metadata.put("aiResponseText", query.message);
            metadata.put("modelVersion", MODEL_VERSION);
            metadata.put("aiReasoning", reasoning.toMap());
            if (requestedEnvironment != null) metadata.put("requested_environment", requestedEnvironment.name());
            if (query.bookingDate != null) metadata.put("booking_date", query.bookingDate);
            if (!query.slotList.isEmpty()) metadata.put("slot_list", query.slotList);
            // Log matched pitch IDs for ML
            List<String> matchedPitchIds = matched.stream()
                    .map(p -> p.getPitchId().toString())
                    .collect(Collectors.toList());
            metadata.put("retrievedItemIds", matchedPitchIds);
            logPublisherService.publishEvent(userIdStr, sessionId, "CHAT_PITCH_QUERY", null, null, metadata, "AI_Chatbot");
        } catch (Exception e) {
            System.err.println("Không thể ghi log CHAT_PITCH_QUERY: " + e.getMessage());
        }

        if (query.message == null || query.message.isBlank()) {
            query.message = "Mình đã ghi nhận yêu cầu sân của bạn, nhưng chưa có dữ liệu phù hợp để trả lời.";
        }
        return query;
    }

    private void recordGeminiPitchParse(AiReasoningTrace trace, AIChat.BookingQuery query, Double userLat, Double userLng) {
        String action = query.data != null ? (String) query.data.get("action") : null;
        trace.parsed("action", action);
        trace.parsed("bookingDate", query.bookingDate);
        trace.parsed("slotList", query.slotList);
        trace.parsed("pitchType", query.pitchType);
        trace.parsed("environment", query.environment);
        trace.parsed("location", query.location);
        trace.parsed("nearMe", query.nearMe);
        trace.parsed("userLat", userLat);
        trace.parsed("userLng", userLng);
        String slotRange = (query.slotList != null && !query.slotList.isEmpty())
                ? formatSlotRange(query.slotList) : "chưa có";
        trace.step(String.format(
                "🤖 Gemini hiểu → action=%s | loại sân=%s | ngày=%s | giờ=%s (%s) | env=%s | khu vực=%s | nearMe=%s | GPS=(%s,%s)",
                action, query.pitchType, query.bookingDate, query.slotList, slotRange,
                query.environment, query.location, query.nearMe, userLat, userLng));
    }

    /** Step-by-step trace of AI decision-making for pitch/booking flows. */
    private static final class AiReasoningTrace {
        private final List<String> steps = new ArrayList<>();
        private final Map<String, Object> parsed = new LinkedHashMap<>();

        void step(String message) {
            steps.add(message);
            System.out.println("[AI-REASONING] " + message);
        }

        void parsed(String key, Object value) {
            parsed.put(key, value);
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("steps", new ArrayList<>(steps));
            out.put("parsed", new LinkedHashMap<>(parsed));
            return out;
        }
    }

    /** Result of personalized pitch ranking + which signal groups actually contributed. */
    public static class PitchRankResult {
        public final List<PitchResponseDTO> pitches;
        public final boolean usedProximity;
        public final boolean usedHistory;
        public final boolean usedProfile;
        PitchRankResult(List<PitchResponseDTO> p, boolean prox, boolean hist, boolean prof) {
            this.pitches = p; this.usedProximity = prox; this.usedHistory = hist; this.usedProfile = prof;
        }
    }

    /** Khung giờ "Xh đến Yh" từ list slot. (cũng dùng bởi handleWeatherQuery của AIChat) */
    public String formatSlotRange(List<Integer> slots) {
        if (slots == null || slots.isEmpty()) return "";
        int minSlot = Collections.min(slots);
        int maxSlot = Collections.max(slots);
        return String.format("%dh đến %dh", minSlot + 5, maxSlot + 6);
    }

    /**
     * Personalized pitch recommendation ranking.
     * score = 0.40*proximity + 0.35*history + 0.15*profile + 0.10*explicitArea.
     * History (rebook/viewed/fav-type/fav-area) outweighs profile per product spec
     * (profile is editable, behaviour is stronger signal). Proximity uses request GPS
     * (userLat/userLng) and falls back to saved profile coords. Returns ≤ {@code limit}
     * pitches sorted by score desc — never the full catalog.
     */
    public PitchRankResult rankRecommendedPitches(List<PitchResponseDTO> candidates, String sessionId,
                                                   String explicitLocation, Double userLat, Double userLng,
                                                   boolean nearMe, int limit) {
        if (candidates == null || candidates.isEmpty()) {
            return new PitchRankResult(new ArrayList<>(), false, false, false);
        }

        UUID uid = catalogCache.resolveCurrentUserId(sessionId);
        PitchUserSignals sig = buildPitchUserSignals(uid, userLat, userLng);

        final Double lat = sig.lat;
        final Double lng = sig.lng;
        final boolean hasCoords = lat != null && lng != null;
        final String fArea = (explicitLocation != null && !explicitLocation.isBlank())
                ? explicitLocation.trim().toLowerCase() : null;

        // Flags: which signal groups actually moved the ranking (for the response message).
        boolean[] used = {false, false, false}; // [0]=proximity [1]=history [2]=profile

        // "Gần tôi": với intent vị trí cụ thể (vd "tìm sân gần tôi", "sân nào gần đây"),
        // ưu tiên KHOẢNG CÁCH GPS thay vì blend cá nhân hoá. Chỉ áp dụng khi thực sự có
        // toạ độ và có ít nhất một sân có toạ độ; nếu không, rơi về xếp hạng cá nhân hoá
        // bên dưới (message sẽ nhắc bật chia sẻ vị trí vì usedProximity=false).
        if (nearMe && hasCoords) {
            List<PitchResponseDTO> byDistance = candidates.stream()
                    .filter(p -> p.getLatitude() != null && p.getLongitude() != null)
                    .sorted((a, b) -> Double.compare(
                            haversineKm(lat, lng, a.getLatitude(), a.getLongitude()),
                            haversineKm(lat, lng, b.getLatitude(), b.getLongitude())))
                    .limit(Math.max(1, limit))
                    .collect(Collectors.toList());
            if (!byDistance.isEmpty()) {
                double nearestKm = haversineKm(lat, lng,
                        byDistance.get(0).getLatitude(), byDistance.get(0).getLongitude());
                System.out.println("🎯 Pitch reco [nearMe]: candidates=" + candidates.size()
                        + " returned=" + byDistance.size()
                        + " sorted=distance nearestKm=" + String.format("%.2f", nearestKm));
                return new PitchRankResult(byDistance, true, false, false);
            }
            // Không sân nào có toạ độ -> rơi về blend cá nhân hoá bên dưới.
        }

        List<PitchResponseDTO> ranked = candidates.stream()
                .sorted((a, b) -> Double.compare(
                        scorePitch(b, sig, hasCoords, lat, lng, fArea, used),
                        scorePitch(a, sig, hasCoords, lat, lng, fArea, used)))
                .limit(Math.max(1, limit))
                .collect(Collectors.toList());

        System.out.println("🎯 Pitch reco: candidates=" + candidates.size() + " returned=" + ranked.size()
                + " coords=" + hasCoords + " favType=" + sig.favType + " booked=" + sig.bookedPitchIds.size()
                + " viewed=" + sig.viewedPitchIds.size() + " district=" + sig.district);

        return new PitchRankResult(ranked, used[0], used[1], used[2]);
    }

    private double scorePitch(PitchResponseDTO p, PitchUserSignals sig, boolean hasCoords,
                              Double lat, Double lng, String area, boolean[] used) {
        double score = 0.0;
        String addr = p.getAddress() != null ? p.getAddress().toLowerCase() : "";

        // Proximity (0..1): closer = higher. 0km->1.0, 1km->0.5, 4km->0.2.
        if (hasCoords && p.getLatitude() != null && p.getLongitude() != null) {
            double km = haversineKm(lat, lng, p.getLatitude(), p.getLongitude());
            double prox = 1.0 / (1.0 + km);
            used[0] = true;
            score += 0.40 * prox;
        }

        // History (0..1): rebook > viewed, plus favourite type + favourite area.
        double hist = 0.0;
        if (p.getPitchId() != null && sig.bookedPitchIds.contains(p.getPitchId())) hist = 1.0;
        else if (p.getPitchId() != null && sig.viewedPitchIds.contains(p.getPitchId())) hist = 0.6;
        if (sig.favType != null && p.getType() != null && p.getType().name().equals(sig.favType)) hist += 0.4;
        if (!sig.bookedAreaTokens.isEmpty()) {
            for (String tok : sig.bookedAreaTokens) {
                if (addr.contains(tok)) { hist += 0.3; break; }
            }
        }
        hist = Math.min(1.0, hist);
        if (hist > 0) used[1] = true;
        score += 0.35 * hist;

        // Profile (0..1): district + preferred type (lower weight than history).
        double prof = 0.0;
        if (sig.district != null && !sig.district.isBlank() && addr.contains(sig.district)) prof += 0.6;
        if (sig.prefType != null && p.getType() != null && p.getType().name().equals(sig.prefType)) prof += 0.4;
        prof = Math.min(1.0, prof);
        if (prof > 0) used[2] = true;
        score += 0.15 * prof;

        // Explicit area keyword from the query (mild tie-breaker; usually already hard-filtered).
        if (area != null && addr.contains(area)) score += 0.10;

        return score;
    }

    /** Aggregated personalization signals for a user, computed once per request. */
    private static class PitchUserSignals {
        Double lat, lng;
        String district;
        String prefType;
        Set<UUID> bookedPitchIds = new HashSet<>();
        Set<UUID> viewedPitchIds = new HashSet<>();
        String favType;                                  // most-booked pitch type
        Set<String> bookedAreaTokens = new HashSet<>();  // address keywords from booked pitches
    }

    private PitchUserSignals buildPitchUserSignals(UUID uid, Double reqLat, Double reqLng) {
        PitchUserSignals s = new PitchUserSignals();
        s.lat = reqLat;
        s.lng = reqLng;
        if (uid == null) return s;

        // Profile: district, preferred type, fallback coords.
        try {
            var profile = userService.getUserById(uid);
            if (profile != null) {
                if (profile.getDistrict() != null) s.district = profile.getDistrict().toLowerCase();
                if (profile.getPreferredPitchType() != null) s.prefType = profile.getPreferredPitchType().name();
                if (s.lat == null || s.lng == null) {
                    s.lat = profile.getLatitude();
                    s.lng = profile.getLongitude();
                }
            }
        } catch (Exception e) {
            System.err.println("buildPitchUserSignals profile error: " + e.getMessage());
        }

        // Booking history: booked pitch ids, favourite type, booked-area tokens.
        try {
            Map<UUID, PitchResponseDTO> byId = new HashMap<>();
            for (PitchResponseDTO p : catalogCache.getAllPitchesCached()) {
                if (p.getPitchId() != null) byId.put(p.getPitchId(), p);
            }
            Map<String, Integer> typeCount = new HashMap<>();
            var bookings = bookingService.getBookingsByUser(uid);
            for (var b : bookings) {
                if (b.getPitchId() == null) continue;
                s.bookedPitchIds.add(b.getPitchId());
                PitchResponseDTO p = byId.get(b.getPitchId());
                if (p != null) {
                    if (p.getType() != null) typeCount.merge(p.getType().name(), 1, Integer::sum);
                    if (p.getAddress() != null) {
                        for (String tok : p.getAddress().toLowerCase().split("[,\\-]")) {
                            String t = tok.trim();
                            if (t.length() >= 4) s.bookedAreaTokens.add(t);
                        }
                    }
                }
            }
            s.favType = typeCount.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse(null);
        } catch (Exception e) {
            System.err.println("buildPitchUserSignals booking error: " + e.getMessage());
        }

        // Viewed-but-not-booked pitches from interaction logs (Mongo VIEW_PITCH).
        try {
            s.viewedPitchIds.addAll(loadViewedPitchIds(uid, 50));
        } catch (Exception e) {
            System.err.println("buildPitchUserSignals view error: " + e.getMessage());
        }

        return s;
    }

    /** Recently viewed pitch ids from Mongo interaction logs. Empty if Mongo unavailable. */
    private Set<UUID> loadViewedPitchIds(UUID userId, int limit) {
        Set<UUID> ids = new HashSet<>();
        if (mongoTemplate == null || userId == null) return ids;
        try {
            org.springframework.data.mongodb.core.query.Query q =
                    org.springframework.data.mongodb.core.query.Query.query(
                            org.springframework.data.mongodb.core.query.Criteria.where("userId").is(userId.toString())
                                    .and("eventType").is("VIEW_PITCH")
                                    .and("itemType").is("PITCH"))
                            .with(org.springframework.data.domain.Sort.by(
                                    org.springframework.data.domain.Sort.Direction.DESC, "timestamp"));
            q.limit(limit);
            List<com.example.FieldFinder.entity.log.InteractionLog> logs =
                    mongoTemplate.find(q, com.example.FieldFinder.entity.log.InteractionLog.class);
            for (var l : logs) {
                if (l.getItemId() == null) continue;
                try { ids.add(UUID.fromString(l.getItemId())); } catch (IllegalArgumentException ignored) {}
            }
        } catch (Exception e) {
            System.err.println("loadViewedPitchIds error: " + e.getMessage());
        }
        return ids;
    }

    /** Haversine great-circle distance in km. */
    private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private Optional<ProviderBookingResponseDTO> findUserScheduleConflict(UUID userId, LocalDate date, List<Integer> requestedSlots) {
        if (userId == null || date == null || requestedSlots == null || requestedSlots.isEmpty()) {
            return Optional.empty();
        }
        try {
            for (ProviderBookingResponseDTO booking : bookingService.getBookingsByUser(userId)) {
                if (booking.getBookingDate() == null || !date.equals(booking.getBookingDate())) continue;
                if ("CANCELED".equalsIgnoreCase(booking.getStatus())) continue;
                if (booking.getSlots() == null || booking.getSlots().isEmpty()) continue;
                boolean overlap = requestedSlots.stream().anyMatch(booking.getSlots()::contains);
                if (overlap) return Optional.of(booking);
            }
        } catch (Exception e) {
            System.err.println("findUserScheduleConflict error: " + e.getMessage());
        }
        return Optional.empty();
    }
}
