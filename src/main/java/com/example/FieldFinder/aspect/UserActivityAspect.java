package com.example.FieldFinder.aspect;

import com.example.FieldFinder.service.log.LogPublisherService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Aspect
@Component
@RequiredArgsConstructor
public class UserActivityAspect {

    private final LogPublisherService logPublisherService;
    private final ObjectMapper mapper = new ObjectMapper();

    // 1. NGHE SỰ KIỆN XEM SÂN
    @AfterReturning(
            pointcut = "execution(* com.example.FieldFinder.controller.PitchController.getPitchById(..)) && args(pitchId)",
            returning = "result",
            argNames = "pitchId,result")
    public void logViewPitch(UUID pitchId, Object result) {
        if (isSuccessResponse(result)) {
            publishLog("VIEW_PITCH", pitchId.toString(), "PITCH", new HashMap<>());
        }
    }

    // 2. NGHE SỰ KIỆN TẠO BOOKING CÓ TRÍCH XUẤT DETAILS
    @AfterReturning(
            pointcut = "execution(* com.example.FieldFinder.controller.BookingController.create*(..))",
            returning = "result"
    )
    public void logCreateBooking(JoinPoint joinPoint, Object result) {
        if (isSuccessResponse(result)) {
            Map<String, Object> metadata = new HashMap<>();
            String bookingId = extractIdFromResponse(result, "bookingId");

            // Lấy Arguments (chính là @RequestBody BookingRequestDTO mà Client gửi lên)
            Object[] args = joinPoint.getArgs();
            for (Object arg : args) {
                if (arg != null && arg.getClass().getSimpleName().contains("BookingRequestDTO")) {
                    try {
                        // Chuyển Object thành JSON Node để bóc tách dễ dàng
                        JsonNode root = mapper.valueToTree(arg);

                        metadata.put("requested_pitch_id", root.path("pitchId").asText(null));
                        metadata.put("total_price", root.path("totalPrice").asDouble(0.0));
                        metadata.put("booking_date", root.path("bookingDate").asText(null));

                        // Lấy mảng bookingDetails (các slot người dùng chọn)
                        if (root.has("bookingDetails")) {
                            metadata.put("booking_details", mapper.convertValue(root.path("bookingDetails"), Object.class));
                        }
                    } catch (Exception e) {
                        System.err.println("Lỗi bóc tách Booking DTO trong AOP: " + e.getMessage());
                    }
                }
            }

            publishLog("CREATE_BOOKING", bookingId, "BOOKING", metadata);
        }
    }

    // 3. NGHE SỰ KIỆN TẠO ĐƠN HÀNG MUA SẢN PHẨM
    @AfterReturning(
            pointcut = "execution(* com.example.FieldFinder.controller.OrderController.create*(..))",
            returning = "result"
    )
    public void logCreateOrder(JoinPoint joinPoint, Object result) {
        if (isSuccessResponse(result)) {
            Map<String, Object> metadata = new HashMap<>();
            String orderId = extractIdFromResponse(result, "orderId");

            Object[] args = joinPoint.getArgs();
            for (Object arg : args) {
                if (arg != null && arg.getClass().getSimpleName().contains("OrderRequestDTO")) {
                    try {
                        JsonNode root = mapper.valueToTree(arg);
                        metadata.put("total_amount", root.path("totalAmount").asDouble(0.0));
                        metadata.put("payment_method", root.path("paymentMethod").asText(null));

                        // Chi tiết sản phẩm mua
                        if (root.has("items")) {
                            metadata.put("items", mapper.convertValue(root.path("items"), Object.class));
                        }
                    } catch (Exception e) {
                        System.err.println("Lỗi bóc tách Order DTO trong AOP: " + e.getMessage());
                    }
                }
            }

            publishLog("CREATE_ORDER", orderId, "ORDER", metadata);
        }
    }

    private boolean isSuccessResponse(Object result) {
        return result instanceof ResponseEntity && ((ResponseEntity<?>) result).getStatusCode().is2xxSuccessful();
    }

    private String extractIdFromResponse(Object result, String idFieldName) {
        try {
            if (result instanceof ResponseEntity) {
                Object body = ((ResponseEntity<?>) result).getBody();
                if (body != null) {
                    JsonNode root = mapper.valueToTree(body);
                    return root.path(idFieldName).asText(null);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void publishLog(String eventType, String itemId, String itemType, Map<String, Object> metadata) {
        HttpServletRequest request = getRequest();
        String userId = getCurrentUserId();
        String sessionId = request != null ? request.getSession().getId() : "Unknown";
        String userAgent = request != null ? request.getHeader("User-Agent") : "Unknown";

        logPublisherService.publishEvent(
                userId, sessionId,
                eventType,
                itemId, itemType,
                metadata, userAgent
        );
    }

    private HttpServletRequest getRequest() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs != null ? attrs.getRequest() : null;
    }

    private String getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !auth.getPrincipal().equals("anonymousUser")) {
            return auth.getName();
        }
        return null;
    }
}