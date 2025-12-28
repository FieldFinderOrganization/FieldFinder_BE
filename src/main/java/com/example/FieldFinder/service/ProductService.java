package com.example.FieldFinder.service;

import com.example.FieldFinder.dto.req.ProductRequestDTO;
import com.example.FieldFinder.dto.res.ProductResponseDTO;
import java.util.List;
import java.util.UUID;

public interface ProductService {
    ProductResponseDTO createProduct(ProductRequestDTO request);

    ProductResponseDTO getProductById(Long id, UUID userId);
    List<ProductResponseDTO> getAllProducts(UUID userId);
    List<ProductResponseDTO> getTopSellingProducts(int limit, UUID userId);
    List<ProductResponseDTO> findProductsByCategories(List<String> categories, UUID userId);

    ProductResponseDTO updateProduct(Long id, ProductRequestDTO request);
    void deleteProduct(Long id);
    void holdStock(Long productId, String size, int quantity);
    void commitStock(Long productId, String size, int quantity);
    void releaseStock(Long productId, String size, int quantity);

    // AI & Search
    List<ProductResponseDTO> findProductsByImage(List<String> keywords, String majorCategory);
    void enrichAllProductsData();
    List<ProductResponseDTO> findProductsByVector(String descriptionFromImage);
    ProductResponseDTO getProductByName(String productName);

    void applyDiscount(Long productId, String discountId);
}