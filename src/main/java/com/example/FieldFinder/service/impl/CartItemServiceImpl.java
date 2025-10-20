package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.req.CartItemRequestDTO;
import com.example.FieldFinder.dto.res.CartItemResponseDTO;
import com.example.FieldFinder.entity.Cart;
import com.example.FieldFinder.entity.Cart_item;
import com.example.FieldFinder.entity.Product;
import com.example.FieldFinder.repository.CartItemRepository;
import com.example.FieldFinder.repository.CartRepository;
import com.example.FieldFinder.repository.ProductRepository;
import com.example.FieldFinder.service.CartItemService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CartItemServiceImpl implements CartItemService {

    private final CartItemRepository cartItemRepository;
    private final CartRepository cartRepository;
    private final ProductRepository productRepository;

    @Override
    @Transactional
    public CartItemResponseDTO addItemToCart(CartItemRequestDTO request) {
        Cart cart = cartRepository.findById(request.getCartId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cart not found"));

        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));

        if (request.getQuantity() > product.getStockQuantity()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Requested quantity exceeds available stock");
        }

        double totalPrice = product.getPrice() * request.getQuantity();

        Cart_item item = Cart_item.builder()
                .cart(cart)
                .product(product)
                .quantity(request.getQuantity())
                .priceAtTime(totalPrice)
                .build();

        Cart_item saved = cartItemRepository.save(item);

        return CartItemResponseDTO.builder()
                .id(saved.getId())
                .cartId(cart.getCartId())
                .productId(product.getProductId())
                .productName(product.getName())
                .quantity(saved.getQuantity())
                .priceAtTime(saved.getPriceAtTime())
                .build();
    }

    @Override
    @Transactional
    public CartItemResponseDTO updateCartItem(Long cartItemId, int quantity) {
        Cart_item item = cartItemRepository.findById(cartItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy sản phẩm trong giỏ"));

        Product product = item.getProduct();

        if (quantity <= 0) {
            cartItemRepository.delete(item);
            throw new ResponseStatusException(HttpStatus.OK, "Sản phẩm được xóa khỏi giỏ hàng thành công");
        }

        if (quantity > product.getStockQuantity()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Số lượng sản phẩm vượt quá số lượng tồn kho");
        }

        item.setQuantity(quantity);
        item.setPriceAtTime(product.getPrice() * quantity);

        Cart_item updated = cartItemRepository.save(item);

        return CartItemResponseDTO.builder()
                .id(updated.getId())
                .cartId(updated.getCart().getCartId())
                .productId(updated.getProduct().getProductId())
                .productName(updated.getProduct().getName())
                .quantity(updated.getQuantity())
                .priceAtTime(updated.getPriceAtTime())
                .build();
    }

    @Override
    @Transactional
    public void removeCartItem(Long cartItemId) {
        Cart_item item = cartItemRepository.findById(cartItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy sản phẩm trong giỏ"));
        cartItemRepository.delete(item);
    }

    @Override
    public List<CartItemResponseDTO> getItemsByCart(Long cartId) {
        Cart cart = cartRepository.findById(cartId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy giỏ hàng"));

        return cartItemRepository.findByCart(cart).stream()
                .map(item -> CartItemResponseDTO.builder()
                        .id(item.getId())
                        .cartId(cart.getCartId())
                        .productId(item.getProduct().getProductId())
                        .productName(item.getProduct().getName())
                        .quantity(item.getQuantity())
                        .priceAtTime(item.getPriceAtTime())
                        .build())
                .collect(Collectors.toList());
    }
}
