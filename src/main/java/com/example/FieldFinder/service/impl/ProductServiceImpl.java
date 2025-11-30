package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.req.ProductRequestDTO;
import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.example.FieldFinder.entity.Category;
import com.example.FieldFinder.entity.Product;
import com.example.FieldFinder.entity.ProductVariant;
import com.example.FieldFinder.repository.CategoryRepository;
import com.example.FieldFinder.repository.ProductRepository;
import com.example.FieldFinder.repository.ProductVariantRepository;
import com.example.FieldFinder.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductVariantRepository productVariantRepository;

    @Override
    public ProductResponseDTO createProduct(ProductRequestDTO request) {
        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new RuntimeException("Category not found!"));

        Product product = Product.builder()
                .category(category)
                .name(request.getName())
                .description(request.getDescription())
                .price(request.getPrice())
                .imageUrl(request.getImageUrl())
                .brand(request.getBrand())
                .sex(request.getSex())
                .tags(request.getTags())
                .build();

        productRepository.save(product);

        if (request.getVariants() != null) {
            List<ProductVariant> variants = request.getVariants().stream().map(v ->
                    ProductVariant.builder()
                            .product(product)
                            .size(v.getSize())
                            .stockQuantity(v.getQuantity())
                            .lockedQuantity(0)
                            .soldQuantity(0)
                            .build()
            ).collect(Collectors.toList());

            productVariantRepository.saveAll(variants);
            product.setVariants(variants);
        }
        return mapToResponse(product);
    }

    @Override
    public ProductResponseDTO getProductById(Long id) {
        return productRepository.findById(id)
                .map(this::mapToResponse)
                .orElseThrow(() -> new RuntimeException("Product not found!"));
    }

    @Override
    public List<ProductResponseDTO> getAllProducts() {
        return productRepository.findAll()
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public ProductResponseDTO updateProduct(Long id, ProductRequestDTO request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found!"));

        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new RuntimeException("Category not found!"));

        // 1. Update basic information
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setCategory(category);
        product.setPrice(request.getPrice());
        product.setImageUrl(request.getImageUrl());
        product.setBrand(request.getBrand());
        product.setSex(request.getSex());

        // 2. Update Tags
        if (request.getTags() != null) {
            if (product.getTags() == null) {
                product.setTags(new ArrayList<>());
            } else {
                product.getTags().clear();
            }
            product.getTags().addAll(request.getTags());
        }

        // 3. Update Variants
        if (request.getVariants() != null) {
            List<ProductVariant> currentVariants = product.getVariants();
            if (currentVariants == null) {
                currentVariants = new ArrayList<>();
                product.setVariants(currentVariants);
            }

            for (ProductRequestDTO.VariantDTO reqVariant : request.getVariants()) {
                ProductVariant existingVariant = currentVariants.stream()
                        .filter(v -> v.getSize().equals(reqVariant.getSize()))
                        .findFirst()
                        .orElse(null);

                if (existingVariant != null) {
                    // Update số lượng tồn kho
                    existingVariant.setStockQuantity(reqVariant.getQuantity());

                } else {
                    // Tạo mới variant
                    ProductVariant newVariant = ProductVariant.builder()
                            .product(product)
                            .size(reqVariant.getSize())
                            .stockQuantity(reqVariant.getQuantity())
                            .lockedQuantity(0)
                            .soldQuantity(0)
                            .build();
                    currentVariants.add(newVariant);
                }
            }
        }

        productRepository.saveAndFlush(product);

        return getProductById(id);
    }

    @Override
    public void deleteProduct(Long id) {
        productRepository.deleteById(id);
    }

    @Override
    @Transactional
    public void holdStock(Long productId, String size, int quantity) {
        ProductVariant variant = productVariantRepository.findByProduct_ProductIdAndSize(productId, size)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy size " + size + " cho sản phẩm này"));

        int available = variant.getAvailableQuantity();
        if (available < quantity) {
            throw new RuntimeException("Size " + size + " đã hết hàng (Còn: " + available + ")");
        }

        variant.setLockedQuantity(variant.getLockedQuantity() + quantity);
        productVariantRepository.save(variant);
    }

    @Override
    @Transactional
    public void commitStock(Long productId, String size, int quantity) {
        ProductVariant variant = productVariantRepository.findByProduct_ProductIdAndSize(productId, size)
                .orElseThrow(() -> new RuntimeException("Variant not found"));

        variant.setStockQuantity(variant.getStockQuantity() - quantity);
        variant.setLockedQuantity(variant.getLockedQuantity() - quantity);
        variant.setSoldQuantity(variant.getSoldQuantity() + quantity);

        productVariantRepository.save(variant);
    }

    @Override
    @Transactional
    public void releaseStock(Long productId, String size, int quantity) {
        ProductVariant variant = productVariantRepository.findByProduct_ProductIdAndSize(productId, size)
                .orElseThrow(() -> new RuntimeException("Variant not found"));

        int newLocked = variant.getLockedQuantity() - quantity;
        variant.setLockedQuantity(Math.max(newLocked, 0));

        productVariantRepository.save(variant);
    }

    @Override
    public List<ProductResponseDTO> getTopSellingProducts(int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        return productRepository.findTopSellingProducts(pageable)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<ProductResponseDTO> findProductsByImage(List<String> keywords, String majorCategory) {
        if (keywords == null || keywords.isEmpty()) {
            return new ArrayList<>();
        }

        List<String> lowerKeywords = keywords.stream()
                .map(String::toLowerCase)
                .collect(Collectors.toList());

        List<Product> candidates = productRepository.findByTagsIn(lowerKeywords);

        List<Product> filteredCandidates = candidates.stream()
                .filter(p -> isValidCategory(p, majorCategory))
                .collect(Collectors.toList());

        return filteredCandidates.stream()
                .sorted((p1, p2) -> {
                    long score1 = calculateScore(p1, lowerKeywords);
                    long score2 = calculateScore(p2, lowerKeywords);
                    return Long.compare(score2, score1);
                })
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    private boolean isValidCategory(Product p, String aiCategory) {
        if (aiCategory == null || aiCategory.equals("ALL")) return true;

        String content = (p.getCategory().getName() + " " + p.getName()).toLowerCase();

        switch (aiCategory) {
            case "FOOTWEAR":
                return isShoe(content);
            case "CLOTHING":
                return isClothing(content);
            case "ACCESSORY":
                return isAccessory(content);
            default:
                return true;
        }
    }

    private long calculateScore(Product p, List<String> keywords) {
        long score = 0;
        if (p.getTags() == null) return 0;

        for (String tag : p.getTags()) {
            String lowerTag = tag.toLowerCase();

            if (keywords.contains(lowerTag)) {

                if (isBrand(lowerTag)) {
                    score += 10;
                }
                else if (isColor(lowerTag)) {
                    score += 5;
                }
                else {
                    score += 1;
                }
            }
        }
        return score;
    }

    private List<String> normalizeTags(List<String> rawTags) {
        if (rawTags == null || rawTags.isEmpty()) {
            return new ArrayList<>();
        }
        return rawTags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(tag -> tag.trim().toLowerCase())
                .distinct()
                .collect(Collectors.toList());
    }

    private boolean isShoe(String text) {
        return text.contains("giày") || text.contains("shoe") || text.contains("sneaker") ||
                text.contains("boot") || text.contains("dép") || text.contains("sandal");
    }

    private boolean isClothing(String text) {
        return text.contains("áo") || text.contains("shirt") || text.contains("tee") || text.contains("top") ||
                text.contains("quần") || text.contains("pant") || text.contains("short") || text.contains("trousers") ||
                text.contains("váy") || text.contains("skirt") || text.contains("đầm") || text.contains("dress") ||
                text.contains("bộ") || text.contains("set") || text.contains("khoác") || text.contains("jacket") || text.contains("hoodie");
    }

    private boolean isAccessory(String text) {
        return text.contains("nón") || text.contains("mũ") || text.contains("hat") || text.contains("cap") ||
                text.contains("túi") || text.contains("bag") ||
                text.contains("balo") || text.contains("backpack") ||
                text.contains("găng") || text.contains("glove") ||
                text.contains("vớ") || text.contains("tất") || text.contains("sock") ||
                text.contains("bóng") || text.contains("ball") || text.contains("bảo vệ") || text.contains("guard");
    }

    private boolean isBrand(String text) {
        return List.of("nike", "adidas", "puma", "k-swiss", "converse").contains(text);
    }

    private boolean isColor(String text) {
        List<String> colors = List.of(
                "đen", "black", "trắng", "white",
                "đỏ", "red", "xanh", "blue", "green", "navy",
                "vàng", "yellow", "cam", "orange",
                "hồng", "pink", "tím", "purple",
                "xám", "grey", "gray", "nâu", "brown",
                "bạc", "silver", "gold"
        );
        return colors.stream().anyMatch(text::contains);
    }

    private ProductResponseDTO mapToResponse(Product product) {
        List<ProductResponseDTO.VariantDTO> variantDTOs = new ArrayList<>();
        if (product.getVariants() != null) {
            variantDTOs = product.getVariants().stream()
                    .map(v -> ProductResponseDTO.VariantDTO.builder()
                            .size(v.getSize())
                            .quantity(v.getAvailableQuantity())
                            .stockTotal(v.getStockQuantity())
                            .build())
                    .collect(Collectors.toList());
        }

        return ProductResponseDTO.builder()
                .id(product.getProductId())
                .name(product.getName())
                .description(product.getDescription())
                .categoryName(product.getCategory().getName())
                .price(product.getPrice())
                .imageUrl(product.getImageUrl())
                .brand(product.getBrand())
                .sex(product.getSex())
                .tags(product.getTags())
                .variants(variantDTOs)
                .build();
    }
}