package com.example.FieldFinder.service;

import com.example.FieldFinder.dto.req.CartItemRequestDTO;
import com.example.FieldFinder.dto.res.CartItemResponseDTO;

import java.util.List;

public interface CartItemService {
    CartItemResponseDTO addItemToCart(CartItemRequestDTO request);
    CartItemResponseDTO updateCartItem(Long cartItemId, int quantity);
    void removeCartItem(Long cartItemId);
    List<CartItemResponseDTO> getItemsByCart(Long cartId);
}
