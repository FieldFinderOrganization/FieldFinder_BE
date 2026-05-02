package com.example.FieldFinder.aspect;

import com.example.FieldFinder.service.log.LogPublisherService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.*;
import java.util.stream.Collectors;

@Aspect
@Component
@RequiredArgsConstructor
public class UserActivityAspect {

    private final LogPublisherService logPublisherService;
    private final ObjectMapper mapper = new ObjectMapper();

    // ═══════════════════════════════════════════
    //  VIEW events — enriched với item snapshot
    // ═══════════════════════════════════════════

    @AfterReturning(
            pointcut = "execution(* com.example.FieldFinder.controller.PitchController.getPitchById(..)) && args(pitchId)",
            returning = "result",
            argNames = "pitchId,result")
    public void logViewPitch(UUID pitchId, Object result) {
        if (isSuccessResponse(result)) {
            Map<String, Object> metadata = extractPitchSnapshot(result);
            publishLog("VIEW_PITCH", pitchId.toString(), "PITCH", metadata);
        }
    }

    @AfterReturning(
            pointcut = "execution(* com.example.FieldFinder.controller.ProductController.getById(..)) && args(productId, ..)",
            returning = "result",
            argNames = "productId,result")
    public void logViewProduct(Long productId, Object result) {
        if (isSuccessResponse(result)) {
            Map<String, Object> metadata = extractProductSnapshot(result);
            publishLog("VIEW_PRODUCT", productId.toString(), "PRODUCT", metadata);
        }
    }

    // ═══════════════════════════════════════════
    //  IMPRESSION events — danh sách hiển thị
    // ═══════════════════════════════════════════

    @AfterReturning(
            pointcut = "execution(* com.example.FieldFinder.controller.PitchController.getAllPitches(..))",
            returning = "result")
    public void logPitchImpression(JoinPoint joinPoint, Object result) {
        try {
            List<String> shownIds = extractIdsFromPageResponse(result, "pitchId");
            if (shownIds.isEmpty()) return;

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("shownItemIds", shownIds);
            metadata.put("positions", buildPositionList(shownIds.size()));
            metadata.put("listType", "PITCH_LIST");
            metadata.put("filters", extractRequestParams("district", "type", "name"));

            publishLog("IMPRESSION_LIST", null, "PITCH", metadata);
        } catch (Exception e) {
            System.err.println("Lỗi ghi log IMPRESSION_LIST (Pitch): " + e.getMessage());
        }
    }

    @AfterReturning(
            pointcut = "execution(* com.example.FieldFinder.controller.ProductController.getAll(..))",
            returning = "result")
    public void logProductImpression(JoinPoint joinPoint, Object result) {
        try {
            List<String> shownIds = extractIdsFromPageResponse(result, "id");
            if (shownIds.isEmpty()) return;

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("shownItemIds", shownIds);
            metadata.put("positions", buildPositionList(shownIds.size()));
            metadata.put("listType", "PRODUCT_LIST");
            metadata.put("filters", extractRequestParams("categoryId", "genders", "brand"));

            publishLog("IMPRESSION_LIST", null, "PRODUCT", metadata);
        } catch (Exception e) {
            System.err.println("Lỗi ghi log IMPRESSION_LIST (Product): " + e.getMessage());
        }
    }

    @AfterReturning(
            pointcut = "execution(* com.example.FieldFinder.controller.ProductController.getTopSelling(..))",
            returning = "result")
    public void logTopSellingImpression(Object result) {
        try {
            List<String> shownIds = extractIdsFromListResponse(result, "id");
            if (shownIds.isEmpty()) return;

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("shownItemIds", shownIds);
            metadata.put("positions", buildPositionList(shownIds.size()));
            metadata.put("listType", "TOP_SELLING");

            publishLog("IMPRESSION_LIST", null, "PRODUCT", metadata);
        } catch (Exception e) {
            System.err.println("Lỗi ghi log IMPRESSION_LIST (TopSelling): " + e.getMessage());
        }
    }

    @AfterReturning(
            pointcut = "execution(* com.example.FieldFinder.controller.ProductController.getByCategories(..))",
            returning = "result")
    public void logByCategoriesImpression(Object result) {
        try {
            List<String> shownIds = extractIdsFromListResponse(result, "id");
            if (shownIds.isEmpty()) return;

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("shownItemIds", shownIds);
            metadata.put("positions", buildPositionList(shownIds.size()));
            metadata.put("listType", "BY_CATEGORIES");
            metadata.put("filters", extractRequestParams("categories"));

            publishLog("IMPRESSION_LIST", null, "PRODUCT", metadata);
        } catch (Exception e) {
            System.err.println("Lỗi ghi log IMPRESSION_LIST (ByCategories): " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════
    //  SEARCH & FILTER events
    // ═══════════════════════════════════════════

    @AfterReturning(
            pointcut = "execution(* com.example.FieldFinder.controller.ProductController.getAll(..)) && args(pageable, categoryId, genders, brand, ..)",
            returning = "result",
            argNames = "pageable,categoryId,genders,brand,result")
    public void logProductSearch(Object pageable, Long categoryId, Set<String> genders, String brand, Object result) {
        // Chỉ log khi có ít nhất 1 filter được áp dụng
        if (categoryId == null && (genders == null || genders.isEmpty()) && (brand == null || brand.isBlank())) return;

        try {
            Map<String, Object> metadata = new HashMap<>();
            if (categoryId != null) metadata.put("categoryId", categoryId);
            if (genders != null && !genders.isEmpty()) metadata.put("genders", genders);
            if (brand != null && !brand.isBlank()) metadata.put("brand", brand);

            int resultCount = extractPageTotalFromResponse(result);
            metadata.put("resultCount", resultCount);

            publishLog("SEARCH_QUERY", null, "PRODUCT", metadata);
        } catch (Exception e) {
            System.err.println("Lỗi ghi log SEARCH_QUERY: " + e.getMessage());
        }
    }

    @AfterReturning(
            pointcut = "execution(* com.example.FieldFinder.controller.PitchController.getAllPitches(..)) && args(pageable, district, type, name)",
            returning = "result",
            argNames = "pageable,district,type,name,result")
    public void logPitchFilter(Object pageable, String district, String type, String name, Object result) {
        if ((district == null || district.isBlank()) && (type == null || type.isBlank()) && (name == null || name.isBlank()))
            return;

        try {
            Map<String, Object> metadata = new HashMap<>();
            if (district != null && !district.isBlank()) metadata.put("district", district);
            if (type != null && !type.isBlank()) metadata.put("type", type);
            if (name != null && !name.isBlank()) metadata.put("name", name);

            int resultCount = extractPageTotalFromResponse(result);
            metadata.put("resultCount", resultCount);

            publishLog("FILTER_APPLY", null, "PITCH", metadata);
        } catch (Exception e) {
            System.err.println("Lỗi ghi log FILTER_APPLY: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════
    //  BOOKING / ORDER events (giữ nguyên)
    // ═══════════════════════════════════════════

    @AfterReturning(
            pointcut = "execution(* com.example.FieldFinder.controller.BookingController.create*(..))",
            returning = "result"
    )
    public void logCreateBooking(JoinPoint joinPoint, Object result) {
        if (isSuccessResponse(result)) {
            Map<String, Object> metadata = new HashMap<>();
            String bookingId = extractIdFromResponse(result, "bookingId");

            Object[] args = joinPoint.getArgs();
            for (Object arg : args) {
                if (arg != null && arg.getClass().getSimpleName().contains("BookingRequestDTO")) {
                    try {
                        JsonNode root = mapper.valueToTree(arg);
                        metadata.put("requested_pitch_id", root.path("pitchId").asText(null));
                        metadata.put("total_price", root.path("totalPrice").asDouble(0.0));
                        metadata.put("booking_date", root.path("bookingDate").asText(null));

                        if (root.has("bookingDetails")) {
                            metadata.put("booking_details", mapper.convertValue(root.path("bookingDetails"), Object.class));
                        }
                    } catch (Exception e) {
                        System.err.println("Lỗi bóc tách Booking DTO: " + e.getMessage());
                    }
                }
            }

            publishLog("CREATE_BOOKING", bookingId, "BOOKING", metadata);
        }
    }

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
                        if (root.has("orderDetails")) {
                            metadata.put("order_details", mapper.convertValue(root.path("orderDetails"), Object.class));
                        }
                    } catch (Exception e) {
                        System.err.println("Lỗi bóc tách Order DTO: " + e.getMessage());
                    }
                }
            }

            publishLog("CREATE_ORDER", orderId, "ORDER", metadata);
        }
    }

    // ═══════════════════════════════════════════
    //  Helper methods
    // ═══════════════════════════════════════════

    private Map<String, Object> extractPitchSnapshot(Object result) {
        Map<String, Object> metadata = new HashMap<>();
        try {
            Object body = result instanceof ResponseEntity ? ((ResponseEntity<?>) result).getBody() : result;
            if (body != null) {
                JsonNode root = mapper.valueToTree(body);
                metadata.put("item_pitch_type", root.path("type").asText(null));
                metadata.put("item_environment", root.path("environment").asText(null));
                metadata.put("item_price_snapshot", root.path("price").asDouble(0));
                metadata.put("item_name", root.path("name").asText(null));
                metadata.put("item_district", root.path("district").asText(null));
            }
        } catch (Exception e) {
            System.err.println("Lỗi snapshot pitch metadata: " + e.getMessage());
        }
        return metadata;
    }

    private Map<String, Object> extractProductSnapshot(Object result) {
        Map<String, Object> metadata = new HashMap<>();
        try {
            Object body = result instanceof ResponseEntity ? ((ResponseEntity<?>) result).getBody() : result;
            if (body != null) {
                JsonNode root = mapper.valueToTree(body);
                metadata.put("item_category", root.path("categoryName").asText(null));
                metadata.put("item_brand", root.path("brand").asText(null));
                metadata.put("item_price_snapshot", root.path("price").asDouble(0));
                metadata.put("item_name", root.path("name").asText(null));

                if (root.has("tags") && root.path("tags").isArray()) {
                    List<String> tags = new ArrayList<>();
                    root.path("tags").forEach(t -> tags.add(t.asText()));
                    metadata.put("item_tags", tags);
                }

                if (root.has("salePercent") && !root.path("salePercent").isNull()) {
                    metadata.put("item_sale_percent", root.path("salePercent").asInt(0));
                }
            }
        } catch (Exception e) {
            System.err.println("Lỗi snapshot product metadata: " + e.getMessage());
        }
        return metadata;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractIdsFromPageResponse(Object result, String idField) {
        try {
            Object body = result instanceof ResponseEntity ? ((ResponseEntity<?>) result).getBody() : result;
            if (body instanceof Page) {
                Page<?> page = (Page<?>) body;
                return page.getContent().stream()
                        .map(item -> {
                            JsonNode node = mapper.valueToTree(item);
                            return node.path(idField).asText(null);
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
            }
        } catch (Exception ignored) {}
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<String> extractIdsFromListResponse(Object result, String idField) {
        try {
            if (result instanceof List) {
                return ((List<?>) result).stream()
                        .map(item -> {
                            JsonNode node = mapper.valueToTree(item);
                            return node.path(idField).asText(null);
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
            }
        } catch (Exception ignored) {}
        return List.of();
    }

    private int extractPageTotalFromResponse(Object result) {
        try {
            Object body = result instanceof ResponseEntity ? ((ResponseEntity<?>) result).getBody() : result;
            if (body instanceof Page) {
                return (int) ((Page<?>) body).getTotalElements();
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private List<Integer> buildPositionList(int size) {
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < size; i++) positions.add(i);
        return positions;
    }

    private Map<String, String> extractRequestParams(String... paramNames) {
        Map<String, String> filters = new HashMap<>();
        HttpServletRequest request = getRequest();
        if (request == null) return filters;

        for (String param : paramNames) {
            String val = request.getParameter(param);
            if (val != null && !val.isBlank()) {
                filters.put(param, val);
            }
        }
        return filters;
    }

    private boolean isSuccessResponse(Object result) {
        if (result == null) return false;
        if (result instanceof ResponseEntity) {
            return ((ResponseEntity<?>) result).getStatusCode().is2xxSuccessful();
        }
        return true;
    }

    private String extractIdFromResponse(Object result, String idFieldName) {
        try {
            if (result instanceof ResponseEntity) {
                Object body = ((ResponseEntity<?>) result).getBody();
                if (body != null) {
                    JsonNode root = mapper.valueToTree(body);
                    return root.path(idFieldName).asText(null);
                }
            } else {
                JsonNode root = mapper.valueToTree(result);
                return root.path(idFieldName).asText(null);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void publishLog(String eventType, String itemId, String itemType, Map<String, Object> metadata) {
        HttpServletRequest request = getRequest();
        String userId = getCurrentUserId();
        String sessionId = request != null ? request.getSession().getId() : "Unknown";
        String userAgent = request != null ? request.getHeader("User-Agent") : "Unknown";

        // Parse X-Geo-Location header nếu frontend gửi (format: "lat,lng")
        Double lat = null, lng = null;
        if (request != null) {
            String geo = request.getHeader("X-Geo-Location");
            if (geo != null && geo.contains(",")) {
                try {
                    String[] parts = geo.split(",");
                    lat = Double.parseDouble(parts[0].trim());
                    lng = Double.parseDouble(parts[1].trim());
                } catch (NumberFormatException ignored) {}
            }
        }

        logPublisherService.publishEventEnriched(
                userId, sessionId,
                eventType,
                itemId, itemType,
                metadata, userAgent,
                lat, lng
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