package com.example.FieldFinder.controller;


import com.example.FieldFinder.dto.req.ProductRequestDTO;
import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.example.FieldFinder.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @PostMapping
    public ProductResponseDTO create(@RequestBody ProductRequestDTO request) {
        return productService.createProduct(request);
    }

    @GetMapping("/{id}")
    public ProductResponseDTO getById(@PathVariable Long id) {
        return productService.getProductById(id);
    }

    @GetMapping
    public List<ProductResponseDTO> getAll() {
        return productService.getAllProducts();
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
    public List<ProductResponseDTO> getTopSelling() {
        return productService.getTopSellingProducts(5);
    }

    @PostMapping("/enrich-data")
    public ResponseEntity<String> enrichData() {
        new Thread(() -> {
            productService.enrichAllProductsData();
        }).start();
        return ResponseEntity.ok("Quá trình AI hóa dữ liệu đang chạy ngầm. Vui lòng theo dõi Console log.");
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
