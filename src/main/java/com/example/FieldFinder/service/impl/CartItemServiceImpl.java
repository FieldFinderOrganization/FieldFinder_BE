package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.CartStatus;
import com.example.FieldFinder.dto.req.CartItemRequestDTO;
import com.example.FieldFinder.dto.res.CartItemResponseDTO;
import com.example.FieldFinder.entity.*;
import com.example.FieldFinder.repository.*;
import com.example.FieldFinder.service.CartItemService;
import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CartItemServiceImpl implements CartItemService {

    private final CartItemRepository cartItemRepository;
    private final CartRepository cartRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final UserRepository userRepository;
    private final DiscountRepository discountRepository;
    private final UserDiscountRepository userDiscountRepository;

    @Data
    @AllArgsConstructor
    private static class PriceCalculationResult {
        private Double finalPrice;
        private List<Discount> appliedDiscounts;
    }

    private PriceCalculationResult calculatePriceAndDiscounts(Product product, UUID userId) {
        if (product == null) return new PriceCalculationResult(0.0, new ArrayList<>());

        List<Long> categoryIds = new ArrayList<>();
        Category current = product.getCategory();
        while (current != null) {
            categoryIds.add(current.getCategoryId());
            current = current.getParent();
        }

        List<Discount> implicitDiscounts = discountRepository.findApplicableDiscountsForProduct(
                product.getProductId(),
                categoryIds
        );

        List<ProductDiscount> combinedDiscounts = new ArrayList<>();
        if (product.getDiscounts() != null) {
            combinedDiscounts.addAll(product.getDiscounts());
        }

        Set<UUID> existingDiscountIds = combinedDiscounts.stream()
                .map(pd -> pd.getDiscount().getDiscountId())
                .collect(Collectors.toSet());

        Product tempCalcProduct = Product.builder()
                .price(product.getPrice())
                .discounts(combinedDiscounts)
                .build();

        for (Discount d : implicitDiscounts) {
            if (!existingDiscountIds.contains(d.getDiscountId())) {
                ProductDiscount dummyPD = ProductDiscount.builder()
                        .product(tempCalcProduct)
                        .discount(d)
                        .build();
                tempCalcProduct.getDiscounts().add(dummyPD);
            }
        }

        List<Discount> validDiscounts = new ArrayList<>();
        java.time.LocalDate now = java.time.LocalDate.now();

        List<UUID> usedDiscountIds = new ArrayList<>();
        if (userId != null) {
            try {
                // KHÔNG CẦN DÒNG NÀY NỮA: User userRef = userRepository.getReferenceById(userId);

                // Gọi hàm mới sửa, truyền thẳng userId vào
                usedDiscountIds = userDiscountRepository.findUsedDiscountIdsByUserId(userId);

                // Log ra để kiểm tra ngay xem nó có lấy được gì không
                System.out.println("User ID: " + userId + " - Used Discounts Count: " + usedDiscountIds.size());

            } catch (Exception e) {
                System.err.println("Error fetching used discounts: " + e.getMessage());
                e.printStackTrace(); // In lỗi đầy đủ để debug
            }
        }

        for (ProductDiscount pd : tempCalcProduct.getDiscounts()) {
            Discount d = pd.getDiscount();
            if (d == null) continue;

            boolean isActive = d.getStatus() == Discount.DiscountStatus.ACTIVE;
            boolean isStarted = d.getStartDate() == null || !now.isBefore(d.getStartDate());
            boolean isNotExpired = d.getEndDate() == null || !now.isAfter(d.getEndDate());

            boolean isNotUsedByUser = !usedDiscountIds.contains(d.getDiscountId());

            if (isActive && isStarted && isNotExpired && isNotUsedByUser) {
                validDiscounts.add(d);
            }
        }

        double currentPrice = product.getPrice();

        for (Discount d : validDiscounts) {
            double reduction = 0;
            double value = d.getValue() != null ? d.getValue().doubleValue() : 0;

            if (d.getDiscountType() == Discount.DiscountType.FIXED_AMOUNT) {
                reduction = value;
            } else {
                // Percentage (Lũy tiến theo logic BE bạn đang làm)
                reduction = currentPrice * (value / 100.0);
            }

            if (d.getMaxDiscountAmount() != null) {
                double max = d.getMaxDiscountAmount().doubleValue();
                if (reduction > max) reduction = max;
            }

            currentPrice -= reduction;
        }

        return new PriceCalculationResult(Math.max(0, currentPrice), validDiscounts);
    }

    @Override
    @Transactional
    public CartItemResponseDTO addItemToCart(CartItemRequestDTO request) {
        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found!"));

        ProductVariant variant = productVariantRepository.findByProduct_ProductIdAndSize(
                product.getProductId(), request.getSize()
        ).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Size not available for this product!"));

        Cart cart = getOrCreateCart(request);

        UUID userId = cart.getUser() != null ? cart.getUser().getUserId() : null;

        Optional<Cart_item> existingItemOpt = cartItemRepository.findByCartAndProductAndSize(
                cart, product, request.getSize()
        );

        PriceCalculationResult calcResult = calculatePriceAndDiscounts(product, userId);

        Cart_item item;
        if (existingItemOpt.isPresent()) {
            item = existingItemOpt.get();
            int newQuantity = item.getQuantity() + request.getQuantity();

            if (newQuantity > variant.getAvailableQuantity()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Requested quantity exceeds available stock for size " + request.getSize());
            }
            item.setQuantity(newQuantity);

            item.setPriceAtTime(calcResult.getFinalPrice());
            item.setOriginalPrice(product.getPrice());
        } else {
            if (request.getQuantity() > variant.getAvailableQuantity()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Requested quantity exceeds available stock for size " + request.getSize());
            }

            item = Cart_item.builder()
                    .cart(cart)
                    .product(product)
                    .quantity(request.getQuantity())
                    .priceAtTime(calcResult.getFinalPrice())
                    .originalPrice(product.getPrice())
                    .size(request.getSize())
                    .build();
        }

        Cart_item saved = cartItemRepository.save(item);
        return mapToResponse(saved, calcResult);
    }

    @Override
    @Transactional
    public CartItemResponseDTO updateCartItem(Long cartItemId, int quantity) {
        Cart_item item = cartItemRepository.findById(cartItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cannot find cart item!"));


        Product product = item.getProduct();
        ProductVariant variant = productVariantRepository.findByProduct_ProductIdAndSize(
                product.getProductId(), item.getSize()
        ).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Size not found anymore!"));

        if (quantity <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Quantity must be greater than 0. Use DELETE endpoint to remove.");
        }

        if (quantity > variant.getAvailableQuantity()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The quantity exceeds available stock for size " + item.getSize());
        }

        UUID userId = null;
        if (item.getCart() != null && item.getCart().getUser() != null) {
            userId = item.getCart().getUser().getUserId();
        }

        PriceCalculationResult calcResult = calculatePriceAndDiscounts(product, userId);

        item.setQuantity(quantity);
        item.setPriceAtTime(calcResult.getFinalPrice());
        item.setOriginalPrice(product.getPrice());

        Cart_item updated = cartItemRepository.save(item);
        return mapToResponse(updated, calcResult);
    }

    @Override
    @Transactional
    public void removeCartItem(Long cartItemId) {
        Cart_item item = cartItemRepository.findById(cartItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cannot find cart item!"));
        cartItemRepository.delete(item);
    }

    @Override
    public List<CartItemResponseDTO> getItemsByCart(Long cartId) {
        Cart cart = cartRepository.findById(cartId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cannot find cart!"));

        UUID userId = cart.getUser() != null ? cart.getUser().getUserId() : null;

        return cartItemRepository.findByCart(cart).stream()
                .map(item -> {
                    PriceCalculationResult calc = calculatePriceAndDiscounts(item.getProduct(), userId);
                    return mapToResponse(item, calc);
                })
                .collect(Collectors.toList());
    }

    private Cart getOrCreateCart(CartItemRequestDTO request) {
        if (request.getCartId() != null) {
            Cart cart = cartRepository.findById(request.getCartId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cart not found!"));
            if (!CartStatus.ACTIVE.equals(cart.getStatus())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cart is already completed or abandoned!");
            }
            return cart;
        } else {
            if (request.getUserId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UserId is required for new cart!");
            }
            User user = userRepository.findByUserId(request.getUserId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found!"));
            Cart cart = new Cart();
            cart.setStatus(CartStatus.ACTIVE);
            cart.setUser(user);
            cart.setCreatedAt(LocalDateTime.now());
            return cartRepository.save(cart);
        }
    }

    private CartItemResponseDTO mapToResponse(Cart_item item, PriceCalculationResult calcResult) {
        List<CartItemResponseDTO.DiscountDTO> discountDTOs = new ArrayList<>();
        if (calcResult.getAppliedDiscounts() != null) {
            discountDTOs = calcResult.getAppliedDiscounts().stream()
                    .map(d -> CartItemResponseDTO.DiscountDTO.builder()
                            .id(d.getDiscountId())
                            .code(d.getCode())
                            .value(d.getValue().doubleValue())
                            .discountType(d.getDiscountType() != null ? d.getDiscountType().toString() : null)
                            .maxDiscountAmount(d.getMaxDiscountAmount() != null ? d.getMaxDiscountAmount().doubleValue() : null)
                            .build())
                    .collect(Collectors.toList());
        }

        return CartItemResponseDTO.builder()
                .id(item.getId())
                .cartId(item.getCart().getCartId())
                .productId(item.getProduct().getProductId())
                .productName(item.getProduct().getName())
                .imageUrl(item.getProduct().getImageUrl())
                .quantity(item.getQuantity())
                .size(item.getSize())

                .priceAtTime(calcResult != null ? calcResult.getFinalPrice() : item.getPriceAtTime())

                .originalPrice(item.getProduct().getPrice())

                .appliedDiscounts(discountDTOs)
                .build();
    }
}