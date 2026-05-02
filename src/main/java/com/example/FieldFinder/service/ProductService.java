package com.example.FieldFinder.service;

import com.example.FieldFinder.dto.req.ProductRequestDTO;
import com.example.FieldFinder.dto.res.ProductResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface ProductService {
    ProductResponseDTO createProduct(ProductRequestDTO request, MultipartFile imageFile);

    ProductResponseDTO getProductById(Long id, UUID userId);
    Page<ProductResponseDTO> getAllProducts(Pageable pageable, Long categoryId, Set<String> genders, String brand, UUID userId);
    List<ProductResponseDTO> getTopSellingProducts(int limit, UUID userId);
    List<ProductResponseDTO> findProductsByCategories(List<String> categories, UUID userId);

    ProductResponseDTO updateProduct(Long id, ProductRequestDTO request);
    void deleteProduct(Long id);
    void holdStock(Long productId, String size, int quantity);
    void commitStock(Long productId, String size, int quantity);
    void releaseStock(Long productId, String size, int quantity);

    List<ProductResponseDTO> findProductsByImage(List<String> keywords, String majorCategory);
    void enrichAllProductsData();
    List<ProductResponseDTO> findProductsByVector(String descriptionFromImage);
    List<Map.Entry<ProductResponseDTO, Double>> findProductsByVectorWithScores(String description);
    ProductResponseDTO getProductByName(String productName);

    void applyDiscount(Long productId, String discountId);

    ProductResponseDTO getProductDetail(Long productId, UUID userId);
}