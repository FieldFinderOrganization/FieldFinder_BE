package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.ai.AIChat;
import com.example.FieldFinder.dto.req.ProductRequestDTO;
import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.example.FieldFinder.entity.*;
import com.example.FieldFinder.repository.*;
import com.example.FieldFinder.service.CloudinaryService;
import com.example.FieldFinder.service.ProductService;
import com.example.FieldFinder.specification.ProductSpecification;
import com.example.FieldFinder.util.DiscountEligibilityUtil;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import com.example.FieldFinder.Enum.CategoryType;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductVariantRepository productVariantRepository;
    private final DiscountRepository discountRepository;
    private final UserDiscountRepository userDiscountRepository;
    private final AIChat aiChat;
    private final CloudinaryService cloudinaryService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ExecutorService enrichmentExecutor = Executors.newFixedThreadPool(2);

    public ProductServiceImpl(
            ProductRepository productRepository,
            CategoryRepository categoryRepository,
            ProductVariantRepository productVariantRepository,
            DiscountRepository discountRepository,
            UserDiscountRepository userDiscountRepository,
            CloudinaryService cloudinaryService,
            @Lazy AIChat aiChat,
            RedisTemplate<String, Object> redisTemplate) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.productVariantRepository = productVariantRepository;
        this.discountRepository = discountRepository;
        this.userDiscountRepository = userDiscountRepository;
        this.cloudinaryService = cloudinaryService;
        this.aiChat = aiChat;
        this.redisTemplate = redisTemplate;
    }

    private List<Discount> getPublicDiscounts(Product product) {
        List<Discount> publicDiscounts = new ArrayList<>();

        if (product.getDiscounts() != null) {
            publicDiscounts.addAll(product.getDiscounts().stream()
                    .map(ProductDiscount::getDiscount)
                    .toList());
        }

        if (product.getCategory() != null) {
            List<Long> categoryIds = new ArrayList<>();
            Category current = product.getCategory();
            while (current != null) {
                categoryIds.add(current.getCategoryId());
                current = current.getParent();
            }

            if (!categoryIds.isEmpty()) {
                List<Discount> implicitDiscounts = discountRepository.findApplicableDiscountsForProduct(
                        product.getProductId(),
                        categoryIds);

                Set<UUID> existingIds = publicDiscounts.stream()
                        .map(Discount::getDiscountId)
                        .collect(Collectors.toSet());

                for (Discount d : implicitDiscounts) {
                    if (!existingIds.contains(d.getDiscountId())) {
                        publicDiscounts.add(d);
                    }
                }
            }
        }
        return publicDiscounts;
    }

    private void calculateAndSetUserPrice(Product product, List<UUID> usedDiscountIds) {
        // 1. Lấy mã công khai
        List<Discount> allDiscounts = getPublicDiscounts(product);

        // 2. Lọc mã đã dùng
        if (usedDiscountIds != null && !usedDiscountIds.isEmpty()) {
            allDiscounts.removeIf(d -> usedDiscountIds.contains(d.getDiscountId()));
        }

        // 3. Tính giá bằng hàm của Entity
        product.calculateSalePriceForUser(allDiscounts);
    }

    private Set<Long> getKeywordExpandedIds(String keyword) {
        Set<Long> ids = new HashSet<>();
        List<Category> matching = categoryRepository.findByNameContainingIgnoreCase(keyword);
        for (Category cat : matching) {
            ids.addAll(getAllDescendantIds(cat.getCategoryId()));
        }
        return ids;
    }

    private Set<Long> getAllDescendantIds(Long categoryId) {
        Set<Long> ids = new HashSet<>();
        ids.add(categoryId);
        Queue<Long> queue = new LinkedList<>();
        queue.add(categoryId);
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            List<Category> children = categoryRepository.findByParent_CategoryId(current);
            for (Category child : children) {
                ids.add(child.getCategoryId());
                queue.add(child.getCategoryId());
            }
        }
        return ids;
    }

    private ProductResponseDTO mapToResponse(Product product, List<UUID> usedDiscountIds) {
        return mapToResponse(product, usedDiscountIds, null);
    }

    private ProductResponseDTO mapToResponse(Product product, List<UUID> usedDiscountIds, UUID userId) {
        if (product == null) return null;

        List<Discount> allDiscounts = getPublicDiscounts(product);
        if (usedDiscountIds != null && !usedDiscountIds.isEmpty()) {
            allDiscounts.removeIf(d -> usedDiscountIds.contains(d.getDiscountId()));
        }

        List<String> walletDiscountCodes = new ArrayList<>();
        List<String> availableGlobalCodes = new ArrayList<>();

        if (userId != null) {
            // findWalletByUserId JOIN FETCHes applicableCategories + applicableProducts
            List<UserDiscount> userWallet = userDiscountRepository.findWalletByUserId(userId)
                    .stream().filter(ud -> !ud.isUsed()).collect(Collectors.toList());
            Set<UUID> addedIds = allDiscounts.stream().map(Discount::getDiscountId).collect(Collectors.toSet());

            for (UserDiscount ud : userWallet) {
                Discount d = ud.getDiscount();
                if (usedDiscountIds != null && usedDiscountIds.contains(d.getDiscountId())) continue;
                if (!DiscountEligibilityUtil.isEligibleForProductPreview(d, product)) continue;

                // Add vào allDiscounts để tham gia tính salePrice (nếu chưa có)
                if (!addedIds.contains(d.getDiscountId())
                        && d.getScope() != Discount.DiscountScope.GLOBAL) {
                    allDiscounts.add(d);
                    addedIds.add(d.getDiscountId());
                }

                if (d.getScope() == Discount.DiscountScope.GLOBAL) {
                    if (!availableGlobalCodes.contains(d.getCode())) {
                        availableGlobalCodes.add(d.getCode());
                    }
                } else {
                    if (!walletDiscountCodes.contains(d.getCode())) {
                        walletDiscountCodes.add(d.getCode());
                    }
                }
            }
        }

        product.calculateSalePriceForUser(allDiscounts);

        ProductResponseDTO dto = ProductResponseDTO.fromEntity(product);
        dto.setSalePrice(product.getSalePrice());
        dto.setSalePercent(product.getOnSalePercent());
        if (!walletDiscountCodes.isEmpty()) dto.setAppliedDiscountCodes(walletDiscountCodes);
        if (!availableGlobalCodes.isEmpty()) dto.setAvailableGlobalCodes(availableGlobalCodes);
        return dto;
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "product_detail", allEntries = true),
            @CacheEvict(value = "top_selling", allEntries = true),
            @CacheEvict(value = "products_category", allEntries = true)
    })
    public ProductResponseDTO createProduct(ProductRequestDTO request, MultipartFile imageFile) {
        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new RuntimeException("Category not found!"));

        // 1. Upload ảnh lên Cloudinary lấy URL
        String imageUrl = request.getImageUrl();
        if (imageFile != null && !imageFile.isEmpty()) {
            try {
                List<String> categoryNames = List.of(category.getName());

                Map<String, Object> uploadResult = cloudinaryService.uploadProductImage(imageFile, categoryNames);

                imageUrl = (String) uploadResult.get("url");

            } catch (Exception e) {
                throw new RuntimeException("Lỗi khi upload ảnh lên Cloudinary", e);
            }
        }

        // 2. Lưu Product với imageUrl vừa lấy được
        Product product = Product.builder()
                .category(category)
                .name(request.getName())
                .description(request.getDescription())
                .price(request.getPrice())
                .imageUrl(imageUrl)
                .brand(request.getBrand())
                .sex(request.getSex())
                .tags(request.getTags() != null ? request.getTags() : new HashSet<>())
                .build();

        productRepository.save(product);

        if (request.getVariants() != null) {
            List<ProductVariant> variants = request.getVariants().stream().map(v -> ProductVariant.builder()
                    .product(product)
                    .size(v.getSize())
                    .stockQuantity(v.getQuantity())
                    .lockedQuantity(0)
                    .soldQuantity(0)
                    .build()).collect(Collectors.toList());

            productVariantRepository.saveAll(variants);
            product.setVariants(variants);
        }

        // 3. Gọi tiến trình ngầm AI Enrich dựa trên URL đã có
        if (product.getImageUrl() != null && !product.getImageUrl().isEmpty()) {
            enrichmentExecutor.submit(() -> enrichSingleProduct(product.getProductId(), product.getImageUrl()));
        }

        return mapToResponse(product, Collections.emptyList());
    }

    @Override
    @Transactional(readOnly = true)
    public ProductResponseDTO getProductById(Long id, UUID userId) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found!"));

        List<UUID> usedDiscountIds = (userId != null)
                ? userDiscountRepository.findUsedDiscountIdsByUserId(userId)
                : Collections.emptyList();

        // Pass userId để wallet lookup → set appliedDiscountCodes & availableGlobalCodes
        return mapToResponse(product, usedDiscountIds, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductResponseDTO> getAllProducts(Pageable pageable, Long categoryId, Set<String> genders, String brand, UUID userId) {
        List<UUID> usedDiscountIds = (userId != null)
                ? userDiscountRepository.findUsedDiscountIdsByUserId(userId)
                : Collections.emptyList();

        Set<Long> categoryIds = null;
        String effectiveBrand = brand;

        if (categoryId != null) {
            Category selectedCategory = categoryRepository.findById(categoryId)
                    .orElseThrow(() -> new RuntimeException("Category not found"));
            if (selectedCategory.getCategoryType() == CategoryType.BRAND) {
                effectiveBrand = selectedCategory.getName();
            } else if (selectedCategory.getCategoryType() == CategoryType.SUPER_CATEGORY) {
                categoryIds = getKeywordExpandedIds(selectedCategory.getName());
            } else {
                categoryIds = getAllDescendantIds(categoryId);
            }
        }

        Specification<Product> spec = Specification.<Product>unrestricted()
                .and(ProductSpecification.hasCategoryIds(categoryIds))
                .and(ProductSpecification.hasSex(genders))
                .and(ProductSpecification.hasBrand(effectiveBrand));

        Page<Product> products = productRepository.findAll(spec, pageable);

        List<ProductResponseDTO> dtos = products.getContent()
                .stream()
                .map(p -> mapToResponse(p, usedDiscountIds))
                .toList();

        return new PageImpl<>(dtos, pageable, products.getTotalElements());
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "product_detail", allEntries = true),
            @CacheEvict(value = "top_selling", allEntries = true),
            @CacheEvict(value = "products_category", allEntries = true)
    })
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
            if (product.getTags() == null)
                product.setTags(new HashSet<>());
            else
                product.getTags().clear();
            product.getTags().addAll(request.getTags());
        }

        if (request.getVariants() != null) {
            if (product.getVariants() == null)
                product.setVariants(new ArrayList<>());

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
            enrichmentExecutor.submit(() -> enrichSingleProduct(product.getProductId(), request.getImageUrl()));
        }

        evictProductDetailCache(id);

        return mapToResponse(product, Collections.emptyList());
    }

    @Override
    @Caching(evict = {
            @CacheEvict(value = "product_detail", allEntries = true),
            @CacheEvict(value = "top_selling", allEntries = true)
    })
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
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Caching(evict = {
            @CacheEvict(value = "product_detail", allEntries = true),
            @CacheEvict(value = "top_selling", allEntries = true),
            @CacheEvict(value = "products_category", allEntries = true)
    })
    public void commitStock(Long productId, String size, int quantity) {
        Optional<ProductVariant> optionalVariant = productVariantRepository.findByProduct_ProductIdAndSize(productId,
                size);
        if (optionalVariant.isEmpty()) {
            System.err.println("⚠️ Warning: Cannot commit stock. Variant not found for Product ID: " + productId
                    + ", Size: " + size);
            return;
        }

        ProductVariant variant = optionalVariant.get();
        variant.setStockQuantity(variant.getStockQuantity() - quantity);
        variant.setLockedQuantity(variant.getLockedQuantity() - quantity);
        variant.setSoldQuantity(variant.getSoldQuantity() + quantity);
        productVariantRepository.save(variant);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Caching(evict = {
            @CacheEvict(value = "product_detail", allEntries = true),
            @CacheEvict(value = "top_selling", allEntries = true),
            @CacheEvict(value = "products_category", allEntries = true)
    })
    public void releaseStock(Long productId, String size, int quantity) {
        Optional<ProductVariant> optionalVariant = productVariantRepository.findByProduct_ProductIdAndSize(productId,
                size);
        if (optionalVariant.isEmpty()) {
            // Log tất cả variant hiện có để chẩn đoán size mismatch
            List<ProductVariant> allVariants = productVariantRepository.findAllByProduct_ProductId(productId);
            String existingSizes = allVariants.stream()
                    .map(v -> "'" + v.getSize() + "'")
                    .collect(java.util.stream.Collectors.joining(", "));
            System.err.println("Cannot release stock. Product ID: " + productId
                    + ", queried size: '" + size + "'"
                    + ", existing sizes in DB: [" + existingSizes + "]");
            if (allVariants.size() == 1) {
                ProductVariant variant = allVariants.get(0);
                System.err.println(
                        "⚠️ Fallback: releasing stock for single variant with size='" + variant.getSize() + "'");
                int newLocked = variant.getLockedQuantity() - quantity;
                variant.setLockedQuantity(Math.max(newLocked, 0));
                productVariantRepository.save(variant);
            }
            return;
        }

        ProductVariant variant = optionalVariant.get();
        int newLocked = variant.getLockedQuantity() - quantity;
        variant.setLockedQuantity(Math.max(newLocked, 0));
        productVariantRepository.save(variant);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "top_selling", key = "'top5_' + (#userId != null ? #userId.toString() : 'anon')")
    public List<ProductResponseDTO> getTopSellingProducts(int limit, UUID userId) {
        Pageable pageable = PageRequest.of(0, limit);

        List<UUID> usedDiscountIds = (userId != null)
                ? userDiscountRepository.findUsedDiscountIdsByUserId(userId)
                : Collections.emptyList();

        return productRepository.findTopSellingProducts(pageable)
                .stream()
                .map(p -> mapToResponse(p, usedDiscountIds))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductResponseDTO> findProductsByCategories(List<String> categories, UUID userId) {
        List<UUID> usedDiscountIds = (userId != null)
                ? userDiscountRepository.findUsedDiscountIdsByUserId(userId)
                : Collections.emptyList();

        return productRepository.findAll().stream()
                .filter(p -> p.getCategory() != null && categories.contains(p.getCategory().getName()))
                .limit(12)
                .map(p -> mapToResponse(p, usedDiscountIds))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductResponseDTO> findProductsByImage(List<String> keywords, String majorCategory) {
        if (keywords == null || keywords.isEmpty())
            return new ArrayList<>();

        List<String> lowerKeywords = keywords.stream().map(String::toLowerCase).collect(Collectors.toList());
        String firstKeyword = lowerKeywords.get(0);
        List<Product> candidates = productRepository.findByKeywords(lowerKeywords, firstKeyword);

        return candidates.stream()
                .filter(p -> isValidCategory(p, majorCategory))
                .sorted((p1, p2) -> Long.compare(calculateScore(p2, lowerKeywords), calculateScore(p1, lowerKeywords)))
                .map(p -> mapToResponse(p, Collections.emptyList())) // Search ảnh chưa support user specific price để
                .collect(Collectors.toList());
    }

    private boolean isValidCategory(Product p, String aiCategory) {
        if (aiCategory == null || aiCategory.equals("ALL"))
            return true;
        if (p.getCategory() == null)
            return false;
        String content = (p.getCategory().getName() + " " + p.getName()).toLowerCase();
        return switch (aiCategory) {
            case "FOOTWEAR" -> isShoe(content);
            case "CLOTHING" -> isClothing(content);
            case "ACCESSORY" -> isAccessory(content);
            default -> true;
        };
    }

    private long calculateScore(Product p, List<String> keywords) {
        long score = 0;
        String productName = p.getName().toLowerCase();
        for (String keyword : keywords) {
            if (keyword.length() > 2 && productName.contains(keyword))
                score += 30;
        }
        if (p.getTags() != null) {
            for (String tag : p.getTags()) {
                String lowerTag = tag.toLowerCase();
                for (String keyword : keywords) {
                    if (lowerTag.equals(keyword))
                        score += 10;
                    else if (lowerTag.contains(keyword))
                        score += 3;
                }
            }
        }
        return score;
    }

    private void enrichSingleProduct(Long productId, String imageUrl) {
        try {
            List<String> aiTags = aiChat.generateTagsForProduct(imageUrl);
            if (!aiTags.isEmpty())
                updateProductTagsInBackGround(productId, aiTags);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Transactional
    protected void updateProductTagsInBackGround(Long productId, List<String> newTags) {
        Product p = productRepository.findById(productId).orElse(null);
        if (p != null) {
            if (p.getTags() == null)
                p.setTags(new HashSet<>());
            p.getTags().addAll(newTags);
            List<String> distinctTags = p.getTags().stream().map(String::toLowerCase).distinct()
                    .toList();
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
                // Chỉ chạy nếu chưa có tags (hoặc bạn có thể thêm logic kiểm tra khác)
                if (product.getTags() == null || product.getTags().isEmpty()) {
                    enrichmentExecutor.submit(() -> enrichSingleProduct(product.getProductId(), product.getImageUrl()));
                }
            }
        }
    }

    @PreDestroy
    public void shutdownExecutor() {
        enrichmentExecutor.shutdown();
    }

    private double cosineSimilarity(double[] vectorA, double[] vectorB) {
        if (vectorA.length != vectorB.length || vectorA.length == 0)
            return 0.0;
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
        return findProductsByVectorWithScores(descriptionFromImage).stream()
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map.Entry<ProductResponseDTO, Double>> findProductsByVectorWithScores(String description) {
        List<Double> queryVectorList = aiChat.getEmbedding(description);
        if (queryVectorList.isEmpty())
            return new ArrayList<>();
        double[] queryVector = queryVectorList.stream().mapToDouble(d -> d).toArray();

        return productRepository.findAll().stream()
                .filter(p -> p.getEmbeddingArray().length > 0)
                .map(p -> new AbstractMap.SimpleEntry<>(p, cosineSimilarity(queryVector, p.getEmbeddingArray())))
                .filter(entry -> entry.getValue() > 0.6)
                .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()))
                .limit(10)
                .map(entry -> (Map.Entry<ProductResponseDTO, Double>) new AbstractMap.SimpleEntry<>(
                        mapToResponse(entry.getKey(), Collections.emptyList()),
                        Math.round(entry.getValue() * 10000.0) / 10000.0)) // Round to 4 decimal places
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public ProductResponseDTO getProductByName(String productName) {
        String keyword = productName.toLowerCase();
        List<Product> products = productRepository.findByKeywords(List.of(keyword), keyword);
        return products.isEmpty() ? null : mapToResponse(products.getFirst(), Collections.emptyList());
    }

    private boolean isShoe(String text) {
        return text.contains("giày") || text.contains("shoe") || text.contains("sneaker");
    }

    private boolean isClothing(String text) {
        return text.contains("áo") || text.contains("shirt") || text.contains("quần");
    }

    private boolean isAccessory(String text) {
        return text.contains("nón") || text.contains("mũ") || text.contains("túi");
    }

    @Transactional
    @Override
    public void applyDiscount(Long productId, String discountId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new RuntimeException("Product not found"));
        Discount discount = discountRepository.findById(UUID.fromString(discountId))
                .orElseThrow(() -> new RuntimeException("Discount not found"));
        boolean exists = product.getDiscounts().stream()
                .anyMatch(pd -> pd.getDiscount().getDiscountId().equals(discount.getDiscountId()));
        if (exists)
            throw new RuntimeException("Discount already applied");

        ProductDiscount pd = ProductDiscount.builder().product(product).discount(discount).build();
        product.getDiscounts().add(pd);
        productRepository.save(product);

        evictProductDetailCache(productId);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "product_detail", key = "#productId + '_' + (#userId != null ? #userId.toString() : 'anon')")
    public ProductResponseDTO getProductDetail(Long productId, UUID userId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new RuntimeException("Sản phẩm không tồn tại!"));

        List<UUID> usedDiscountIds = (userId != null)
                ? userDiscountRepository.findUsedDiscountIdsByUserId(userId)
                : Collections.emptyList();

        return mapToResponse(product, usedDiscountIds, userId);
    }

    private void evictProductDetailCache(Long productId) {
        String pattern = "product_detail::" + productId + "_*";

        java.util.Set<String> keys = redisTemplate.keys(pattern);

        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
            System.out.println("Đã xóa " + keys.size() + " bản ghi cache cho sản phẩm ID: " + productId);
        }
    }
}