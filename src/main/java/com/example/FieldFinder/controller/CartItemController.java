package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.req.CartItemRequestDTO;
import com.example.FieldFinder.dto.res.CartItemResponseDTO;
import com.example.FieldFinder.service.CartItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@CrossOrigin("*")
@RequestMapping("/api/cart-items")
@RequiredArgsConstructor
public class CartItemController {
    private final CartItemService cartItemService;

    @PostMapping
    public ResponseEntity<CartItemResponseDTO> addItem(@RequestBody CartItemRequestDTO request) {
        return ResponseEntity.ok(cartItemService.addItemToCart(request));
    }

    @PutMapping("/{cartItemId}")
    public ResponseEntity<CartItemResponseDTO> updateItem(
            @PathVariable Long cartItemId,
            @RequestParam int quantity) {
        return ResponseEntity.ok(cartItemService.updateCartItem(cartItemId, quantity));
    }

    @DeleteMapping("/{cartItemId}")
    public ResponseEntity<Void> removeItem(@PathVariable Long cartItemId) {
        cartItemService.removeCartItem(cartItemId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/cart/{cartId}")
    public ResponseEntity<List<CartItemResponseDTO>> getItemsByCart(@PathVariable Long cartId) {
        return ResponseEntity.ok(cartItemService.getItemsByCart(cartId));
    }
}
