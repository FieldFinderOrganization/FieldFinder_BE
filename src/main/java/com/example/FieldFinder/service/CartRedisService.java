package com.example.FieldFinder.service;

import com.example.FieldFinder.dto.req.CartItemRedisDTO;
import com.example.FieldFinder.dto.res.CartResponseDTO;
import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.example.FieldFinder.entity.ProductVariant;
import com.example.FieldFinder.repository.ProductVariantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class CartRedisService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ProductVariantRepository productVariantRepository;
    private final ProductService productService;

    private static final String CART_PREFIX = "cart:";
    private static final long CART_TTL_DAYS = 90;

    private String getCartKey(UUID userId) {
        return CART_PREFIX + userId.toString();
    }

    private String getItemHashKey(Long productId, String size) {
        return productId + "_" + size;
    }

    private String getCurrentFormattedTime() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
        return LocalDateTime.now().format(formatter);
    }

    public void addItemToCart(UUID userId, Long productId, String size, int quantity) {
        ProductVariant variant = productVariantRepository.findByProduct_ProductIdAndSize(productId, size)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sản phẩm hoặc size không tồn tại!"));

        String cartKey = getCartKey(userId);
        String hashKey = getItemHashKey(productId, size);
        HashOperations<String, String, CartItemRedisDTO> hashOps = redisTemplate.opsForHash();

        CartItemRedisDTO existingItem = hashOps.get(cartKey, hashKey);
        int finalQuantity = quantity;

        if (existingItem != null) {
            finalQuantity += existingItem.getQuantity();
        }

        if (finalQuantity > variant.getAvailableQuantity()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Số lượng yêu cầu vượt quá tồn kho (Còn: " + variant.getAvailableQuantity() + ")");
        }

        CartItemRedisDTO newItem = CartItemRedisDTO.builder()
                .productId(productId)
                .size(size)
                .quantity(finalQuantity)
                .addedAt(getCurrentFormattedTime())
                .build();

        hashOps.put(cartKey, hashKey, newItem);
        redisTemplate.expire(cartKey, CART_TTL_DAYS, TimeUnit.DAYS);
    }

    public void updateCartItem(UUID userId, Long productId, String size, int quantity) {
        if (quantity <= 0) {
            removeItemFromCart(userId, productId, size);
            return;
        }

        ProductVariant variant = productVariantRepository.findByProduct_ProductIdAndSize(productId, size)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sản phẩm hoặc size không tồn tại!"));

        if (quantity > variant.getAvailableQuantity()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Số lượng yêu cầu vượt quá tồn kho!");
        }

        String cartKey = getCartKey(userId);
        String hashKey = getItemHashKey(productId, size);
        HashOperations<String, String, CartItemRedisDTO> hashOps = redisTemplate.opsForHash();

        CartItemRedisDTO existingItem = hashOps.get(cartKey, hashKey);
        if (existingItem == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sản phẩm không có trong giỏ hàng!");
        }

        existingItem.setQuantity(quantity);
        hashOps.put(cartKey, hashKey, existingItem);

        redisTemplate.expire(cartKey, CART_TTL_DAYS, TimeUnit.DAYS);
    }

    public void removeItemFromCart(UUID userId, Long productId, String size) {
        String cartKey = getCartKey(userId);
        redisTemplate.opsForHash().delete(cartKey, getItemHashKey(productId, size));
        redisTemplate.expire(cartKey, CART_TTL_DAYS, TimeUnit.DAYS);
    }

    public CartResponseDTO getCart(UUID userId) {
        String cartKey = getCartKey(userId);
        HashOperations<String, String, CartItemRedisDTO> hashOps = redisTemplate.opsForHash();
        Map<String, CartItemRedisDTO> redisItems = hashOps.entries(cartKey);

        if (redisItems.isEmpty()) {
            return new CartResponseDTO(new ArrayList<>(), 0.0);
        }

        redisTemplate.expire(cartKey, CART_TTL_DAYS, TimeUnit.DAYS);

        List<CartResponseDTO.CartItemDetail> detailList = new ArrayList<>();
        double totalCartPrice = 0.0;

        for (CartItemRedisDTO redisItem : redisItems.values()) {
            try {
                ProductResponseDTO productInfo = productService.getProductById(redisItem.getProductId(), userId);

                int currentStock = 0;
                if (productInfo.getVariants() != null) {
                    for (ProductResponseDTO.VariantDTO v : productInfo.getVariants()) {
                        if (v.getSize().equals(redisItem.getSize())) {
                            currentStock = v.getQuantity();
                            break;
                        }
                    }
                }

                Double unitPrice = productInfo.getSalePrice() != null ? productInfo.getSalePrice() : productInfo.getPrice();
                Double itemTotalPrice = unitPrice * redisItem.getQuantity();
                totalCartPrice += itemTotalPrice;

                CartResponseDTO.CartItemDetail detail = CartResponseDTO.CartItemDetail.builder()
                        .productId(productInfo.getId())
                        .productName(productInfo.getName())
                        .imageUrl(productInfo.getImageUrl())
                        .size(redisItem.getSize())
                        .originalPrice(productInfo.getPrice())
                        .unitPrice(unitPrice)
                        .totalPrice(itemTotalPrice)
                        .quantity(redisItem.getQuantity())
                        .stockAvailable(currentStock)
                        .salePercent(productInfo.getSalePercent())
                        .build();

                detailList.add(detail);

            } catch (Exception e) {
                redisTemplate.opsForHash().delete(cartKey, getItemHashKey(redisItem.getProductId(), redisItem.getSize()));
            }
        }

        return new CartResponseDTO(detailList, totalCartPrice);
    }

    public void clearCart(UUID userId) {
        redisTemplate.delete(getCartKey(userId));
    }
}