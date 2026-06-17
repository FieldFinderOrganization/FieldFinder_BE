package com.example.FieldFinder.ai.handler;

import com.example.FieldFinder.ai.AIChat;
import com.example.FieldFinder.ai.AiChatSessionContextStore;
import com.example.FieldFinder.ai.cache.AiCatalogCache;
import com.example.FieldFinder.ai.util.AiTextUtil;
import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.example.FieldFinder.entity.Discount;
import com.example.FieldFinder.repository.DiscountRepository;
import com.example.FieldFinder.service.CategoryService;
import com.example.FieldFinder.service.ProductService;
import com.example.FieldFinder.service.log.LogPublisherService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Intent truy vấn sản phẩm (giá/sale/tồn/size/chi tiết/gợi ý theo từ khóa) — tách khỏi AIChat.
 * Logic giữ nguyên 1:1. AIChat.handleProductQuery ủy quyền vào đây.
 */
@Component
public class ProductQueryHandler {

    private static final String MODEL_VERSION = "gemini-2.5-flash";

    private final AiCatalogCache catalogCache;
    private final ProductService productService;
    private final AiChatSessionContextStore sessionContextStore;
    private final LogPublisherService logPublisherService;
    private final CategoryService categoryService;
    private final DiscountRepository discountRepository;

    public ProductQueryHandler(AiCatalogCache catalogCache, ProductService productService,
                               AiChatSessionContextStore sessionContextStore,
                               LogPublisherService logPublisherService, CategoryService categoryService,
                               DiscountRepository discountRepository) {
        this.catalogCache = catalogCache;
        this.productService = productService;
        this.sessionContextStore = sessionContextStore;
        this.logPublisherService = logPublisherService;
        this.categoryService = categoryService;
        this.discountRepository = discountRepository;
    }

    /**
     * Câu thông báo các chương trình giảm giá TOÀN SHOP (mã GLOBAL) đang chạy, hoặc null nếu không có.
     * Mã GLOBAL không gắn vào salePercent từng sản phẩm nên các intent on-sale (đọc salePercent)
     * không "thấy" nó — helper này bù lại để chatbot không trả lời "chưa có" khi đang có mã toàn shop.
     */
    private String globalCampaignAnnouncement() {
        List<Discount> globals;
        try {
            globals = discountRepository.findActiveGlobalPromotions();
        } catch (Exception e) {
            return null;
        }
        if (globals == null || globals.isEmpty()) return null;

        if (globals.size() == 1) {
            return "Hiện hệ thống đang có chương trình " + describeGlobal(globals.get(0))
                    + " — bạn hãy trải nghiệm và mua sắm ngay nhé! 🛍️";
        }
        StringBuilder sb = new StringBuilder("Hiện hệ thống đang có các chương trình giảm giá toàn shop:");
        for (Discount d : globals) {
            sb.append("\n• ").append(describeGlobal(d));
        }
        sb.append("\nBạn hãy trải nghiệm và mua sắm ngay nhé! 🛍️");
        return sb.toString();
    }

    /** Mô tả 1 mã GLOBAL: "PROMO10 giảm 10% toàn bộ sản phẩm (đơn từ 200.000đ)". */
    private String describeGlobal(Discount d) {
        String value = d.getDiscountType() == Discount.DiscountType.PERCENTAGE
                ? ("giảm " + d.getValue().stripTrailingZeros().toPlainString() + "%")
                : ("giảm " + d.getValue().stripTrailingZeros().toPlainString() + "đ");
        StringBuilder s = new StringBuilder(d.getCode()).append(" ").append(value)
                .append(" cho toàn bộ sản phẩm");
        BigDecimal minOrder = d.getMinOrderValue();
        if (minOrder != null && minOrder.signum() > 0) {
            s.append(" (đơn từ ").append(minOrder.stripTrailingZeros().toPlainString()).append("đ)");
        }
        return s.toString();
    }

    public AIChat.BookingQuery handle(AIChat.BookingQuery query, String userInput, String sessionId) {
        if (query.data == null) query.data = new HashMap<>();

        UUID userId = catalogCache.resolveCurrentUserId(sessionId);
        List<ProductResponseDTO> products = catalogCache.getProductsForAiAssistantCached(userId);
        String action = (String) query.data.get("action");
        String productName = (String) query.data.get("productName");

        // Scope filters cho query giảm giá (brand/loại): "Nike đang sale gì", "giày nào đang giảm".
        String saleBrand = (String) query.data.get("brand");
        String saleProductType = (String) query.data.get("productType");
        String saleCategoryKeyword = (String) query.data.get("categoryKeyword");
        String scopeLabel = buildSaleScopeLabel(saleBrand, saleProductType, saleCategoryKeyword);

        ProductResponseDTO foundProduct = null;

        if ("list_on_sale".equals(action)) {
            List<ProductResponseDTO> onSaleProducts = applySaleScope(
                    products.stream()
                            .filter(p -> p.getSalePercent() != null && p.getSalePercent() > 0)
                            .collect(Collectors.toList()),
                    saleBrand, saleProductType, saleCategoryKeyword);

            String globalAnnounce = globalCampaignAnnouncement();
            if (onSaleProducts.isEmpty()) {
                // Không có sale theo SP, nhưng nếu đang có mã GLOBAL toàn shop thì báo mã đó.
                if (globalAnnounce != null) {
                    query.message = globalAnnounce;
                } else {
                    query.message = scopeLabel == null
                            ? "Hiện tại shop chưa có sản phẩm nào đang giảm giá."
                            : String.format("Hiện %s chưa có sản phẩm nào đang giảm giá.", scopeLabel);
                }
            } else {
                onSaleProducts.sort(Comparator.comparing(ProductResponseDTO::getSalePercent).reversed());
                query.message = scopeLabel == null
                        ? String.format("Hiện tại shop có %d sản phẩm đang giảm giá. Tôi đã gửi danh sách cho bạn 👇", onSaleProducts.size())
                        : String.format("%s hiện có %d sản phẩm đang giảm giá. Tôi đã gửi danh sách cho bạn 👇", AiTextUtil.capitalize(scopeLabel), onSaleProducts.size());
                query.data.put("products", onSaleProducts);
                if (globalAnnounce != null) query.message += "\n\n" + globalAnnounce;
            }
            logProductQuery(userId, sessionId, action, productName, query.message, null);
            return query;
        }

        if ("count_on_sale".equals(action)) {
            long count = applySaleScope(
                    products.stream()
                            .filter(p -> p.getSalePercent() != null && p.getSalePercent() > 0)
                            .collect(Collectors.toList()),
                    saleBrand, saleProductType, saleCategoryKeyword).size();
            String globalAnnounceCount = globalCampaignAnnouncement();
            if (count == 0 && globalAnnounceCount != null) {
                query.message = globalAnnounceCount;
            } else {
                query.message = scopeLabel == null
                        ? "Hiện tại shop có " + count + " sản phẩm đang giảm giá."
                        : String.format("Hiện %s có %d sản phẩm đang giảm giá.", scopeLabel, count);
                if (globalAnnounceCount != null) query.message += "\n\n" + globalAnnounceCount;
            }
            logProductQuery(userId, sessionId, action, productName, query.message, null);
            return query;
        }

        if ("check_on_sale".equals(action) || "check_sales".equals(action) ||
                "check_sales_context".equals(action) || "product_detail".equals(action) ||
                "check_size".equals(action) || "prepare_order".equals(action)) {

            ProductResponseDTO p = null;

            if (productName != null && !productName.isEmpty()) {
                p = productService.getProductByName(productName);
            }

            if (p == null && sessionId != null) {
                p = sessionContextStore.getLastProduct(sessionId);
            }

            if (p != null) {
                foundProduct = p;

                if ("check_on_sale".equals(action)) {
                    if (p.getSalePercent() != null && p.getSalePercent() > 0) {
                        query.message = String.format("Sản phẩm '%s' đang giảm %d%%, giá chỉ còn %s VNĐ.",
                                p.getName(), p.getSalePercent(), AiTextUtil.formatMoney(p.getSalePrice()));
                    } else {
                        // Không có sale riêng cho SP này, nhưng có thể có mã GLOBAL áp cho toàn shop.
                        String globalAnnounceP = globalCampaignAnnouncement();
                        query.message = (globalAnnounceP != null)
                                ? String.format("Sản phẩm '%s' chưa có giảm giá riêng. %s", p.getName(), globalAnnounceP)
                                : String.format("Sản phẩm '%s' hiện KHÔNG có chương trình giảm giá.", p.getName());
                    }
                }
                else if ("check_sales".equals(action) || "check_sales_context".equals(action)) {
                    int totalSold = (p.getTotalSold() != null) ? p.getTotalSold() : 0;
                    String comment = totalSold > 0 ? "Đang được quan tâm." : "Chưa có lượt bán.";
                    query.message = String.format("Sản phẩm '%s' đã bán được tổng cộng %d chiếc. %s", p.getName(), totalSold, comment);
                }
                else if ("product_detail".equals(action)) {
                    String lowerInput = userInput.toLowerCase();
                    boolean isAskingForImage = lowerInput.contains("ảnh") || lowerInput.contains("hình") || lowerInput.contains("photo") || lowerInput.contains("pic");

                    if (isAskingForImage) {
                        if (p.getImageUrl() != null && !p.getImageUrl().isEmpty()) {
                            query.message = String.format("Đây là hình ảnh thực tế của %s. Bạn xem bên dưới nhé 👇", p.getName());
                        } else {
                            query.message = String.format("Sản phẩm %s hiện chưa cập nhật hình ảnh.", p.getName());
                        }
                    } else {
                        StringBuilder detailMsg = new StringBuilder();
                        detailMsg.append(String.format("- Chi tiết: %s\n", p.getName()));
                        detailMsg.append(String.format("- Giá: %s VNĐ\n", AiTextUtil.formatMoney(p.getPrice())));
                        if (p.getSalePercent() != null && p.getSalePercent() > 0) {
                            detailMsg.append(String.format("- Giảm còn: %s VNĐ\n", AiTextUtil.formatMoney(p.getSalePrice())));
                        }
                        detailMsg.append(String.format("- Thương hiệu: %s\n", p.getBrand()));
                        detailMsg.append("- Mô tả: " + (p.getDescription() != null ? p.getDescription() : "Đang cập nhật"));
                        query.message = detailMsg.toString();
                    }
                }
                else if ("check_size".equals(action)) {
                    String sizeToCheck = (String) query.data.get("size");

                    if (sizeToCheck == null || sizeToCheck.isEmpty()) {
                        if (p.getVariants() == null || p.getVariants().isEmpty()) {
                            query.message = String.format("Sản phẩm '%s' hiện chưa cập nhật thông tin size.", p.getName());
                        } else {
                            String sizes = availableSizesString(p);
                            if (sizes == null) {
                                query.message = String.format("Tiếc quá, sản phẩm '%s' hiện đã hết sạch hàng các size rồi ạ.", p.getName());
                            } else {
                                query.message = String.format("Dạ mẫu '%s' hiện còn các size: %s. Bạn chốt size nào để mình lên đơn nhé?", p.getName(), sizes);
                            }
                        }
                    }
                    else {
                        ProductResponseDTO.VariantDTO variant = findVariantBySize(p, sizeToCheck);
                        int quantity = (variant != null && variant.getQuantity() != null) ? variant.getQuantity() : 0;
                        if (quantity > 0) {
                            if (sessionId != null) sessionContextStore.setLastSize(sessionId, sizeToCheck);
                            boolean hasOrderIntent = userInput != null && (
                                    userInput.toLowerCase().contains("đặt") ||
                                            userInput.toLowerCase().contains("mua") ||
                                            userInput.toLowerCase().contains("lấy") ||
                                            userInput.toLowerCase().contains("order")
                            );
                            if (hasOrderIntent) {
                                // Chuyển thẳng sang prepare_order flow
                                int orderQty = extractQuantityFromInput(userInput, query.data.get("quantity"));
                                if (orderQty > quantity) {
                                    query.message = String.format("Sản phẩm '%s' size %s chỉ còn %d đôi (bạn muốn đặt %d). Bạn giảm số lượng hoặc đổi size nhé.",
                                            p.getName(), sizeToCheck, quantity, orderQty);
                                } else {
                                    query.message = String.format("Xác nhận: Bạn muốn đặt %d đôi %s - Size %s. Nhấn nút bên dưới để thanh toán nhé! 👇", orderQty, p.getName(), sizeToCheck);
                                    query.data.put("selectedSize", sizeToCheck);
                                    query.data.put("selectedQuantity", orderQty);
                                    query.data.put("action", "ready_to_order");
                                }
                            } else {
                                query.message = String.format("Sản phẩm '%s' size %s hiện đang còn hàng (SL: %d).", p.getName(), sizeToCheck, quantity);
                            }
                        } else {
                            String sizes = availableSizesString(p);
                            query.message = sizes != null
                                    ? String.format("Tiếc quá, sản phẩm '%s' size %s hiện đang hết hàng. Shop còn các size: %s.",
                                            p.getName(), sizeToCheck, sizes)
                                    : String.format("Tiếc quá, sản phẩm '%s' hiện đã hết hàng tất cả các size.", p.getName());
                        }
                    }
                }
                else if ("prepare_order".equals(action)) {
                    // Gom các dòng (size, quantity) cần đặt. Ưu tiên mảng "orderItems" (đặt
                    // nhiều size trong 1 tin nhắn); fallback "size" + "quantity" đơn lẻ (giữ
                    // flow cũ + lastSize từ session).
                    List<Map<String, Object>> lines = new ArrayList<>();
                    Object rawItems = query.data.get("orderItems");
                    if (rawItems instanceof List<?> rawList) {
                        for (Object o : rawList) {
                            if (!(o instanceof Map<?, ?> m)) continue;
                            Object sz = m.get("size");
                            if (sz == null) continue;
                            String size = sz.toString().trim();
                            if (size.isEmpty()) continue;
                            int qty = 1;
                            Object q = m.get("quantity");
                            if (q instanceof Number n) {
                                qty = n.intValue();
                            } else if (q != null) {
                                try { qty = Integer.parseInt(q.toString().trim()); } catch (NumberFormatException ignore) {}
                            }
                            Map<String, Object> line = new HashMap<>();
                            line.put("size", size);
                            line.put("quantity", Math.max(1, qty));
                            lines.add(line);
                        }
                    }
                    if (lines.isEmpty()) {
                        String sizeToOrder = (String) query.data.get("size");
                        if (sizeToOrder == null && sessionId != null) {
                            sizeToOrder = sessionContextStore.getLastSize(sessionId);
                        }
                        if (sizeToOrder != null) {
                            int quantity = extractQuantityFromInput(userInput, query.data.get("quantity"));
                            Map<String, Object> line = new HashMap<>();
                            line.put("size", sizeToOrder);
                            line.put("quantity", Math.max(1, quantity));
                            lines.add(line);
                        }
                    }

                    if (lines.isEmpty()) {
                        query.message = String.format("Bạn muốn đặt size nào cho sản phẩm '%s'? (VD: 'Lấy size 40').", p.getName());
                    } else {
                        // Check size + tồn kho TRƯỚC khi hiện card thanh toán — không để tới
                        // checkout mới nổ ở StockLockService (quantity DTO = availableQuantity, khớp lock check).
                        List<Map<String, Object>> okItems = new ArrayList<>();
                        List<String> problems = new ArrayList<>();
                        for (Map<String, Object> line : lines) {
                            String size = (String) line.get("size");
                            int qty = ((Number) line.get("quantity")).intValue();
                            ProductResponseDTO.VariantDTO variant = findVariantBySize(p, size);
                            int avail = (variant != null && variant.getQuantity() != null) ? variant.getQuantity() : 0;
                            if (avail <= 0) {
                                problems.add(String.format("size %s đã hết hàng", size));
                            } else if (avail < qty) {
                                problems.add(String.format("size %s chỉ còn %d đôi (bạn đặt %d)", size, avail, qty));
                            } else {
                                okItems.add(line);
                            }
                        }

                        if (!problems.isEmpty()) {
                            String sizes = availableSizesString(p);
                            String detail = String.join("; ", problems);
                            query.message = sizes != null
                                    ? String.format("Tiếc quá, sản phẩm '%s': %s. Shop còn các size: %s. Bạn đổi size hoặc giảm số lượng để mình lên đơn nhé?",
                                            p.getName(), detail, sizes)
                                    : String.format("Tiếc quá, sản phẩm '%s' hiện đã hết hàng tất cả các size.", p.getName());
                        } else {
                            // Tất cả dòng đều đủ tồn → chốt đơn (nhiều dòng = nhiều line item cùng 1 sp).
                            StringBuilder summary = new StringBuilder();
                            for (int li = 0; li < okItems.size(); li++) {
                                if (li > 0) summary.append(", ");
                                summary.append(String.format("%d đôi size %s",
                                        ((Number) okItems.get(li).get("quantity")).intValue(),
                                        okItems.get(li).get("size")));
                            }
                            query.message = String.format("Xác nhận: Bạn muốn đặt %s - %s. Nhấn nút bên dưới để thanh toán nhé! 👇",
                                    summary, p.getName());
                            query.data.put("selectedItems", okItems);
                            // Back-compat: reader cũ chỉ đọc 1 size.
                            query.data.put("selectedSize", okItems.get(0).get("size"));
                            query.data.put("selectedQuantity", okItems.get(0).get("quantity"));
                            query.data.put("action", "ready_to_order");
                            if (sessionId != null) {
                                sessionContextStore.setLastSize(sessionId, (String) okItems.get(0).get("size"));
                            }
                        }
                    }
                }

            } else {
                query.message = "Xin lỗi, tôi không biết bạn đang hỏi về sản phẩm nào. Vui lòng nói tên sản phẩm cụ thể.";
            }
        }

        else if ("check_stock".equals(action) && productName != null) {
            foundProduct = products.stream()
                    .filter(p -> p.getName().toLowerCase().contains(productName.toLowerCase()))
                    .findFirst().orElse(null);
            if (foundProduct != null) {
                query.message = "Sản phẩm " + foundProduct.getName() + " hiện đang có hàng.";
            } else {
                query.message = "Sản phẩm " + productName + " hiện không tìm thấy.";
            }
        }
        else if ("cheapest_product".equals(action)) {
            // Lấy từ khóa category từ AI (nếu có)
            String categoryKeyword = (String) query.data.get("categoryKeyword");

            // Lọc danh sách nếu có từ khóa
            List<ProductResponseDTO> targetProducts = filterProductsByCategoryOrName(products, categoryKeyword);

            foundProduct = targetProducts.stream()
                    .min(Comparator.comparing(ProductResponseDTO::getPrice))
                    .orElse(null);

            if (foundProduct != null) {
                String displayCat = categoryKeyword;
                if ("Shoes".equalsIgnoreCase(categoryKeyword)) displayCat = "giày";
                else if ("Clothing".equalsIgnoreCase(categoryKeyword)) displayCat = "quần áo";

                String contextMsg = (categoryKeyword != null) ? "thuộc nhóm " + displayCat : "trong cửa hàng";

                query.message = String.format("Sản phẩm %s rẻ nhất là %s với giá %s VNĐ.",
                        contextMsg, foundProduct.getName(), AiTextUtil.formatMoney(foundProduct.getPrice()));
            } else {
                query.message = (categoryKeyword != null)
                        ? "Không tìm thấy sản phẩm nào thuộc nhóm '" + categoryKeyword + "'."
                        : "Hiện cửa hàng chưa có sản phẩm nào.";
            }
        }
        else if ("most_expensive_product".equals(action)) {
            String categoryKeyword = (String) query.data.get("categoryKeyword");

            List<ProductResponseDTO> targetProducts = filterProductsByCategoryOrName(products, categoryKeyword);

            foundProduct = targetProducts.stream()
                    .max(Comparator.comparing(ProductResponseDTO::getPrice))
                    .orElse(null);

            if (foundProduct != null) {
                String displayCat = categoryKeyword;
                if ("Shoes".equalsIgnoreCase(categoryKeyword)) displayCat = "giày";
                else if ("Clothing".equalsIgnoreCase(categoryKeyword)) displayCat = "quần áo";

                String contextMsg = (categoryKeyword != null) ? "thuộc nhóm " + displayCat : "trong cửa hàng";

                query.message = String.format("Sản phẩm %s đắt nhất là %s với giá %s VNĐ.",
                        contextMsg, foundProduct.getName(), AiTextUtil.formatMoney(foundProduct.getPrice()));
            } else {
                query.message = (categoryKeyword != null)
                        ? "Không tìm thấy sản phẩm nào thuộc nhóm '" + categoryKeyword + "'."
                        : "Hiện cửa hàng chưa có sản phẩm nào.";
            }
        }
        else if ("best_selling_product".equals(action)) {
            List<ProductResponseDTO> top = productService.getTopSellingProducts(1, userId);
            if (!top.isEmpty()) {
                foundProduct = top.get(0);
                query.message = String.format("Sản phẩm bán chạy nhất là %s.", foundProduct.getName());
            } else {
                query.message = "Chưa có dữ liệu về sản phẩm bán chạy.";
            }
        }
        else if ("max_discount_product".equals(action)) {
            foundProduct = applySaleScope(
                    products.stream()
                            .filter(p -> p.getSalePercent() != null && p.getSalePercent() > 0)
                            .collect(Collectors.toList()),
                    saleBrand, saleProductType, saleCategoryKeyword).stream()
                    .max(Comparator.comparing(ProductResponseDTO::getSalePercent))
                    .orElse(null);
            if (foundProduct != null) {
                query.message = scopeLabel == null
                        ? String.format("Sản phẩm giảm sâu nhất là %s (-%d%%).", foundProduct.getName(), foundProduct.getSalePercent())
                        : String.format("%s giảm sâu nhất là %s (-%d%%).", AiTextUtil.capitalize(scopeLabel), foundProduct.getName(), foundProduct.getSalePercent());
            } else {
                query.message = scopeLabel == null
                        ? "Hiện không có sản phẩm nào giảm giá."
                        : String.format("Hiện %s không có sản phẩm nào giảm giá.", scopeLabel);
            }
        }
        else if ("max_discount_brand".equals(action) || "max_discount_category".equals(action)) {
            boolean byBrand = "max_discount_brand".equals(action);
            List<ProductResponseDTO> onSale = products.stream()
                    .filter(p -> p.getSalePercent() != null && p.getSalePercent() > 0)
                    .collect(Collectors.toList());
            DiscountGroup top = topDiscountGroup(onSale,
                    byBrand ? ProductResponseDTO::getBrand : ProductResponseDTO::getCategoryName);
            if (top == null) {
                query.message = byBrand
                        ? "Hiện chưa có thương hiệu nào đang giảm giá."
                        : "Hiện chưa có danh mục nào đang giảm giá.";
            } else {
                query.message = String.format(
                        "%s giảm giá mạnh nhất là %s (sâu nhất -%d%%, %d sản phẩm đang sale). Tôi gửi vài mẫu giảm sâu 👇",
                        byBrand ? "Thương hiệu" : "Danh mục", top.key, top.maxPct, top.count);
                query.data.put("products", top.products);
            }
            logProductQuery(userId, sessionId, action, productName, query.message, null);
            return query;
        }

        else if ("search_by_price_range".equals(action)) {
            Object minPriceObj = query.data.get("minPrice");
            Object maxPriceObj = query.data.get("maxPrice");
            String categoryKeyword = (String) query.data.get("categoryKeyword");

            Double minPrice = 0.0;
            Double maxPrice = Double.MAX_VALUE;

            if (minPriceObj != null) {
                if (minPriceObj instanceof Number) {
                    minPrice = ((Number) minPriceObj).doubleValue();
                }
            }

            if (maxPriceObj != null) {
                if (maxPriceObj instanceof Number) {
                    maxPrice = ((Number) maxPriceObj).doubleValue();
                }
            }

            List<ProductResponseDTO> targetProducts = products;
            if (categoryKeyword != null && !categoryKeyword.isEmpty()) {
                targetProducts = filterProductsByCategoryOrName(products, categoryKeyword);
            }

            final Double finalMinPrice = minPrice;
            final Double finalMaxPrice = maxPrice;

            List<ProductResponseDTO> filteredProducts = targetProducts.stream()
                    .filter(p -> {
                        double effectivePrice = p.getPrice();
                        if (p.getSalePercent() != null && p.getSalePercent() > 0 && p.getSalePrice() != null) {
                            effectivePrice = p.getSalePrice();
                        }
                        return effectivePrice >= finalMinPrice && effectivePrice <= finalMaxPrice;
                    })
                    .sorted(Comparator.comparing(p -> {
                        if (p.getSalePercent() != null && p.getSalePercent() > 0 && p.getSalePrice() != null) {
                            return p.getSalePrice();
                        }
                        return p.getPrice();
                    }))
                    .collect(Collectors.toList());

            if (filteredProducts.isEmpty()) {
                String categoryMsg = (categoryKeyword != null && !categoryKeyword.isEmpty())
                        ? " thuộc nhóm " + AiTextUtil.translateCategory(categoryKeyword)
                        : "";

                String priceMsg = AiTextUtil.buildPriceRangeMessage(minPrice, maxPrice);

                query.message = String.format(
                        "Không tìm thấy sản phẩm%s trong khoảng giá %s.",
                        categoryMsg,
                        priceMsg
                );

                query.data.put("products", new ArrayList<>());
            } else {
                String categoryMsg = (categoryKeyword != null && !categoryKeyword.isEmpty())
                        ? " " + AiTextUtil.translateCategory(categoryKeyword)
                        : "";

                String priceMsg = AiTextUtil.buildPriceRangeMessage(minPrice, maxPrice);

                query.message = String.format(
                        "Tìm thấy %d sản phẩm%s trong khoảng giá %s 👇",
                        filteredProducts.size(),
                        categoryMsg,
                        priceMsg
                );

                query.data.put("products", filteredProducts);
                query.data.put("priceRange", Map.of(
                        "min", minPrice,
                        "max", maxPrice
                ));

                query.data.put("showImage", true);
            }
        }

        if (foundProduct != null) {
            sessionContextStore.setLastProduct(sessionId, foundProduct);

            query.data.put("product", foundProduct);

            boolean shouldShowImage = false;

            if ("product_detail".equals(action) ||
                    "image_search_result".equals(action) ||
                    "prepare_order".equals(action)) {

                shouldShowImage = true;
            }

            query.data.put("showImage", shouldShowImage);
        }

        logProductQuery(userId, sessionId, action, productName, query.message, foundProduct);

        return query;
    }

    private void logProductQuery(UUID userId, String sessionId, String action, String productName, String aiMessage, ProductResponseDTO foundProduct) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("product_action", action);
            metadata.put("product_name_query", productName);
            metadata.put("aiResponseText", aiMessage);
            metadata.put("modelVersion", MODEL_VERSION);

            if (foundProduct != null) {
                metadata.put("found_product_id", foundProduct.getId());
                metadata.put("found_product_name", foundProduct.getName());
                metadata.put("retrievedItemIds", List.of(foundProduct.getId()));
                metadata.put("item_price_snapshot", foundProduct.getPrice());
                metadata.put("item_category", foundProduct.getCategoryName());
            }

            logPublisherService.publishEvent(
                    userId != null ? userId.toString() : null,
                    sessionId,
                    "CHAT_PRODUCT_QUERY",
                    foundProduct != null ? foundProduct.getId().toString() : null,
                    foundProduct != null ? "PRODUCT" : null,
                    metadata, "AI_Chatbot"
            );
        } catch (Exception e) {
            System.err.println("Không thể ghi log CHAT_PRODUCT_QUERY: " + e.getMessage());
        }
    }

    /**
     * Lọc danh sách SP đang sale theo scope brand + loại (productType ưu tiên, fallback categoryKeyword).
     * Trả list mutable (sort/cap được). null/blank scope → bỏ qua tiêu chí đó.
     */
    private List<ProductResponseDTO> applySaleScope(List<ProductResponseDTO> onSale,
                                                    String brand, String productType, String categoryKeyword) {
        List<ProductResponseDTO> out = new ArrayList<>(onSale);
        if (brand != null && !brand.isBlank()) {
            String b = brand.trim();
            out = out.stream()
                    .filter(p -> p.getBrand() != null && p.getBrand().equalsIgnoreCase(b))
                    .collect(Collectors.toList());
        }
        if (productType != null && !productType.isBlank()) {
            out = out.stream()
                    .filter(p -> categoryService.productMatchesType(p, productType))
                    .collect(Collectors.toList());
        } else if (categoryKeyword != null && !categoryKeyword.isBlank()) {
            out = new ArrayList<>(filterProductsByCategoryOrName(out, categoryKeyword));
        }
        return out;
    }

    /** Nhãn scope giảm giá để chèn vào message (vd "thương hiệu Nike", "giày"). null nếu không có scope. */
    private String buildSaleScopeLabel(String brand, String productType, String categoryKeyword) {
        List<String> parts = new ArrayList<>();
        if (productType != null && !productType.isBlank()) {
            String label = AiTextUtil.productTypeLabel(productType);
            if (label != null) parts.add(label);
        } else if (categoryKeyword != null && !categoryKeyword.isBlank()) {
            parts.add(AiTextUtil.translateCategory(categoryKeyword));
        }
        if (brand != null && !brand.isBlank()) {
            parts.add("thương hiệu " + brand.trim());
        }
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    /** Nhóm SP sale theo key (brand/category) đã rank: maxPct desc, tiebreak count desc. */
    private static class DiscountGroup {
        final String key;
        final int maxPct;
        final int count;
        final List<ProductResponseDTO> products; // sale của nhóm, sort salePercent desc, cap 10
        DiscountGroup(String key, int maxPct, int count, List<ProductResponseDTO> products) {
            this.key = key; this.maxPct = maxPct; this.count = count; this.products = products;
        }
    }

    /**
     * Group SP đang sale theo keyFn (bỏ null/blank), chọn nhóm "giảm nhiều nhất":
     * rank theo độ sâu (maxPct) desc, tiebreak số lượng SP sale (count) desc — cách Shopee/Amazon làm.
     * Trả null nếu không nhóm nào có SP sale.
     */
    private DiscountGroup topDiscountGroup(List<ProductResponseDTO> onSale,
                                           java.util.function.Function<ProductResponseDTO, String> keyFn) {
        Map<String, List<ProductResponseDTO>> grouped = new LinkedHashMap<>();
        for (ProductResponseDTO p : onSale) {
            String key = keyFn.apply(p);
            if (key == null || key.isBlank()) continue;
            grouped.computeIfAbsent(key.trim(), k -> new ArrayList<>()).add(p);
        }
        DiscountGroup best = null;
        for (Map.Entry<String, List<ProductResponseDTO>> e : grouped.entrySet()) {
            List<ProductResponseDTO> items = e.getValue();
            int maxPct = items.stream().mapToInt(ProductResponseDTO::getSalePercent).max().orElse(0);
            int count = items.size();
            if (best == null || maxPct > best.maxPct || (maxPct == best.maxPct && count > best.count)) {
                List<ProductResponseDTO> sorted = items.stream()
                        .sorted(Comparator.comparing(ProductResponseDTO::getSalePercent).reversed())
                        .limit(10)
                        .collect(Collectors.toList());
                best = new DiscountGroup(e.getKey(), maxPct, count, sorted);
            }
        }
        return best;
    }

    private List<ProductResponseDTO> filterProductsByCategoryOrName(List<ProductResponseDTO> products, String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return products;
        }

        String finalKeyword = keyword.toLowerCase().trim();

        return products.stream()
                .filter(p -> isProductMatchingKeyword(p, finalKeyword))
                .collect(Collectors.toList());
    }

    private boolean isProductMatchingKeyword(ProductResponseDTO p, String keyword) {
        String pName = (p.getName() != null) ? p.getName().toLowerCase() : "";
        String pCat = (p.getCategoryName() != null) ? p.getCategoryName().toLowerCase() : "";

        String pTags = "";
        if (p.getTags() != null && !p.getTags().isEmpty()) {
            pTags = p.getTags().stream()
                    .map(String::toLowerCase)
                    .collect(Collectors.joining(" "));
        }

        if (pName.contains(keyword) || pCat.contains(keyword)) {
            return true;
        }

        if (keyword.equals("accessories") || keyword.contains("phụ kiện")) {
            if (pCat.contains("clothing") || pCat.contains("shirt") || pCat.contains("pant") ||
                    pCat.contains("jacket") || pCat.contains("hoodie") || pCat.contains("dress") ||
                    pCat.contains("shoes") || pCat.contains("footwear") || pCat.contains("sneaker")) {
                if (!pCat.contains("sock")) {
                    return false;
                }
            }
            if (pCat.contains("accessories") || pCat.contains("phụ kiện")) return true;

            boolean isBag = pName.contains("bag") || pName.contains("túi") || pTags.contains("túi");
            boolean isHat = pName.contains("hat") || pName.contains("nón") || pName.contains("mũ") || pTags.contains("mũ");
            boolean isSock = pName.contains("sock") || pName.contains("tất") || pName.contains("vớ");
            boolean isGlove = pName.contains("glove") || pName.contains("găng");

            return isBag || isHat || isSock || isGlove;
        }

        if (keyword.equals("bags and backpacks") || keyword.contains("bag") || keyword.contains("túi")) {
            return pName.contains("bag") || pName.contains("túi") || pName.contains("balo") ||
                    pName.contains("backpack") ||
                    pCat.contains("bag") || pCat.contains("túi") ||
                    pTags.contains("túi") || pTags.contains("balo");
        }

        if (keyword.equals("shoes") || keyword.equals("footwear") || keyword.contains("giày")) {
            return pName.contains("shoe") || pName.contains("giày") || pName.contains("sneaker") ||
                    pCat.contains("shoe") || pCat.contains("footwear");
        }

        if (keyword.equals("clothing") || keyword.contains("quần áo") || keyword.contains("đồ")) {
            return pName.contains("shirt") || pName.contains("áo") ||
                    pName.contains("pant") || pName.contains("quần") ||
                    pName.contains("short") || pName.contains("dress") ||
                    pCat.contains("clothing") || pCat.contains("wear");
        }

        return pTags.contains(keyword);
    }

    private int extractQuantityFromInput(String userInput, Object rawQty) {
        // Ưu tiên Gemini parse, nếu không có thì dùng regex trên userInput
        if (rawQty instanceof Number) {
            int q = ((Number) rawQty).intValue();
            if (q > 1) return q;
        }
        if (userInput != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d+)\\s*(đôi|cái|chiếc|cặp|pair|x|×)")
                    .matcher(userInput.toLowerCase());
            if (m.find()) return Math.max(1, Integer.parseInt(m.group(1)));
        }
        return 1;
    }

    private static ProductResponseDTO.VariantDTO findVariantBySize(ProductResponseDTO p, String size) {
        if (p.getVariants() == null || size == null) return null;
        for (ProductResponseDTO.VariantDTO v : p.getVariants()) {
            if (size.equalsIgnoreCase(v.getSize())) return v;
        }
        return null;
    }

    /** "39 (còn 5), 40 (còn 2)" — chỉ size còn hàng (quantity DTO = availableQuantity, đã trừ locked); null nếu hết sạch/chưa có variant. */
    private static String availableSizesString(ProductResponseDTO p) {
        if (p.getVariants() == null || p.getVariants().isEmpty()) return null;
        List<String> list = new ArrayList<>();
        for (ProductResponseDTO.VariantDTO v : p.getVariants()) {
            if (v.getQuantity() != null && v.getQuantity() > 0) {
                list.add(String.format("%s (còn %d)", v.getSize(), v.getQuantity()));
            }
        }
        return list.isEmpty() ? null : String.join(", ", list);
    }
}
