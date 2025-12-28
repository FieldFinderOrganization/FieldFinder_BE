package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.ai.AIChat;
import com.example.FieldFinder.dto.req.ProductRequestDTO;
import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.example.FieldFinder.entity.*;
import com.example.FieldFinder.repository.*;
import com.example.FieldFinder.service.ProductService;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductVariantRepository productVariantRepository;
    private final DiscountRepository discountRepository;
    private final UserDiscountRepository userDiscountRepository; // Inject thêm
    private final AIChat aiChat;

    public ProductServiceImpl(
            ProductRepository productRepository,
            CategoryRepository categoryRepository,
            ProductVariantRepository productVariantRepository,
            DiscountRepository discountRepository,
            UserDiscountRepository userDiscountRepository,
            @Lazy AIChat aiChat
    ) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.productVariantRepository = productVariantRepository;
        this.discountRepository = discountRepository;
        this.userDiscountRepository = userDiscountRepository;
        this.aiChat = aiChat;
    }

    // --- LOGIC TÍNH GIÁ TRUNG TÂM ---

    private ProductResponseDTO mapToResponse(Product product, UUID userId) {
        if (product == null) return null;

        // 1. Tính toán giá (Implicit Discount + User Discount check)
        calculateAndSetUserPrice(product, userId);

        // 2. Map sang DTO (Lúc này Product đã có SalePrice đúng trong @Transient field)
        ProductResponseDTO dto = ProductResponseDTO.fromEntity(product);

        // Đảm bảo lấy giá từ field transient mà chúng ta vừa tính
        dto.setSalePrice(product.getSalePrice());
        dto.setSalePercent(product.getOnSalePercent());

        return dto;
    }

    /**
     * Hàm này thực hiện 2 việc:
     * 1. Tìm các mã giảm giá ngầm (Implicit) dựa trên Category.
     * 2. Tính giá cuối cùng dựa trên User (loại bỏ mã user đã dùng).
     * 3. Set kết quả vào field @Transient của Product.
     */
    private void calculateAndSetUserPrice(Product product, UUID userId) {
        // A. Lấy danh sách ID mã giảm giá user đã dùng (Nếu có user)
        List<UUID> usedDiscountIds = (userId != null)
                ? userDiscountRepository.findUsedDiscountIdsByUserId(userId)
                : new ArrayList<>();

        // B. Tổng hợp tất cả Discount khả dụng (Explicit + Implicit)
        List<Discount> allApplicableDiscounts = new ArrayList<>();

        // B1. Lấy mã gắn trực tiếp (Explicit)
        if (product.getDiscounts() != null) {
            allApplicableDiscounts.addAll(product.getDiscounts().stream()
                    .map(ProductDiscount::getDiscount)
                    .toList());
        }

        // B2. Lấy mã theo danh mục (Implicit)
        List<Long> categoryIds = new ArrayList<>();
        Category current = product.getCategory();
        while (current != null) {
            categoryIds.add(current.getCategoryId());
            current = current.getParent();
        }

        List<Discount> implicitDiscounts = discountRepository.findApplicableDiscountsForProduct(
                product.getProductId(),
                categoryIds
        );

        // Merge Implicit vào (tránh trùng lặp)
        Set<UUID> existingIds = allApplicableDiscounts.stream()
                .map(Discount::getDiscountId)
                .collect(Collectors.toSet());

        for (Discount d : implicitDiscounts) {
            if (!existingIds.contains(d.getDiscountId())) {
                allApplicableDiscounts.add(d);
            }
        }

        // C. Tính giá
        double currentPrice = product.getPrice();
        LocalDate now = LocalDate.now();

        for (Discount d : allApplicableDiscounts) {
            if (d == null) continue;

            // 1. Check logic cơ bản
            boolean isActive = d.getStatus() == Discount.DiscountStatus.ACTIVE;
            boolean isStarted = d.getStartDate() == null || !now.isBefore(d.getStartDate());
            boolean isNotExpired = d.getEndDate() == null || !now.isAfter(d.getEndDate());

            // 2. CHECK QUAN TRỌNG: User đã dùng chưa?
            boolean isNotUsedByUser = !usedDiscountIds.contains(d.getDiscountId());

            if (isActive && isStarted && isNotExpired && isNotUsedByUser) {
                double reduction = 0.0;
                double value = d.getValue() != null ? d.getValue().doubleValue() : 0.0;

                if (d.getDiscountType() == Discount.DiscountType.FIXED_AMOUNT) {
                    reduction = value;
                } else {
                    // Percentage
                    reduction = currentPrice * (value / 100.0);
                }

                if (d.getMaxDiscountAmount() != null) {
                    double maxLimit = d.getMaxDiscountAmount().doubleValue();
                    if (maxLimit > 0) reduction = Math.min(reduction, maxLimit);
                }

                currentPrice -= reduction;
            }
        }

        currentPrice = Math.max(0, currentPrice);

        // D. Set ngược lại vào Entity để DTO Mapper sử dụng
        product.setSalePrice(currentPrice);

        // Tính % giảm giá để hiển thị tag
        if (product.getPrice() > 0) {
            double totalReduction = product.getPrice() - currentPrice;
            int percent = (totalReduction > 0)
                    ? (int) Math.round((totalReduction / product.getPrice()) * 100)
                    : 0;
            product.setOnSalePercent(percent);
        } else {
            product.setOnSalePercent(0);
        }
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

        return mapToResponse(product, null); // Create xong chưa cần tính giá user cụ thể
    }

    @Override
    @Transactional(readOnly = true)
    public ProductResponseDTO getProductById(Long id, UUID userId) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found!"));
        return mapToResponse(product, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductResponseDTO> getAllProducts(UUID userId) {
        return productRepository.findAll()
                .stream()
                .map(p -> mapToResponse(p, userId))
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
            // Logic update variants giữ nguyên như cũ
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

        return getProductById(id, null);
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
    public List<ProductResponseDTO> getTopSellingProducts(int limit, UUID userId) {
        Pageable pageable = PageRequest.of(0, limit);
        return productRepository.findTopSellingProducts(pageable)
                .stream()
                .map(p -> mapToResponse(p, userId))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductResponseDTO> findProductsByCategories(List<String> categories, UUID userId) {
        // Tối ưu: Nên query DB thay vì getAll rồi filter, nhưng tạm thời giữ logic cũ
        return productRepository.findAll().stream() // Có thể thay bằng findByCategoryNameIn...
                .filter(p -> p.getCategory() != null && categories.contains(p.getCategory().getName()))
                .limit(12)
                .map(p -> mapToResponse(p, userId))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductResponseDTO> findProductsByImage(List<String> keywords, String majorCategory) {
        if (keywords == null || keywords.isEmpty()) return new ArrayList<>();

        List<String> lowerKeywords = keywords.stream().map(String::toLowerCase).collect(Collectors.toList());
        List<Product> candidates = productRepository.findByKeywords(lowerKeywords);

        // Với chức năng search ảnh, tạm thời chưa áp dụng user specific price (userId = null)
        return candidates.stream()
                .filter(p -> isValidCategory(p, majorCategory))
                .sorted((p1, p2) -> Long.compare(calculateScore(p2, lowerKeywords), calculateScore(p1, lowerKeywords)))
                .map(p -> mapToResponse(p, null))
                .collect(Collectors.toList());
    }

    // ... Các hàm private helper (isValidCategory, calculateScore, enrichSingleProduct, etc.) GIỮ NGUYÊN ...
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
                .map(entry -> mapToResponse(entry.getKey(), null))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public ProductResponseDTO getProductByName(String productName) {
        List<Product> products = productRepository.findByKeywords(List.of(productName.toLowerCase()));
        return products.isEmpty() ? null : mapToResponse(products.get(0), null);
    }

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
}