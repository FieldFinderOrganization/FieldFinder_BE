package com.example.FieldFinder.service;

import com.example.FieldFinder.dto.req.ProductRequestDTO;
import com.example.FieldFinder.dto.res.ProductResponseDTO;

import java.util.List;

public interface ProductService {
    ProductResponseDTO createProduct(ProductRequestDTO request);
    ProductResponseDTO getProductById(Long id);
    List<ProductResponseDTO> getAllProducts();
    ProductResponseDTO updateProduct(Long id, ProductRequestDTO request);
    void deleteProduct(Long id);
    void holdStock(Long productId, String size, int quantity);
    void commitStock(Long productId, String size, int quantity);
    void releaseStock(Long productId, String size, int quantity);
    List<ProductResponseDTO> getTopSellingProducts(int limit);
    List<ProductResponseDTO> findProductsByImage(List<String> keywords, String majorCategory);
}
