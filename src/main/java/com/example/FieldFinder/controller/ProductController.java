package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.req.ProductRequestDTO;
import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final UserRepository userRepository;

    private UUID getUserIdFromAuth(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal().equals("anonymousUser")) {
            return null;
        }

        try {
            Object principal = authentication.getPrincipal();
            String email = null;

            if (principal instanceof UserDetails) {
                email = ((UserDetails) principal).getUsername();
            } else if (principal instanceof String) {
                email = (String) principal;
            }

            if (email != null) {
                Optional<User> user = userRepository.findByEmail(email);
                if (user.isPresent()) {
                    return user.get().getUserId();
                }
            }
        } catch (Exception e) {
            // Log error
            return null;
        }
        return null;
    }

    @PostMapping
    public ProductResponseDTO create(@RequestBody ProductRequestDTO request) {
        return productService.createProduct(request);
    }

    @GetMapping("/{id}")
    public ProductResponseDTO getById(@PathVariable Long id, Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        return productService.getProductById(id, userId);
    }

    @GetMapping
    public List<ProductResponseDTO> getAll(Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        return productService.getAllProducts(userId);
    }

    @PutMapping("/{id}")
    public ProductResponseDTO update(@PathVariable Long id, @RequestBody ProductRequestDTO request) {
        return productService.updateProduct(id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        productService.deleteProduct(id);
    }

    @GetMapping("/top-selling")
    public List<ProductResponseDTO> getTopSelling(Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        return productService.getTopSellingProducts(5, userId);
    }

    // API lọc theo category (bạn có hỏi trong service cũ)
    @GetMapping("/by-categories")
    public List<ProductResponseDTO> getByCategories(@RequestParam List<String> categories, Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        return productService.findProductsByCategories(categories, userId);
    }

    @PostMapping("/enrich-data")
    public ResponseEntity<String> enrichData() {
        new Thread(() -> {
            productService.enrichAllProductsData();
        }).start();
        return ResponseEntity.ok("Quá trình AI hóa dữ liệu đang chạy ngầm.");
    }

    @PostMapping("/{productId}/discounts/{discountId}")
    public ResponseEntity<Void> applyDiscountToProduct(
            @PathVariable Long productId,
            @PathVariable String discountId
    ) {
        productService.applyDiscount(productId, discountId);
        return ResponseEntity.ok().build();
    }
}