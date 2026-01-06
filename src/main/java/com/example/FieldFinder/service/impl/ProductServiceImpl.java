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

    // --- HELPER: Lấy danh sách giảm giá công khai (Explicit + Implicit) ---
    private List<Discount> getPublicDiscounts(Product product) {
        List<Discount> publicDiscounts = new ArrayList<>();

        // 1. Explicit (Gắn trực tiếp vào Product)
        if (product.getDiscounts() != null) {
            publicDiscounts.addAll(product.getDiscounts().stream()
                    .map(ProductDiscount::getDiscount)
                    .toList());
        }

        // 2. Implicit (Theo Category)
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

        // Merge vào list chính (tránh trùng lặp ID)
        Set<UUID> existingIds = publicDiscounts.stream()
                .map(Discount::getDiscountId)
                .collect(Collectors.toSet());

        for (Discount d : implicitDiscounts) {
            if (!existingIds.contains(d.getDiscountId())) {
                publicDiscounts.add(d);
            }
        }
        return publicDiscounts;
    }

    // --- HELPER: Logic tính giá (cho danh sách products) ---
    private void calculateAndSetUserPrice(Product product, UUID userId) {
        // 1. Lấy mã công khai
        List<Discount> allDiscounts = getPublicDiscounts(product);

        // 2. Lọc mã đã dùng (nếu có user)
        if (userId != null) {
            List<UUID> usedDiscountIds = userDiscountRepository.findUsedDiscountIdsByUserId(userId);
            allDiscounts.removeIf(d -> usedDiscountIds.contains(d.getDiscountId()));
        }

        // 3. Tính giá bằng hàm của Entity
        product.calculateSalePriceForUser(allDiscounts);
    }

    private ProductResponseDTO mapToResponse(Product product, UUID userId) {
        if (product == null) return null;
        calculateAndSetUserPrice(product, userId);
        ProductResponseDTO dto = ProductResponseDTO.fromEntity(product);
        dto.setSalePrice(product.getSalePrice());
        dto.setSalePercent(product.getOnSalePercent());
        return dto;
    }

    // --- MAIN METHODS ---

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

        return mapToResponse(product, null);
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
        return productRepository.findAll().stream()
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

        return candidates.stream()
                .filter(p -> isValidCategory(p, majorCategory))
                .sorted((p1, p2) -> Long.compare(calculateScore(p2, lowerKeywords), calculateScore(p1, lowerKeywords)))
                .map(p -> mapToResponse(p, null))
                .collect(Collectors.toList());
    }

    private boolean isValidCategory(Product p, String aiCategory) {
        if (aiCategory == null || aiCategory.equals("ALL")) return true;
        if (p.getCategory() == null) return false;
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
        } catch (Exception e) {
            e.printStackTrace();
        }
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

    // --- LOGIC CHI TIẾT SẢN PHẨM (Bao gồm Private Discount) ---

    @Override
    @Transactional(readOnly = true)
    public ProductResponseDTO getProductDetail(Long productId, UUID userId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new RuntimeException("Sản phẩm không tồn tại!"));

        // 1. Lấy danh sách giảm giá công khai (Dùng lại helper)
        List<Discount> allDiscounts = getPublicDiscounts(product);
        Set<UUID> addedIds = allDiscounts.stream().map(Discount::getDiscountId).collect(Collectors.toSet());

        // 2. Lấy danh sách giảm giá RIÊNG trong ví User (Logic riêng cho trang Detail)
        if (userId != null) {
            List<UserDiscount> userWallet = userDiscountRepository.findByUser_UserIdAndIsUsedFalse(userId);
            for (UserDiscount ud : userWallet) {
                Discount d = ud.getDiscount();
                // Chỉ thêm nếu chưa có và áp dụng được cho sản phẩm
                if (!addedIds.contains(d.getDiscountId()) && isApplicableToProduct(d, product)) {
                    allDiscounts.add(d);
                    addedIds.add(d.getDiscountId());
                }
            }
        }

        // 3. Lọc bỏ các mã đã sử dụng (Check lịch sử dùng chung)
        if (userId != null) {
            List<UUID> usedIds = userDiscountRepository.findUsedDiscountIdsByUserId(userId);
            allDiscounts.removeIf(d -> usedIds.contains(d.getDiscountId()));
        }

        // 4. Tính giá
        product.calculateSalePriceForUser(allDiscounts);

        // 5. Map DTO
        ProductResponseDTO dto = ProductResponseDTO.fromEntity(product);
        dto.setSalePrice(product.getSalePrice());
        dto.setSalePercent(product.getOnSalePercent());

        return dto;
    }

    // Hàm kiểm tra scope của mã giảm giá (Có thể mở rộng logic sau này)
    private boolean isApplicableToProduct(Discount d, Product p) {
        // Ví dụ logic đơn giản: Nếu Discount có categoryId thì phải khớp
        // Hiện tại tạm thời trả về true vì chưa rõ cấu trúc entity Discount chi tiết
        // Bạn có thể thêm logic check category/product specific ở đây
        return true;
    }
}