package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.ai.AIChat;
import com.example.FieldFinder.dto.req.ProductRequestDTO;
import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.example.FieldFinder.entity.*;
import com.example.FieldFinder.repository.*;
import com.example.FieldFinder.service.CloudinaryService;
import com.example.FieldFinder.service.ProductService;
import com.example.FieldFinder.specification.ProductSpecification;
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
import java.util.*;
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
        if (product == null)
            return null;
        calculateAndSetUserPrice(product, usedDiscountIds);
        ProductResponseDTO dto = ProductResponseDTO.fromEntity(product);
        dto.setSalePrice(product.getSalePrice());
        dto.setSalePercent(product.getOnSalePercent());
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
                // Truyền tên danh mục vào list theo đúng signature của hàm uploadProductImage
                List<String> categoryNames = List.of(category.getName());

                // Gọi hàm upload của bạn và nhận về Map
                Map<String, Object> uploadResult = cloudinaryService.uploadProductImage(imageFile, categoryNames);

                // Trích xuất URL từ kết quả
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
            new Thread(() -> enrichSingleProduct(product.getProductId(), product.getImageUrl())).start();
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

        return mapToResponse(product, usedDiscountIds);
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

        Specification<Product> spec = Specification.where(ProductSpecification.hasCategoryIds(categoryIds))
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
            new Thread(() -> enrichSingleProduct(product.getProductId(), request.getImageUrl())).start();
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
            System.err.println("⚠️ Cannot release stock. Product ID: " + productId
                    + ", queried size: '" + size + "'"
                    + ", existing sizes in DB: [" + existingSizes + "]");
            // Fallback: nếu chỉ có 1 variant cho sản phẩm này (Freesize/one-size), dùng
            // variant đó
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
        List<Product> candidates = productRepository.findByKeywords(lowerKeywords);

        return candidates.stream()
                .filter(p -> isValidCategory(p, majorCategory))
                .sorted((p1, p2) -> Long.compare(calculateScore(p2, lowerKeywords), calculateScore(p1, lowerKeywords)))
                .map(p -> mapToResponse(p, Collections.emptyList())) // Search ảnh chưa support user specific price để
                // tối ưu tốc độ
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
                new Thread(() -> enrichSingleProduct(product.getProductId(), product.getImageUrl())).start();
            }
        }
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
        List<Double> queryVectorList = aiChat.getEmbedding(descriptionFromImage);
        if (queryVectorList.isEmpty())
            return new ArrayList<>();
        double[] queryVector = queryVectorList.stream().mapToDouble(d -> d).toArray();

        return productRepository.findAll().stream()
                .filter(p -> p.getEmbeddingArray().length > 0)
                .map(p -> new AbstractMap.SimpleEntry<>(p, cosineSimilarity(queryVector, p.getEmbeddingArray())))
                .filter(entry -> entry.getValue() > 0.6)
                .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()))
                .limit(10)
                .map(entry -> mapToResponse(entry.getKey(), Collections.emptyList()))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public ProductResponseDTO getProductByName(String productName) {
        List<Product> products = productRepository.findByKeywords(List.of(productName.toLowerCase()));
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

        List<Discount> allDiscounts = getPublicDiscounts(product);

        if (userId != null) {
            List<UUID> usedDiscountIds = userDiscountRepository.findUsedDiscountIdsByUserId(userId);

            allDiscounts.removeIf(d -> usedDiscountIds.contains(d.getDiscountId()));

            List<UserDiscount> userWallet = userDiscountRepository.findByUser_UserIdAndIsUsedFalse(userId);
            Set<UUID> addedIds = allDiscounts.stream().map(Discount::getDiscountId).collect(Collectors.toSet());

            for (UserDiscount ud : userWallet) {
                Discount d = ud.getDiscount();

                if (!addedIds.contains(d.getDiscountId())
                        && !usedDiscountIds.contains(d.getDiscountId())
                        && isApplicableToProduct(d, product)) {

                    allDiscounts.add(d);
                    addedIds.add(d.getDiscountId());
                }
            }
        }

        product.calculateSalePriceForUser(allDiscounts);

        ProductResponseDTO dto = ProductResponseDTO.fromEntity(product);
        dto.setSalePrice(product.getSalePrice());
        dto.setSalePercent(product.getOnSalePercent());

        return dto;
    }

    private boolean isApplicableToProduct(Discount d, Product p) {
        if (d.getScope() == Discount.DiscountScope.GLOBAL)
            return true;

        if (d.getScope() == Discount.DiscountScope.SPECIFIC_PRODUCT) {
            return d.getApplicableProducts().stream().anyMatch(prod -> prod.getProductId().equals(p.getProductId()));
        }

        if (d.getScope() == Discount.DiscountScope.CATEGORY) {
            if (p.getCategory() == null)
                return false;
            return d.getApplicableCategories().stream()
                    .anyMatch(cat -> cat.getCategoryId().equals(p.getCategory().getCategoryId()));
        }

        return false;
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