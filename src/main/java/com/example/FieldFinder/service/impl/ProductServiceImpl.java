package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.ai.AIChat;
import com.example.FieldFinder.dto.req.ProductRequestDTO;
import com.example.FieldFinder.dto.res.CachedPage;
import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.example.FieldFinder.entity.*;
import com.example.FieldFinder.repository.*;
import com.example.FieldFinder.service.CloudinaryService;
import com.example.FieldFinder.service.ProductService;
import com.example.FieldFinder.specification.ProductSpecification;
import com.example.FieldFinder.util.DiscountEligibilityUtil;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
    private final com.example.FieldFinder.service.PhashIndex phashIndex;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CacheManager cacheManager;
    private ProductServiceImpl self;
    private final ExecutorService enrichmentExecutor = Executors.newFixedThreadPool(2);

    @PersistenceContext
    private EntityManager entityManager;

    public ProductServiceImpl(
            ProductRepository productRepository,
            CategoryRepository categoryRepository,
            ProductVariantRepository productVariantRepository,
            DiscountRepository discountRepository,
            UserDiscountRepository userDiscountRepository,
            CloudinaryService cloudinaryService,
            @Lazy AIChat aiChat,
            RedisTemplate<String, Object> redisTemplate,
            CacheManager cacheManager,
            @Lazy ProductServiceImpl self,
            com.example.FieldFinder.service.PhashIndex phashIndex) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.productVariantRepository = productVariantRepository;
        this.discountRepository = discountRepository;
        this.userDiscountRepository = userDiscountRepository;
        this.cloudinaryService = cloudinaryService;
        this.aiChat = aiChat;
        this.redisTemplate = redisTemplate;
        this.cacheManager = cacheManager;
        this.self = self;
        this.phashIndex = phashIndex;
    }

    private Map<Long, Long> loadCategoryParentById() {
        Map<Long, Long> parentById = new HashMap<>();
        for (Object[] row : categoryRepository.findAllCategoryIdAndParentId()) {
            Long id = (Long) row[0];
            if (id != null) {
                parentById.put(id, (Long) row[1]);
            }
        }
        return parentById;
    }

    private Set<Long> collectCategoryAncestorIds(Long categoryId, Map<Long, Long> parentById) {
        Set<Long> ancestors = new LinkedHashSet<>();
        Long current = categoryId;
        while (current != null) {
            ancestors.add(current);
            current = parentById.get(current);
        }
        return ancestors;
    }

    private List<Discount> getPublicDiscounts(Product product) {
        List<Discount> publicDiscounts = new ArrayList<>();
        Set<UUID> existingIds = new HashSet<>();

        if (product.getCategory() != null) {
            Map<Long, Long> parentById = loadCategoryParentById();
            List<Long> categoryIds = new ArrayList<>(
                    collectCategoryAncestorIds(product.getCategory().getCategoryId(), parentById));

            if (!categoryIds.isEmpty()) {
                List<Discount> implicitDiscounts = discountRepository.findApplicableDiscountsForProduct(
                        product.getProductId(),
                        categoryIds);

                for (Discount d : implicitDiscounts) {
                    if (existingIds.add(d.getDiscountId())) {
                        publicDiscounts.add(d);
                    }
                }
            }
        }

        // Bổ sung từ product.discounts (ProductDiscount) cho các discount chưa có
        if (product.getDiscounts() != null) {
            for (ProductDiscount pd : product.getDiscounts()) {
                Discount d = pd.getDiscount();
                if (d != null && existingIds.add(d.getDiscountId())) {
                    publicDiscounts.add(d);
                }
            }
        }

        return publicDiscounts;
    }

    /**
     * Batch precompute publicDiscounts cho list Product (page) trong 1 query DB.
     * Trả Map productId → list discount applicable cho product đó.
     * Tránh N queries findApplicableDiscountsForProduct mỗi product.
     */
    private Map<Long, List<Discount>> precomputePublicDiscountsForProducts(List<Product> products) {
        if (products == null || products.isEmpty()) return Collections.emptyMap();

        Set<Long> allProductIds = new HashSet<>();
        Set<Long> allCategoryIds = new HashSet<>();
        Map<Long, Set<Long>> productAncestors = new HashMap<>();
        Map<Long, Long> parentById = loadCategoryParentById();

        for (Product p : products) {
            allProductIds.add(p.getProductId());
            Set<Long> ancestors = Collections.emptySet();
            if (p.getCategory() != null) {
                ancestors = collectCategoryAncestorIds(p.getCategory().getCategoryId(), parentById);
            }
            productAncestors.put(p.getProductId(), ancestors);
            allCategoryIds.addAll(ancestors);
        }

        List<Discount> allDiscounts = allProductIds.isEmpty()
                ? Collections.emptyList()
                : discountRepository.findApplicableDiscountsForProductsBatch(
                        new ArrayList<>(allProductIds),
                        allCategoryIds.isEmpty() ? List.of(-1L) : new ArrayList<>(allCategoryIds));

        Map<Long, List<Discount>> result = new HashMap<>();
        for (Product p : products) {
            Long pid = p.getProductId();
            Set<Long> ancestors = productAncestors.getOrDefault(pid, Collections.emptySet());
            Set<UUID> seen = new HashSet<>();
            List<Discount> matched = new ArrayList<>();

            for (Discount d : allDiscounts) {
                if (!seen.add(d.getDiscountId())) continue;
                boolean applicable = false;
                if (d.getScope() == Discount.DiscountScope.GLOBAL) {
                    applicable = true;
                } else if (d.getScope() == Discount.DiscountScope.SPECIFIC_PRODUCT) {
                    applicable = d.getApplicableProducts() != null && d.getApplicableProducts().stream()
                            .anyMatch(ap -> ap.getProductId().equals(pid));
                } else if (d.getScope() == Discount.DiscountScope.CATEGORY) {
                    applicable = d.getApplicableCategories() != null && d.getApplicableCategories().stream()
                            .anyMatch(ac -> ancestors.contains(ac.getCategoryId()));
                }
                if (applicable) matched.add(d);
                else seen.remove(d.getDiscountId());  // allow re-add for other products
            }

            // Bổ sung từ product.discounts (ProductDiscount)
            if (p.getDiscounts() != null) {
                Set<UUID> matchedIds = matched.stream().map(Discount::getDiscountId).collect(Collectors.toSet());
                for (ProductDiscount pd : p.getDiscounts()) {
                    Discount d = pd.getDiscount();
                    if (d != null && matchedIds.add(d.getDiscountId())) {
                        matched.add(d);
                    }
                }
            }
            result.put(pid, matched);
        }
        return result;
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
        return mapToResponse(product, usedDiscountIds, null, null);
    }

    private ProductResponseDTO mapToResponse(Product product, List<UUID> usedDiscountIds, UUID userId) {
        return mapToResponse(product, usedDiscountIds, userId, null);
    }

    /**
     * @param preloadedPublicDiscounts list discount đã batch-load trước (skip DB query if non-null).
     */
    private ProductResponseDTO mapToResponse(Product product, List<UUID> usedDiscountIds, UUID userId,
                                              List<Discount> preloadedPublicDiscounts) {
        if (product == null) return null;

        List<Discount> allDiscounts = preloadedPublicDiscounts != null
                ? new ArrayList<>(preloadedPublicDiscounts)
                : getPublicDiscounts(product);
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

    /**
     * Overlay per-user discount lên DTO ĐÃ cached từ base.
     * @param product entity Product (vừa load batch)
     * @param baseDto DTO base (clone-friendly, sẽ mutate fields)
     * @param usedDiscountIds list discount user đã dùng (load 1 lần ngoài vòng)
     * @param userWallet wallet user (load 1 lần ngoài vòng)
     * @return DTO mới với salePrice + codes theo user
     */
    private ProductResponseDTO applyUserOverlay(Product product,
                                                 ProductResponseDTO baseDto,
                                                 List<UUID> usedDiscountIds,
                                                 List<UserDiscount> userWallet) {
        return applyUserOverlay(product, baseDto, usedDiscountIds, userWallet, null);
    }

    private ProductResponseDTO applyUserOverlay(Product product,
                                                 ProductResponseDTO baseDto,
                                                 List<UUID> usedDiscountIds,
                                                 List<UserDiscount> userWallet,
                                                 List<Discount> preloadedPublicDiscounts) {
        List<Discount> allDiscounts = preloadedPublicDiscounts != null
                ? new ArrayList<>(preloadedPublicDiscounts)
                : getPublicDiscounts(product);
        if (usedDiscountIds != null && !usedDiscountIds.isEmpty()) {
            allDiscounts.removeIf(d -> usedDiscountIds.contains(d.getDiscountId()));
        }
        List<String> walletDiscountCodes = new ArrayList<>();
        List<String> availableGlobalCodes = new ArrayList<>();
        Set<UUID> addedIds = allDiscounts.stream().map(Discount::getDiscountId).collect(Collectors.toSet());

        for (UserDiscount ud : userWallet) {
            Discount d = ud.getDiscount();
            if (usedDiscountIds != null && usedDiscountIds.contains(d.getDiscountId())) continue;
            if (!DiscountEligibilityUtil.isEligibleForProductPreview(d, product)) continue;

            if (!addedIds.contains(d.getDiscountId())
                    && d.getScope() != Discount.DiscountScope.GLOBAL) {
                allDiscounts.add(d);
                addedIds.add(d.getDiscountId());
            }
            if (d.getScope() == Discount.DiscountScope.GLOBAL) {
                if (!availableGlobalCodes.contains(d.getCode())) availableGlobalCodes.add(d.getCode());
            } else {
                if (!walletDiscountCodes.contains(d.getCode())) walletDiscountCodes.add(d.getCode());
            }
        }

        product.calculateSalePriceForUser(allDiscounts);

        // Clone shallow để tránh mutate cached object (Spring cache returns shared ref)
        ProductResponseDTO dto = ProductResponseDTO.fromEntity(product);
        dto.setSalePrice(product.getSalePrice());
        dto.setSalePercent(product.getOnSalePercent());
        if (!walletDiscountCodes.isEmpty()) dto.setAppliedDiscountCodes(walletDiscountCodes);
        else dto.setAppliedDiscountCodes(null);
        if (!availableGlobalCodes.isEmpty()) dto.setAvailableGlobalCodes(availableGlobalCodes);
        else dto.setAvailableGlobalCodes(null);
        // Preserve base fields from baseDto if needed (tags, variants...)
        // ProductResponseDTO.fromEntity already loads all needed.
        return dto;
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "top_selling", allEntries = true),
            @CacheEvict(value = "products_category", allEntries = true),
            @CacheEvict(value = "ai_catalog", allEntries = true)
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
                .dominantColor(com.example.FieldFinder.util.ColorVocab.canonical(request.getDominantColor()))
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
        return self.getProductDetail(id, userId);
    }

    /** Batch fetch — avoid N+1 query when AI Chat needs many products at once. */
    @Override
    @Transactional(readOnly = true)
    public Map<Long, ProductResponseDTO> getProductsByIds(List<Long> ids, UUID userId) {
        if (ids == null || ids.isEmpty()) return Collections.emptyMap();

        List<Product> products = productRepository.findAllListViewByIds(ids);
        Map<Long, List<Discount>> discountsMap = precomputePublicDiscountsForProducts(products);
        List<UUID> usedDiscountIds = (userId != null)
                ? userDiscountRepository.findUsedDiscountIdsByUserId(userId)
                : Collections.emptyList();

        // Ví user load 1 LẦN ngoài vòng (trước đây mapToResponse load lại mỗi product → N query nặng).
        List<UserDiscount> userWallet = (userId != null)
                ? userDiscountRepository.findWalletByUserId(userId)
                        .stream().filter(ud -> !ud.isUsed()).collect(Collectors.toList())
                : Collections.emptyList();

        Map<Long, ProductResponseDTO> out = new LinkedHashMap<>();
        for (Product p : products) {
            try {
                List<Discount> pub = discountsMap.getOrDefault(p.getProductId(), Collections.emptyList());
                ProductResponseDTO dto = (userId != null)
                        ? applyUserOverlay(p, null, usedDiscountIds, userWallet, pub)
                        : mapToResponse(p, usedDiscountIds, null, pub);
                out.put(p.getProductId(), dto);
            } catch (Exception e) {
                System.err.println("getProductsByIds map fail pid=" + p.getProductId() + ": " + e.getMessage());
            }
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductResponseDTO> getAllProducts(Pageable pageable, Long categoryId, Set<String> genders, String brand, String name, UUID userId) {
        // Lấy page base từ cache (không kèm userId trong key — share giữa users).
        CachedPage<ProductResponseDTO> basePage = self.getAllProductsCached(pageable, categoryId, genders, brand, name);
        if (userId == null || basePage.content().isEmpty()) {
            return basePage.toPage();
        }

        // Overlay per-user discount/wallet — batch load + apply trên page (≤ 20 items)
        List<Long> productIds = basePage.content().stream()
                .map(ProductResponseDTO::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        List<Product> productList = productRepository.findAllListViewByIds(productIds);
        Map<Long, Product> productMap = productList.stream()
                .collect(Collectors.toMap(Product::getProductId, p -> p));

        Map<Long, List<Discount>> discountsMap = precomputePublicDiscountsForProducts(productList);

        List<UUID> usedDiscountIds = userDiscountRepository.findUsedDiscountIdsByUserId(userId);
        List<UserDiscount> userWallet = userDiscountRepository.findWalletByUserId(userId)
                .stream().filter(ud -> !ud.isUsed()).collect(Collectors.toList());

        List<ProductResponseDTO> overlaid = basePage.content().stream()
                .map(baseDto -> {
                    Product p = productMap.get(baseDto.getId());
                    if (p == null) return baseDto;
                    return applyUserOverlay(p, baseDto, usedDiscountIds, userWallet,
                            discountsMap.getOrDefault(p.getProductId(), Collections.emptyList()));
                })
                .collect(Collectors.toList());

        return new PageImpl<>(overlaid, pageable, basePage.totalElements());
    }

    /**
     * Base cache: cache theo (pageable, categoryId, genders, brand) — KHÔNG kèm userId.
     * Mọi user share cache. Per-user discount apply post-cache trong getAllProducts.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "products_category", keyGenerator = "productListCacheKeyGenerator")
    public CachedPage<ProductResponseDTO> getAllProductsCached(Pageable pageable, Long categoryId, Set<String> genders, String brand, String name) {
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
                .and(ProductSpecification.hasBrand(effectiveBrand))
                .and(ProductSpecification.hasNameLike(name));

        // Stable-sort fallback: SQL OFFSET/LIMIT needs a deterministic order, else pages overlap
        // across requests -> infinite scroll never ends. Add a productId tiebreaker when unsorted.
        Pageable effectivePageable = pageable.getSort().isSorted()
                ? pageable
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "productId"));

        // Step 1: paginate root + category only -> real SQL OFFSET/LIMIT, exact page size, fast
        // (no variants collection -> no in-memory paging / HHH90003004).
        Page<Product> idPage = productRepository.findAll(spec, effectivePageable);

        // Step 2: hydrate variants for just this page in 1 query, preserving page order.
        List<Long> pageIds = idPage.getContent().stream().map(Product::getProductId).toList();
        Map<Long, Product> hydrated = pageIds.isEmpty()
                ? Collections.emptyMap()
                : productRepository.findAllListViewByIds(pageIds).stream()
                        .collect(Collectors.toMap(Product::getProductId, p -> p, (a, b) -> a));
        List<Product> ordered = idPage.getContent().stream()
                .map(p -> hydrated.getOrDefault(p.getProductId(), p))
                .toList();

        // Batch precompute public discounts cho cả page (1 query thay N)
        Map<Long, List<Discount>> discountsMap = precomputePublicDiscountsForProducts(ordered);

        // Base DTO: không có wallet/used. salePrice từ public discounts only.
        List<ProductResponseDTO> dtos = ordered.stream()
                .map(p -> mapToResponse(p, Collections.emptyList(), null,
                        discountsMap.getOrDefault(p.getProductId(), Collections.emptyList())))
                .toList();

        return CachedPage.from(new PageImpl<>(dtos, pageable, idPage.getTotalElements()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductResponseDTO> getProductsForAiAssistant(UUID userId) {
        // Base list cached (no userId). Overlay sẽ apply trong AIChat flow nếu cần.
        // AI flow hiện gọi với userId=null hầu hết → base cache hit luôn.
        List<ProductResponseDTO> base = self.getProductsForAiAssistantCached();
        if (userId == null) return base;

        // Apply overlay nếu userId — batch
        List<Long> ids = base.stream().map(ProductResponseDTO::getId).filter(Objects::nonNull).collect(Collectors.toList());
        if (ids.isEmpty()) return base;
        List<Product> productList = productRepository.findAllListViewByIds(ids);
        Map<Long, Product> pmap = productList.stream()
                .collect(Collectors.toMap(Product::getProductId, p -> p));
        Map<Long, List<Discount>> discountsMap = precomputePublicDiscountsForProducts(productList);
        List<UUID> usedDiscountIds = userDiscountRepository.findUsedDiscountIdsByUserId(userId);
        List<UserDiscount> wallet = userDiscountRepository.findWalletByUserId(userId)
                .stream().filter(ud -> !ud.isUsed()).collect(Collectors.toList());
        return base.stream().map(d -> {
            Product p = pmap.get(d.getId());
            return p != null ? applyUserOverlay(p, d, usedDiscountIds, wallet,
                    discountsMap.getOrDefault(p.getProductId(), Collections.emptyList())) : d;
        }).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "ai_catalog", key = "'ai_base'")
    public List<ProductResponseDTO> getProductsForAiAssistantCached() {
        List<ProductResponseDTO> all = new ArrayList<>();
        int pageIdx = 0;
        final int pageSize = 100;
        CachedPage<ProductResponseDTO> chunk;
        do {
            chunk = self.getAllProductsCached(PageRequest.of(pageIdx, pageSize), null, null, null, null);
            all.addAll(chunk.content());
            pageIdx++;
        } while (chunk.hasNext());
        return all;
    }

    @Override
    public void evictProductDetailForId(Long productId) {
        evictProductDetailCache(productId);
    }

    @Override
    public void evictAllListProductCaches() {
        for (String name : new String[]{"products_category", "ai_catalog", "top_selling"}) {
            Cache c = cacheManager.getCache(name);
            if (c != null) {
                c.clear();
            }
        }
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "top_selling", allEntries = true),
            @CacheEvict(value = "products_category", allEntries = true),
            @CacheEvict(value = "ai_catalog", allEntries = true)
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

        // Màu chủ đạo: admin duyệt/sửa qua update → chuẩn hóa canonical. Chỉ set khi request có gửi
        // (null = không đụng tới giá trị hiện có, tránh xóa nhầm màu đã duyệt khi update field khác).
        if (request.getDominantColor() != null && !request.getDominantColor().isBlank()) {
            product.setDominantColor(com.example.FieldFinder.util.ColorVocab.canonical(request.getDominantColor()));
        }

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
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "top_selling", allEntries = true),
            @CacheEvict(value = "products_category", allEntries = true),
            @CacheEvict(value = "ai_catalog", allEntries = true)
    })
    public void deleteProduct(Long id) {
        evictProductDetailCache(id);
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
        evictProductDetailCache(productId);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void commitStock(Long productId, String size, int quantity) {
        int updated = productVariantRepository.commitStockAtomic(productId, size, quantity);
        if (updated == 0) {
            System.err.println("⚠️ Warning: Cannot commit stock. Variant not found for Product ID: " + productId
                    + ", Size: " + size);
            return;
        }
        System.out.println(String.format(
                "[commitStock] productId=%d size=%s qty=%d (atomic UPDATE rows=%d)",
                productId, size, quantity, updated));
        evictProductDetailCache(productId);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseStock(Long productId, String size, int quantity) {
        Optional<ProductVariant> optionalVariant = productVariantRepository.findByProduct_ProductIdAndSize(productId,
                size);
        if (optionalVariant.isEmpty()) {
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
                evictProductDetailCache(productId);
            }
            return;
        }

        ProductVariant variant = optionalVariant.get();
        int newLocked = variant.getLockedQuantity() - quantity;
        variant.setLockedQuantity(Math.max(newLocked, 0));
        productVariantRepository.save(variant);
        evictProductDetailCache(productId);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void restoreStock(Long productId, String size, int quantity) {
        int updated = productVariantRepository.restoreStockAtomic(productId, size, quantity);
        if (updated == 0) {
            System.err.println("⚠️ Cannot restore stock. Variant not found Product=" + productId + " Size=" + size);
            return;
        }
        evictProductDetailCache(productId);
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
                .map(p -> mapToResponse(p, usedDiscountIds, userId))
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
                .map(p -> mapToResponse(p, usedDiscountIds, userId))
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
            com.example.FieldFinder.ai.AIChat.ProductEnrichment enrich =
                    aiChat.enrichProductFromImage(imageUrl);
            if (enrich.tags != null && !enrich.tags.isEmpty())
                updateProductTagsInBackGround(productId, enrich.tags);
            // Chỉ seed màu khi cột đang trống — KHÔNG đè giá trị admin đã duyệt.
            if (enrich.dominantColor != null && !enrich.dominantColor.isBlank())
                updateProductDominantColorInBackGround(productId, enrich.dominantColor);
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            Long phash = com.example.FieldFinder.util.PhashUtil.computeFromUrl(imageUrl);
            if (phash != null) {
                updateProductPhashInBackGround(productId, phash);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Transactional
    protected void updateProductPhashInBackGround(Long productId, Long phash) {
        Product p = productRepository.findById(productId).orElse(null);
        if (p != null) {
            p.setImagePhash(phash);
            productRepository.save(p);
            if (phashIndex != null) phashIndex.put(productId, phash);
        }
    }

    /** Seed màu chủ đạo AI — chỉ ghi khi cột đang trống (giữ nguyên giá trị admin đã duyệt). */
    @Transactional
    protected void updateProductDominantColorInBackGround(Long productId, String dominantColor) {
        Product p = productRepository.findById(productId).orElse(null);
        if (p != null && (p.getDominantColor() == null || p.getDominantColor().isBlank())) {
            p.setDominantColor(dominantColor);
            productRepository.save(p);
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
                boolean noTags = product.getTags() == null || product.getTags().isEmpty();
                boolean noColor = product.getDominantColor() == null || product.getDominantColor().isBlank();
                if (noTags) {
                    // enrichSingleProduct seed cả tags lẫn dominantColor trong 1 vision call.
                    enrichmentExecutor.submit(() -> enrichSingleProduct(product.getProductId(), product.getImageUrl()));
                } else if (noColor) {
                    // Backfill: đã có tags nhưng thiếu màu chủ đạo → seed riêng màu.
                    Long pid = product.getProductId();
                    String url = product.getImageUrl();
                    enrichmentExecutor.submit(() -> {
                        var enrich = aiChat.enrichProductFromImage(url);
                        if (enrich.dominantColor != null && !enrich.dominantColor.isBlank())
                            updateProductDominantColorInBackGround(pid, enrich.dominantColor);
                    });
                }
                if (product.getImagePhash() == null) {
                    Long pid = product.getProductId();
                    String url = product.getImageUrl();
                    enrichmentExecutor.submit(() -> {
                        Long h = com.example.FieldFinder.util.PhashUtil.computeFromUrl(url);
                        if (h != null) updateProductPhashInBackGround(pid, h);
                    });
                }
            }
        }
    }

    @PreDestroy
    public void shutdownExecutor() {
        enrichmentExecutor.shutdown();
    }

    @jakarta.annotation.PostConstruct
    public void backfillPhashOnStartup() {
        new Thread(() -> {
            try {
                Thread.sleep(5000);
                List<Product> need = productRepository.findAllNeedingPhashBackfill();
                System.out.println("🟢 pHash backfill on startup: " + need.size() + " product(s)");
                for (Product p : need) {
                    Long pid = p.getProductId();
                    String url = p.getImageUrl();
                    enrichmentExecutor.submit(() -> {
                        Long h = com.example.FieldFinder.util.PhashUtil.computeFromUrl(url);
                        if (h != null) updateProductPhashInBackGround(pid, h);
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "phash-backfill").start();
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
        evictAllListProductCaches();
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "product_detail", key = "#productId + '_' + (#userId != null ? #userId.toString() : 'anon')")
    public ProductResponseDTO getProductDetail(Long productId, UUID userId) {
        Product product = productRepository.findAllListViewByIds(List.of(productId)).stream()
                .findFirst()
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