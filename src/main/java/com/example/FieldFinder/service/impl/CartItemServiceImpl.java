package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.CartStatus;
import com.example.FieldFinder.dto.req.CartItemRequestDTO;
import com.example.FieldFinder.dto.res.CartItemResponseDTO;
import com.example.FieldFinder.entity.*;
import com.example.FieldFinder.repository.*;
import com.example.FieldFinder.service.CartItemService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CartItemServiceImpl implements CartItemService {

    private final CartItemRepository cartItemRepository;
    private final CartRepository cartRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public CartItemResponseDTO addItemToCart(CartItemRequestDTO request) {
        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found!"));

        ProductVariant variant = productVariantRepository.findByProduct_ProductIdAndSize(
                product.getProductId(), request.getSize()
        ).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Size not available for this product!"));

        Cart cart = null;

        if (request.getCartId() != null) {
            cart = cartRepository.findById(request.getCartId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cart not found!"));

            if (!CartStatus.ACTIVE.equals(cart.getStatus())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cart is already completed or abandoned!");
            }

        } else {
            if (request.getUserId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UserId is required for new cart!");
            }
            User user = userRepository.findByUserId(request.getUserId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found!"));

            cart = new Cart();
            cart.setStatus(CartStatus.ACTIVE);
            cart.setUser(user);
            cart.setCreatedAt(LocalDateTime.now());
            cart = cartRepository.save(cart);
        }

        Optional<Cart_item> existingItemOpt = cartItemRepository.findByCartAndProductAndSize(
                cart, product, request.getSize()
        );

        Cart_item item;
        if (existingItemOpt.isPresent()) {
            item = existingItemOpt.get();
            int newQuantity = item.getQuantity() + request.getQuantity();

            if (newQuantity > variant.getAvailableQuantity()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Requested quantity exceeds available stock for size " + request.getSize());
            }
            item.setQuantity(newQuantity);
            item.setPriceAtTime(product.getPrice());

        } else {
            if (request.getQuantity() > variant.getAvailableQuantity()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Requested quantity exceeds available stock for size " + request.getSize());
            }

            double unitPrice = product.getPrice();

            item = Cart_item.builder()
                    .cart(cart)
                    .product(product)
                    .quantity(request.getQuantity())
                    .priceAtTime(unitPrice)
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

        item.setQuantity(quantity);
        item.setPriceAtTime(product.getPrice());

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