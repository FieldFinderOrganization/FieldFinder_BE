package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.ai.AIChat;
import com.example.FieldFinder.dto.req.ProductRequestDTO;
import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.example.FieldFinder.entity.Category;
import com.example.FieldFinder.entity.Discount;
import com.example.FieldFinder.entity.Product;
import com.example.FieldFinder.entity.ProductVariant;
import com.example.FieldFinder.repository.CategoryRepository;
import com.example.FieldFinder.repository.DiscountRepository;
import com.example.FieldFinder.repository.ProductRepository;
import com.example.FieldFinder.repository.ProductVariantRepository;
import com.example.FieldFinder.service.ProductService;
import com.example.FieldFinder.entity.ProductDiscount;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductVariantRepository productVariantRepository;
    private final DiscountRepository discountRepository;
    private final AIChat aiChat;

    public ProductServiceImpl(
            ProductRepository productRepository,
            CategoryRepository categoryRepository,
            ProductVariantRepository productVariantRepository,
            DiscountRepository discountRepository,
            @Lazy AIChat aiChat
    ) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.productVariantRepository = productVariantRepository;
        this.discountRepository = discountRepository;
        this.aiChat = aiChat;
    }

    // --- Helper Method Quan Trọng ---
    // Hàm này giúp kích hoạt dữ liệu Lazy và tính toán giá bao gồm cả giảm giá Danh mục
    private ProductResponseDTO mapToResponse(Product product) {
        if (product == null) return null;

        // 1. FORCE INITIALIZE: Kích hoạt tải danh sách Discount đã gán cứng từ DB
        if (product.getDiscounts() != null) {
            product.getDiscounts().size();
            for (ProductDiscount pd : product.getDiscounts()) {
                if (pd.getDiscount() != null) {
                    pd.getDiscount().getDiscountId();
                    pd.getDiscount().getValue();
                    pd.getDiscount().getMaxDiscountAmount();
                }
            }
        } else {
            product.setDiscounts(new ArrayList<>());
        }

        // 2. TÌM MÃ GIẢM GIÁ THEO DANH MỤC (Implicit Discounts)
        // Lấy danh sách ID category cha để tìm khuyến mãi áp dụng cho cả nhánh
        List<Long> categoryIds = new ArrayList<>();
        Category current = product.getCategory();
        while (current != null) {
            categoryIds.add(current.getCategoryId());
            current = current.getParent();
        }

        // Gọi Repository để tìm các mã giảm giá áp dụng cho danh mục này
        List<Discount> implicitDiscounts = discountRepository.findApplicableDiscountsForProduct(
                product.getProductId(),
                categoryIds
        );

        // 3. TÍNH TOÁN GIÁ AN TOÀN (Safe Calculation)
        // Tạo một đối tượng Product tạm để tính giá, tránh sửa đổi trực tiếp vào Entity đang Managed (tránh lỗi Dirty Check lưu nhầm vào DB)
        Product tempCalcProduct = Product.builder()
                .price(product.getPrice())
                .discounts(new ArrayList<>(product.getDiscounts())) // Copy danh sách hiện có
                .build();

        // Lấy danh sách ID các mã đã có để tránh trùng lặp
        Set<UUID> existingDiscountIds = product.getDiscounts().stream()
                .map(pd -> pd.getDiscount().getDiscountId())
                .collect(Collectors.toSet());

        // Thêm các mã giảm giá danh mục vào đối tượng tạm
        for (Discount d : implicitDiscounts) {
            if (!existingDiscountIds.contains(d.getDiscountId())) {
                ProductDiscount dummyPD = ProductDiscount.builder()
                        .product(tempCalcProduct)
                        .discount(d)
                        .build();
                tempCalcProduct.getDiscounts().add(dummyPD);
            }
        }

        // 4. Map dữ liệu ra DTO
        ProductResponseDTO dto = ProductResponseDTO.fromEntity(product);

        // Ghi đè giá và % giảm bằng kết quả tính toán từ đối tượng tạm (đã bao gồm khuyến mãi danh mục)
        dto.setSalePrice(tempCalcProduct.getSalePrice());
        dto.setSalePercent(tempCalcProduct.getOnSalePercent());

        return dto;
    }

    @Override
    @Transactional
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
                .tags(request.getTags() != null ? request.getTags() : new ArrayList<>())
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

        if (product.getImageUrl() != null && !product.getImageUrl().isEmpty()) {
            new Thread(() -> enrichSingleProduct(product.getProductId(), product.getImageUrl())).start();
        }

        return mapToResponse(product);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductResponseDTO getProductById(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found!"));
        return mapToResponse(product);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductResponseDTO> getAllProducts() {
        // mapToResponse sẽ được gọi cho từng sản phẩm
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

        String oldImageUrl = product.getImageUrl();

        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setCategory(category);
        product.setPrice(request.getPrice());
        product.setImageUrl(request.getImageUrl());
        product.setBrand(request.getBrand());
        product.setSex(request.getSex());

        if (request.getTags() != null) {
            if (product.getTags() == null) product.setTags(new ArrayList<>());
            else product.getTags().clear();
            product.getTags().addAll(request.getTags());
        }

        if (request.getVariants() != null) {
            if (product.getVariants() == null) product.setVariants(new ArrayList<>());

            for (ProductRequestDTO.VariantDTO reqVariant : request.getVariants()) {
                ProductVariant existingVariant = product.getVariants().stream()
                        .filter(v -> v.getSize().equals(reqVariant.getSize()))
                        .findFirst()
                        .orElse(null);

                if (existingVariant != null) {
                    existingVariant.setStockQuantity(reqVariant.getQuantity());
                } else {
                    ProductVariant newVariant = ProductVariant.builder()
                            .product(product)
                            .size(reqVariant.getSize())
                            .stockQuantity(reqVariant.getQuantity())
                            .lockedQuantity(0)
                            .soldQuantity(0)
                            .build();
                    product.getVariants().add(newVariant);
                }
            }
        }

        boolean imageChanged = !request.getImageUrl().equals(oldImageUrl);
        productRepository.saveAndFlush(product);

        if (imageChanged && request.getImageUrl() != null) {
            new Thread(() -> enrichSingleProduct(product.getProductId(), request.getImageUrl())).start();
        }

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
    @Transactional(readOnly = true)
    public List<ProductResponseDTO> getTopSellingProducts(int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        return productRepository.findTopSellingProducts(pageable)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductResponseDTO> findProductsByImage(List<String> keywords, String majorCategory) {
        if (keywords == null || keywords.isEmpty()) return new ArrayList<>();

        List<String> lowerKeywords = keywords.stream().map(String::toLowerCase).collect(Collectors.toList());
        List<Product> candidates = productRepository.findByKeywords(lowerKeywords);

        return candidates.stream()
                .filter(p -> isValidCategory(p, majorCategory))
                .sorted((p1, p2) -> Long.compare(calculateScore(p2, lowerKeywords), calculateScore(p1, lowerKeywords)))
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    // ... (Giữ nguyên các hàm helper AI, check category, check brand, etc. để code gọn) ...
    // Bạn có thể copy lại phần logic AI và check category từ file cũ nếu cần,
    // nhưng quan trọng nhất là mapToResponse đã được sửa ở trên.

    private boolean isValidCategory(Product p, String aiCategory) {
        if (aiCategory == null || aiCategory.equals("ALL")) return true;
        String content = (p.getCategory().getName() + " " + p.getName()).toLowerCase();
        switch (aiCategory) {
            case "FOOTWEAR": return isShoe(content);
            case "CLOTHING": return isClothing(content);
            case "ACCESSORY": return isAccessory(content);
            default: return true;
        }
    }

    private long calculateScore(Product p, List<String> keywords) {
        long score = 0;
        String productName = p.getName().toLowerCase();
        for (String keyword : keywords) {
            if (keyword.length() > 2 && productName.contains(keyword)) score += 30;
        }
        if (p.getTags() != null) {
            for (String tag : p.getTags()) {
                String lowerTag = tag.toLowerCase();
                for (String keyword : keywords) {
                    if (lowerTag.equals(keyword)) score += 10;
                    else if (lowerTag.contains(keyword)) score += 3;
                }
            }
        }
        return score;
    }

    private void enrichSingleProduct(Long productId, String imageUrl) {
        try {
            List<String> aiTags = aiChat.generateTagsForProduct(imageUrl);
            if (!aiTags.isEmpty()) updateProductTagsInBackGround(productId, aiTags);
        } catch (Exception e) { /* Log */ }
    }

    @Transactional
    protected void updateProductTagsInBackGround(Long productId, List<String> newTags) {
        Product p = productRepository.findById(productId).orElse(null);
        if (p != null) {
            if (p.getTags() == null) p.setTags(new ArrayList<>());
            p.getTags().addAll(newTags);
            List<String> distinctTags = p.getTags().stream().map(String::toLowerCase).distinct().collect(Collectors.toList());
            p.getTags().clear();
            p.getTags().addAll(distinctTags);
            productRepository.save(p);
        }
    }

    @Override
    @Transactional
    public void enrichAllProductsData() {
        // Logic AI enrich all (giữ nguyên từ file cũ)
        List<Product> allProducts = productRepository.findAll();
        for (Product product : allProducts) {
            if (product.getImageUrl() != null && !product.getImageUrl().isEmpty()) {
                new Thread(() -> enrichSingleProduct(product.getProductId(), product.getImageUrl())).start();
            }
        }
    }

    private double cosineSimilarity(double[] vectorA, double[] vectorB) {
        if (vectorA.length != vectorB.length || vectorA.length == 0) return 0.0;
        double dotProduct = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += Math.pow(vectorA[i], 2);
            normB += Math.pow(vectorB[i], 2);
        }
        return (normA == 0 || normB == 0) ? 0.0 : dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductResponseDTO> findProductsByVector(String descriptionFromImage) {
        List<Double> queryVectorList = aiChat.getEmbedding(descriptionFromImage);
        if (queryVectorList.isEmpty()) return new ArrayList<>();
        double[] queryVector = queryVectorList.stream().mapToDouble(d -> d).toArray();

        return productRepository.findAll().stream()
                .filter(p -> p.getEmbeddingArray().length > 0)
                .map(p -> new AbstractMap.SimpleEntry<>(p, cosineSimilarity(queryVector, p.getEmbeddingArray())))
                .filter(entry -> entry.getValue() > 0.6)
                .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()))
                .limit(10)
                .map(entry -> mapToResponse(entry.getKey()))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public ProductResponseDTO getProductByName(String productName) {
        List<Product> products = productRepository.findByKeywords(List.of(productName.toLowerCase()));
        return products.isEmpty() ? null : mapToResponse(products.get(0));
    }

    // Các hàm check category & brand phụ trợ
    private boolean isShoe(String text) { return text.contains("giày") || text.contains("shoe") || text.contains("sneaker"); }
    private boolean isClothing(String text) { return text.contains("áo") || text.contains("shirt") || text.contains("quần"); }
    private boolean isAccessory(String text) { return text.contains("nón") || text.contains("mũ") || text.contains("túi"); }

    @Transactional
    @Override
    public void applyDiscount(Long productId, String discountId) {
        Product product = productRepository.findById(productId).orElseThrow(() -> new RuntimeException("Product not found"));
        Discount discount = discountRepository.findById(UUID.fromString(discountId)).orElseThrow(() -> new RuntimeException("Discount not found"));
        boolean exists = product.getDiscounts().stream().anyMatch(pd -> pd.getDiscount().getDiscountId().equals(discount.getDiscountId()));
        if (exists) throw new RuntimeException("Discount already applied");

        ProductDiscount pd = ProductDiscount.builder().product(product).discount(discount).build();
        product.getDiscounts().add(pd);
        productRepository.save(product);
    }

    public List<ProductResponseDTO> findByCategories(List<String> categories) {
        return getAllProducts().stream()
                .filter(p -> p.getCategoryName() != null && categories.contains(p.getCategoryName()))
                .limit(12)
                .toList();
    }
}