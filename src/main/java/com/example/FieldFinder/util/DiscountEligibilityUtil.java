package com.example.FieldFinder.util;

import com.example.FieldFinder.entity.Category;
import com.example.FieldFinder.entity.Discount;
import com.example.FieldFinder.entity.OrderItem;
import com.example.FieldFinder.entity.Product;

import java.math.BigDecimal;
import java.time.LocalDate;

public final class DiscountEligibilityUtil {

    private DiscountEligibilityUtil() {
    }

    public static boolean isUsable(Discount discount, LocalDate now) {
        if (discount == null) return false;
        boolean isActive = discount.getStatus() == Discount.DiscountStatus.ACTIVE;
        boolean isStarted = discount.getStartDate() == null || !now.isBefore(discount.getStartDate());
        boolean isNotExpired = discount.getEndDate() == null || !now.isAfter(discount.getEndDate());
        boolean isStockAvailable = discount.getQuantity() > 0;
        return isActive && isStarted && isNotExpired && isStockAvailable;
    }

    public static boolean meetsMinimum(Discount discount, double amount) {
        if (discount == null || discount.getMinOrderValue() == null) return true;
        BigDecimal minimum = discount.getMinOrderValue();
        return minimum.compareTo(BigDecimal.ZERO) <= 0
                || BigDecimal.valueOf(amount).compareTo(minimum) >= 0;
    }

    public static boolean isApplicableToProduct(Discount discount, Product product) {
        if (discount == null || product == null || discount.getScope() == null) return false;

        if (discount.getScope() == Discount.DiscountScope.GLOBAL) {
            return true;
        }

        if (discount.getScope() == Discount.DiscountScope.SPECIFIC_PRODUCT) {
            return discount.getApplicableProducts() != null
                    && discount.getApplicableProducts().stream()
                    .anyMatch(p -> p.getProductId().equals(product.getProductId()));
        }

        if (discount.getScope() == Discount.DiscountScope.CATEGORY) {
            if (discount.getApplicableCategories() == null
                    || discount.getApplicableCategories().isEmpty()) return false;

            // Walk parent chain — match nếu BẤT KỲ ancestor nào của product
            // có ID trùng với một category trong applicableCategories.
            // Phải nhất quán với findApplicableDiscountsForProduct (cùng dùng parent chain).
            java.util.Set<Long> applicableIds = discount.getApplicableCategories().stream()
                    .map(Category::getCategoryId)
                    .collect(java.util.stream.Collectors.toSet());

            Category cur = product.getCategory();
            while (cur != null) {
                if (applicableIds.contains(cur.getCategoryId())) return true;
                cur = cur.getParent();
            }
            return false;
        }

        return false;
    }

    public static boolean isEligibleForProductPreview(Discount discount, Product product) {
        return isUsable(discount, LocalDate.now())
                && isApplicableToProduct(discount, product)
                && meetsMinimum(discount, product.getPrice() != null ? product.getPrice() : 0.0);
    }

    public static boolean isEligibleForOrderItem(Discount discount, OrderItem item) {
        if (item == null || item.getProduct() == null) return false;
        double lineSubtotal = item.getPrice();
        return isUsable(discount, LocalDate.now())
                && isApplicableToProduct(discount, item.getProduct())
                && meetsMinimum(discount, lineSubtotal);
    }
}