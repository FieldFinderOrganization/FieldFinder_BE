package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.req.CartItemRequestDTO;
import com.example.FieldFinder.dto.res.CartResponseDTO;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.CartRedisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

@CrossOrigin("*")
@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartRedisService cartService;
    private final UserRepository userRepository;

    private UUID getUserIdFromAuth(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal().equals("anonymousUser")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bạn cần đăng nhập để sử dụng giỏ hàng!");
        }
        try {
            Object principal = authentication.getPrincipal();
            String email = null;
            if (principal instanceof UserDetails) email = ((UserDetails) principal).getUsername();
            else if (principal instanceof String) email = (String) principal;

            if (email != null) {
                Optional<User> user = userRepository.findByEmail(email);
                if (user.isPresent()) return user.get().getUserId();
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Lỗi xác thực người dùng");
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Không tìm thấy người dùng");
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CartResponseDTO> getMyCart(Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        return ResponseEntity.ok(cartService.getCart(userId));
    }

    @PostMapping("/add")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<String> addItem(@RequestBody CartItemRequestDTO request, Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        cartService.addItemToCart(userId, request.getProductId(), request.getSize(), request.getQuantity());
        return ResponseEntity.ok("Đã thêm vào giỏ hàng!");
    }

    @PutMapping("/update")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<String> updateItem(@RequestBody CartItemRequestDTO request, Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        cartService.updateCartItem(userId, request.getProductId(), request.getSize(), request.getQuantity());
        return ResponseEntity.ok("Cập nhật số lượng thành công!");
    }

    @DeleteMapping("/remove")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<String> removeItem(
            @RequestParam Long productId,
            @RequestParam String size,
            Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        cartService.removeItemFromCart(userId, productId, size);
        return ResponseEntity.ok("Đã xóa khỏi giỏ hàng");
    }

    @DeleteMapping("/clear")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<String> clearCart(Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        cartService.clearCart(userId);
        return ResponseEntity.ok("Giỏ hàng đã được làm sạch");
    }
}