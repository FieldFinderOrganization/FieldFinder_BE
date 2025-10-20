package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.req.CartRequestDTO;
import com.example.FieldFinder.dto.res.CartResponseDTO;
import com.example.FieldFinder.entity.Cart;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.CartRepository;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.CartService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    private final CartRepository cartRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public CartResponseDTO createCart(CartRequestDTO request) {
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng"));

        Cart cart = Cart.builder()
                .user(user)
                .createdAt(LocalDateTime.now())
                .build();


        Cart savedCart = cartRepository.save(cart);

        return CartResponseDTO.builder()
                .cartId(savedCart.getCartId())
                .userId(user.getUserId())
                .userName(user.getName())
                .createdAt(savedCart.getCreatedAt())
                .build();
    }

    @Override
    public List<CartResponseDTO> getAllCarts() {
        return cartRepository.findAll().stream()
                .map(cart -> CartResponseDTO.builder()
                        .cartId(cart.getCartId())
                        .userId(cart.getUser().getUserId())
                        .userName(cart.getUser().getName())
                        .createdAt(cart.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    public List<CartResponseDTO> getCartsByUserId(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng"));

        return cartRepository.findByUser(user).stream()
                .map(cart -> CartResponseDTO.builder()
                        .cartId(cart.getCartId())
                        .userId(user.getUserId())
                        .userName(user.getName())
                        .createdAt(cart.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void deleteCart(Long cartId) {
        Cart cart = cartRepository.findById(cartId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy giỏ hàng"));
        cartRepository.delete(cart);
    }
}
