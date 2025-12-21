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
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.FieldFinder.entity.ProductDiscount;

import java.util.*;

import java.time.LocalDate;
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
            new Thread(() -> {
                enrichSingleProduct(product.getProductId(), product.getImageUrl());
            }).start();
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

        String oldImageUrl = product.getImageUrl();

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
                    existingVariant.setStockQuantity(reqVariant.getQuantity());

                } else {
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

        boolean imageChanged = !request.getImageUrl().equals(oldImageUrl);

        productRepository.saveAndFlush(product);

        if (imageChanged && request.getImageUrl() != null) {
            new Thread(() -> {
                enrichSingleProduct(product.getProductId(), request.getImageUrl());
            }).start();
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
    public List<ProductResponseDTO> getTopSellingProducts(int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        return productRepository.findTopSellingProducts(pageable)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<ProductResponseDTO> findProductsByImage(List<String> keywords, String majorCategory) {
        if (keywords == null || keywords.isEmpty()) return new ArrayList<>();

        List<String> lowerKeywords = keywords.stream()
                .map(String::toLowerCase)
                .collect(Collectors.toList());

        List<Product> candidates = productRepository.findByKeywords(lowerKeywords);

        return candidates.stream()
                .filter(p -> isValidCategory(p, majorCategory))
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
        String productName = p.getName().toLowerCase();
        String categoryName = p.getCategory().getName().toLowerCase();

        for (String keyword : keywords) {
            if (keyword.length() > 2 && productName.contains(keyword)) {
                score += 30;
            }
        }

        if (p.getTags() != null) {
            for (String tag : p.getTags()) {
                String lowerTag = tag.toLowerCase();

                for (String keyword : keywords) {
                    if (lowerTag.equals(keyword)) {
                        if (isBrand(keyword)) score += 15;
                        else if (isColor(keyword)) score += 10;
                        else score += 5;
                    }
                    else if (lowerTag.contains(keyword) || keyword.contains(lowerTag)) {
                        score += 3;
                    }
                }
            }
        }

        return score;
    }

    private void enrichSingleProduct(Long productId, String imageUrl) {
        try {
            System.out.println("🤖 AI đang phân tích tags cho sản phẩm ID: " + productId);

            List<String> aiTags = aiChat.generateTagsForProduct(imageUrl);

            if (!aiTags.isEmpty()) {
                updateProductTagsInBackGround(productId, aiTags);
            }
        } catch (Exception e) {
            System.err.println("Lỗi AI Enrichment cho ID " + productId + ": " + e.getMessage());
        }
    }

    @Transactional
    protected void updateProductTagsInBackGround(Long productId, List<String> newTags) {
        Product p = productRepository.findById(productId).orElse(null);
        if (p != null) {
            if (p.getTags() == null) p.setTags(new ArrayList<>());
            p.getTags().addAll(newTags);

            List<String> distinctTags = p.getTags().stream()
                    .map(String::toLowerCase)
                    .distinct()
                    .collect(Collectors.toList());

            p.getTags().clear();
            p.getTags().addAll(distinctTags);

            productRepository.save(p);
            System.out.println("✅ Đã cập nhật xong tags cho ID " + productId);
        }
    }

    @Override
    @Transactional
    public void enrichAllProductsData() {
        List<Product> allProducts = productRepository.findAll();
        System.out.println("Bắt đầu AI hóa dữ liệu cho " + allProducts.size() + " sản phẩm...");

        for (Product product : allProducts) {
            try {
                if (product.getImageUrl() != null && !product.getImageUrl().isEmpty()) {
                    System.out.println("Đang xử lý sản phẩm ID: " + product.getProductId() + " - " + product.getName());

                    List<String> aiGeneratedTags = aiChat.generateTagsForProduct(product.getImageUrl());

                    if (!aiGeneratedTags.isEmpty()) {
                        if (product.getTags() == null) product.setTags(new ArrayList<>());

                        product.getTags().addAll(aiGeneratedTags);

                        List<String> distinctTags = product.getTags().stream()
                                .map(String::toLowerCase)
                                .distinct()
                                .collect(Collectors.toList());

                        product.getTags().clear();
                        product.getTags().addAll(distinctTags);

                        String fullDescription = product.getName() + " " + String.join(" ", distinctTags);

                        List<Double> vector = aiChat.getEmbedding(fullDescription);

                        product.setEmbedding(vector.toString());
                        productRepository.save(product);
                        System.out.println("-> Đã cập nhật " + distinctTags.size() + " tags.");
                    }
                }
            } catch (Exception e) {
                System.err.println("Lỗi khi xử lý sản phẩm " + product.getProductId() + ": " + e.getMessage());
            }


        }
        System.out.println("Hoàn tất quá trình làm giàu dữ liệu!");
    }

    private double cosineSimilarity(double[] vectorA, double[] vectorB) {
        if (vectorA.length != vectorB.length || vectorA.length == 0) return 0.0;

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += Math.pow(vectorA[i], 2);
            normB += Math.pow(vectorB[i], 2);
        }

        if (normA == 0 || normB == 0) return 0.0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    @Override
    public List<ProductResponseDTO> findProductsByVector(String descriptionFromImage) {
        List<Double> queryVectorList = aiChat.getEmbedding(descriptionFromImage);
        if (queryVectorList.isEmpty()) return new ArrayList<>();

        double[] queryVector = queryVectorList.stream().mapToDouble(d -> d).toArray();

        List<Product> allProducts = productRepository.findAll();

        return allProducts.stream()
                .filter(p -> p.getEmbeddingArray().length > 0)
                .map(p -> {
                    double similarity = cosineSimilarity(queryVector, p.getEmbeddingArray());
                    return new AbstractMap.SimpleEntry<>(p, similarity);
                })
                .filter(entry -> entry.getValue() > 0.6) // 🔥 Lọc ngưỡng: Chỉ lấy giống > 60%
                .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue())) // Điểm cao xếp trước
                .limit(10) // Lấy top 10
                .map(entry -> mapToResponse(entry.getKey()))
                .collect(Collectors.toList());
    }

    @Override
    public ProductResponseDTO getProductByName(String productName) {

        List<Product> products = productRepository.findByKeywords(List.of(productName.toLowerCase()));

        if (products.isEmpty()) return null;

        return mapToResponse(products.get(0));
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
        Integer salePercent = resolveActiveDiscountPercent(product);
        Double finalPrice = calculateSalePrice(product.getPrice(), salePercent);

        return ProductResponseDTO.builder()
                .id(product.getProductId())
                .name(product.getName())
                .description(product.getDescription())
                .categoryName(product.getCategory().getName())
                .price(product.getPrice())          // giá gốc
                .salePercent(salePercent)           // % sale
                .salePrice(finalPrice)              // giá sau sale
                .imageUrl(product.getImageUrl())
                .brand(product.getBrand())
                .sex(product.getSex())
                .tags(product.getTags())
                .variants(variantDTOs)
                .totalSold(product.getTotalSold())
                .build();
    }
    private Integer resolveActiveDiscountPercent(Product product) {
        if (product.getDiscounts() == null || product.getDiscounts().isEmpty()) {
            return null;
        }

        LocalDate today = LocalDate.now();

        return product.getDiscounts().stream()
                .map(ProductDiscount::getDiscount)
                .filter(d ->
                        d.getStatus() == Discount.DiscountStatus.ACTIVE &&
                                !today.isBefore(d.getStartDate()) &&
                                !today.isAfter(d.getEndDate())
                )
                .map(Discount::getPercentage)
                .max(Comparator.naturalOrder()) // ✅ FIX lỗi compareTo ambiguous
                .orElse(null);
    }


    private Double calculateSalePrice(Double originalPrice, Integer percent) {
        if (percent == null || percent <= 0) return originalPrice;
        return originalPrice * (100 - percent) / 100;
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

        if (exists) {
            throw new RuntimeException("Discount already applied to this product");
        }

        ProductDiscount pd = ProductDiscount.builder()
                .product(product)
                .discount(discount)
                .build();

        product.getDiscounts().add(pd);
        productRepository.save(product);
    }

}