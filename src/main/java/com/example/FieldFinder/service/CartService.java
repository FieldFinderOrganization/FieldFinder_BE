package com.example.FieldFinder.service;

import com.example.FieldFinder.dto.req.CartRequestDTO;
import com.example.FieldFinder.dto.res.CartResponseDTO;

import java.util.List;
import java.util.UUID;
public interface CartService {
    CartResponseDTO createCart(CartRequestDTO request);
    List<CartResponseDTO> getAllCarts();
    List<CartResponseDTO> getCartsByUserId(UUID userId);
    void deleteCart(Long cartId);
}
