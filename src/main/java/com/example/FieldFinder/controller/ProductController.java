package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.req.ProductRequestDTO;
import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.example.FieldFinder.service.ProductService;
import com.example.FieldFinder.service.RedisService;
import com.fasterxml.jackson.databind.ObjectMapper; // Import thêm ObjectMapper
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final RedisService redisService;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
                return redisService.getUserIdByEmail(email);
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    @GetMapping("/{id}")
    public ProductResponseDTO getById(@PathVariable Long id, Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        return productService.getProductDetail(id, userId);
    }

    @GetMapping
    public Page<ProductResponseDTO> getAll(
            Pageable pageable,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Set<String> genders,
            @RequestParam(required = false) String brand,
            Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        return productService.getAllProducts(pageable, categoryId, genders, brand, userId);
    }

    @GetMapping("/top-selling")
    public List<ProductResponseDTO> getTopSelling(Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        return productService.getTopSellingProducts(5, userId);
    }

    @GetMapping("/by-categories")
    public List<ProductResponseDTO> getByCategories(@RequestParam List<String> categories, Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        return productService.findProductsByCategories(categories, userId);
    }

    @PostMapping(consumes = {"multipart/form-data"})
    @PreAuthorize("hasAnyRole('ADMIN', 'PROVIDER')")
    public ProductResponseDTO create(
            @RequestPart("product") String productJson,
            @RequestPart(value = "image", required = false) MultipartFile imageFile) {
        try {
            ProductRequestDTO request = objectMapper.readValue(productJson, ProductRequestDTO.class);
            return productService.createProduct(request, imageFile);
        } catch (Exception e) {
            throw new RuntimeException("Lỗi định dạng JSON dữ liệu sản phẩm: " + e.getMessage());
        }
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PROVIDER')")
    public ProductResponseDTO update(@PathVariable Long id, @RequestBody ProductRequestDTO request) {
        return productService.updateProduct(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PROVIDER')")
    public void delete(@PathVariable Long id) {
        productService.deleteProduct(id);
    }

    @PostMapping("/{productId}/discounts/{discountId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PROVIDER')")
    public ResponseEntity<Void> applyDiscountToProduct(
            @PathVariable Long productId,
            @PathVariable String discountId
    ) {
        productService.applyDiscount(productId, discountId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/enrich-data")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<String> enrichData() {
        new Thread(productService::enrichAllProductsData).start();
        return ResponseEntity.ok("Quá trình AI hóa dữ liệu đang chạy ngầm.");
    }
}