package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.req.CartItemRequestDTO;
import com.example.FieldFinder.dto.res.CartItemResponseDTO;
import com.example.FieldFinder.entity.Cart;
import com.example.FieldFinder.entity.Cart_item;
import com.example.FieldFinder.entity.Product;
import com.example.FieldFinder.entity.ProductVariant; // 👈 Import Variant
import com.example.FieldFinder.repository.CartItemRepository;
import com.example.FieldFinder.repository.CartRepository;
import com.example.FieldFinder.repository.ProductRepository;
import com.example.FieldFinder.repository.ProductVariantRepository; // 👈 Import Variant Repo
import com.example.FieldFinder.service.CartItemService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CartItemServiceImpl implements CartItemService {

    private final CartItemRepository cartItemRepository;
    private final CartRepository cartRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository; // 👈 Inject Repo này

    @Override
    @Transactional
    public CartItemResponseDTO addItemToCart(CartItemRequestDTO request) {
        Cart cart = cartRepository.findById(request.getCartId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cart not found!"));

        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found!"));

        // 1. Tìm Variant (Size) tương ứng
        ProductVariant variant = productVariantRepository.findByProduct_ProductIdAndSize(
                product.getProductId(), request.getSize()
        ).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Size not available for this product!"));

        // 2. Kiểm tra item trong giỏ hàng
        Optional<Cart_item> existingItemOpt = cartItemRepository.findByCartAndProductAndSize(
                cart, product, request.getSize()
        );

        Cart_item item;
        if (existingItemOpt.isPresent()) {
            item = existingItemOpt.get();
            int newQuantity = item.getQuantity() + request.getQuantity();

            // 3. Kiểm tra tồn kho trên Variant
            if (newQuantity > variant.getAvailableQuantity()) { // 👈 SỬA: Check trên variant
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Requested quantity exceeds available stock for size " + request.getSize());
            }
            item.setQuantity(newQuantity);
            item.setPriceAtTime(product.getPrice() * newQuantity);

        } else {
            // 4. Kiểm tra tồn kho trên Variant (cho item mới)
            if (request.getQuantity() > variant.getAvailableQuantity()) { // 👈 SỬA: Check trên variant
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Requested quantity exceeds available stock for size " + request.getSize());
            }
            double totalPrice = product.getPrice() * request.getQuantity();

            item = Cart_item.builder()
                    .cart(cart)
                    .product(product)
                    .quantity(request.getQuantity())
                    .priceAtTime(totalPrice)
                    .size(request.getSize())
                    .build();
        }

        Cart_item saved = cartItemRepository.save(item);
        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public CartItemResponseDTO updateCartItem(Long cartItemId, int quantity) {
        Cart_item item = cartItemRepository.findById(cartItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cannot find cart item!"));

        Product product = item.getProduct();

        // 5. Tìm Variant để check kho khi update
        ProductVariant variant = productVariantRepository.findByProduct_ProductIdAndSize(
                product.getProductId(), item.getSize()
        ).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Size not found anymore!"));

        if (quantity <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Quantity must be greater than 0. Use DELETE endpoint to remove.");
        }

        // 6. Kiểm tra tồn kho trên Variant
        if (quantity > variant.getAvailableQuantity()) { // 👈 SỬA: Check trên variant
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The quantity exceeds available stock for size " + item.getSize());
        }

        item.setQuantity(quantity);
        item.setPriceAtTime(product.getPrice() * quantity);

        Cart_item updated = cartItemRepository.save(item);
        return mapToResponse(updated);
    }

    @Override
    @Transactional
    public void removeCartItem(Long cartItemId) {
        Cart_item item = cartItemRepository.findById(cartItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cannot find  cart item!"));
        cartItemRepository.delete(item);
    }

    @Override
    public List<CartItemResponseDTO> getItemsByCart(Long cartId) {
        Cart cart = cartRepository.findById(cartId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cannot find cart!"));

        return cartItemRepository.findByCart(cart).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    private CartItemResponseDTO mapToResponse(Cart_item item) {
        return CartItemResponseDTO.builder()
                .id(item.getId())
                .cartId(item.getCart().getCartId())
                .productId(item.getProduct().getProductId())
                .productName(item.getProduct().getName())
                .imageUrl(item.getProduct().getImageUrl())
                .quantity(item.getQuantity())
                .size(item.getSize())
                .priceAtTime(item.getPriceAtTime())
                .build();
    }
}