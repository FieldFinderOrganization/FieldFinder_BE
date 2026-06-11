package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.req.DiscountRequestDTO;
import com.example.FieldFinder.dto.req.UserDiscountRequestDTO;
import com.example.FieldFinder.dto.res.DiscountResponseDTO;
import com.example.FieldFinder.dto.res.UserDiscountResponseDTO;
import com.example.FieldFinder.entity.*;
import com.example.FieldFinder.repository.*;
import com.example.FieldFinder.service.DiscountService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.FieldFinder.Enum.CategoryType;
import com.example.FieldFinder.Enum.UserTier;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DiscountServiceImpl implements DiscountService {

    private final DiscountRepository discountRepository;
    private final UserDiscountRepository userDiscountRepository;
    private final UserRepository userRepository;

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    private final RedisTemplate<String, Object> redisTemplate;

    private void clearProductCacheByDiscount(Discount discount) {
        if (discount == null) return;

        if (discount.getScope() == Discount.DiscountScope.SPECIFIC_PRODUCT) {
            if (discount.getApplicableProducts() != null) {
                for (Product p : discount.getApplicableProducts()) {
                    String pattern = "product_detail::" + p.getProductId() + "_*";
                    Set<String> keys = redisTemplate.keys(pattern);
                    if (keys != null && !keys.isEmpty()) {
                        redisTemplate.delete(keys);
                    }
                }
            }
        } else {
            Set<String> keys = redisTemplate.keys("product_detail::*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        }

        Set<String> topSellingKeys = redisTemplate.keys("top_selling::*");
        if (topSellingKeys != null && !topSellingKeys.isEmpty()) redisTemplate.delete(topSellingKeys);
    }

    @Override
    @Transactional
    public DiscountResponseDTO createDiscount(DiscountRequestDTO dto) {
        if (discountRepository.existsByCode(dto.getCode())) {
            throw new RuntimeException("Discount code already exists!");
        }

        Discount discount = dto.toEntity();

        handleScopeMapping(discount, dto);

        Discount saved = discountRepository.save(discount);

        // Mã đổi điểm KHÔNG phát free vào ví — user phải đổi qua /api/points/redeem
        if (saved.getPointCost() == null) {
            if (saved.getMinTier() == null) {
                userDiscountRepository.bulkAssignToAllUsers(saved.getDiscountId());
            } else {
                // Mã gắn hạng: chỉ đẩy vào ví user có hạng >= minTier
                userDiscountRepository.bulkAssignToUsersByTiers(
                        saved.getDiscountId(), tierNamesFrom(saved.getMinTier()));
            }
        }

        clearProductCacheByDiscount(saved);

        return DiscountResponseDTO.fromEntity(saved);
    }

    /** Tên các tier từ minTier trở lên (semantics "hạng đó trở lên"). */
    private static List<String> tierNamesFrom(UserTier minTier) {
        List<String> names = new ArrayList<>();
        for (UserTier t : UserTier.values()) {
            if (t.ordinal() >= minTier.ordinal()) names.add(t.name());
        }
        return names;
    }

    @Override
    @Transactional
    public DiscountResponseDTO updateDiscount(String id, DiscountRequestDTO dto) {
        Discount discount = discountRepository.findById(UUID.fromString(id))
                .orElseThrow(() -> new RuntimeException("Discount not found!"));

        discount.setCode(dto.getCode());
        discount.setDescription(dto.getDescription());
        discount.setDiscountType(Discount.DiscountType.valueOf(dto.getDiscountType()));
        discount.setValue(dto.getValue());
        discount.setMinOrderValue(dto.getMinOrderValue());
        discount.setMaxDiscountAmount(dto.getMaxDiscountAmount());
        discount.setScope(Discount.DiscountScope.valueOf(dto.getScope()));
        discount.setQuantity(dto.getQuantity());
        discount.setStartDate(dto.getStartDate());
        discount.setEndDate(dto.getEndDate());

        try {
            discount.setStatus(Discount.DiscountStatus.valueOf(dto.getStatus()));
        } catch (Exception e) {
            discount.setStatus(Discount.DiscountStatus.INACTIVE);
        }

        discount.setMinTier(dto.parseMinTier());
        discount.setPointCost(dto.getPointCost());

        handleScopeMapping(discount, dto);

        Discount updated = discountRepository.save(discount);

        clearProductCacheByDiscount(updated);

        return DiscountResponseDTO.fromEntity(updated);
    }

    private Set<Long> expandCategoryIds(List<Category> selectedCategories) {
        Set<Long> ids = new HashSet<>();
        for (Category cat : selectedCategories) {
            if (cat.getCategoryType() == CategoryType.SUPER_CATEGORY) {
                // keyword-based expansion: tìm mọi category có tên chứa keyword rồi lấy cả cây
                List<Category> matching = categoryRepository.findByNameContainingIgnoreCase(cat.getName());
                for (Category match : matching) {
                    ids.addAll(getAllDescendantIds(match.getCategoryId()));
                }
            } else {
                ids.addAll(getAllDescendantIds(cat.getCategoryId()));
            }
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
                if (ids.add(child.getCategoryId())) {
                    queue.add(child.getCategoryId());
                }
            }
        }
        return ids;
    }

    private void handleScopeMapping(Discount discount, DiscountRequestDTO dto) {

        if (discount.getApplicableProducts() == null) {
            discount.setApplicableProducts(new HashSet<>());
        }
        if (discount.getApplicableCategories() == null) {
            discount.setApplicableCategories(new HashSet<>());
        }

        if (discount.getScope() == Discount.DiscountScope.SPECIFIC_PRODUCT) {
            if (dto.getApplicableProductIds() != null && !dto.getApplicableProductIds().isEmpty()) {
                List<Product> products = productRepository.findAllById(dto.getApplicableProductIds());
                discount.setApplicableProducts(new HashSet<>(products));
            } else {
                discount.getApplicableProducts().clear();
            }
            discount.getApplicableCategories().clear();
        }
        else if (discount.getScope() == Discount.DiscountScope.CATEGORY) {
            if (dto.getApplicableCategoryIds() != null && !dto.getApplicableCategoryIds().isEmpty()) {
                List<Category> selectedCategories = categoryRepository.findAllById(dto.getApplicableCategoryIds());
                Set<Long> expandedIds = expandCategoryIds(selectedCategories);
                List<Category> allCats = categoryRepository.findAllById(new ArrayList<>(expandedIds));
                discount.setApplicableCategories(new HashSet<>(allCats));
            } else {
                discount.getApplicableCategories().clear();
            }
            discount.getApplicableProducts().clear();
        }
        else {
            discount.getApplicableProducts().clear();
            discount.getApplicableCategories().clear();
        }
    }

    @Override
    @Transactional
    public void deleteDiscount(String id) {
        Discount discount = discountRepository.findById(UUID.fromString(id)).orElse(null);
        if (discount != null) {
            discountRepository.delete(discount);
            clearProductCacheByDiscount(discount);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<DiscountResponseDTO> getAllDiscounts() {
        return discountRepository.findAll().stream()
                .map(DiscountResponseDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public DiscountResponseDTO getDiscountById(String id) {
        return discountRepository.findById(UUID.fromString(id))
                .map(DiscountResponseDTO::fromEntity)
                .orElseThrow(() -> new RuntimeException("Discount not found!"));
    }

    @Override
    @Transactional
    public void saveDiscountToWallet(UUID userId, UserDiscountRequestDTO dto) {
        User user = userRepository.findById(UUID.fromString(String.valueOf(userId)))
                .orElseThrow(() -> new RuntimeException("User not found"));

        Discount discount = discountRepository.findByCode(dto.getDiscountCode())
                .orElseThrow(() -> new RuntimeException("Discount code invalid"));

        if (discount.getStatus() != Discount.DiscountStatus.ACTIVE) {
            throw new RuntimeException("Discount is not active");
        }
        if (LocalDate.now().isAfter(discount.getEndDate())) {
            throw new RuntimeException("Discount is expired");
        }
        if (discount.getQuantity() <= 0) {
            throw new RuntimeException("Discount is out of stock");
        }
        if (discount.getMinTier() != null
                && !user.getEffectiveTier().isAtLeast(discount.getMinTier())) {
            throw new RuntimeException(
                    "Voucher chỉ dành cho hạng " + discount.getMinTier().name() + " trở lên");
        }
        if (discount.getPointCost() != null) {
            throw new RuntimeException("Mã này chỉ đổi được bằng điểm thưởng");
        }

        if (userDiscountRepository.existsByUserAndDiscount(user, discount)) {
            throw new RuntimeException("You have already saved this voucher");
        }

        UserDiscount userDiscount = UserDiscount.builder()
                .user(user)
                .discount(discount)
                .isUsed(false)
                .savedAt(java.time.LocalDateTime.now())
                .build();

        userDiscountRepository.save(userDiscount);

        // Không trừ quantity khi lưu — quantity = tổng lượt SỬ DỤNG, trừ atomic lúc checkout
        // (nhất quán với bulk assign cũng không trừ).

        clearProductCacheByDiscount(discount);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserDiscountResponseDTO> getMyWallet(UUID userId) {
        User user = userRepository.findById(UUID.fromString(String.valueOf(userId)))
                .orElseThrow(() -> new RuntimeException("User not found"));

        return userDiscountRepository.findWalletByUserId(userId).stream()
                .map(UserDiscountResponseDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public DiscountResponseDTO updateStatus(String id, Discount.DiscountStatus status) {
        Discount discount = discountRepository.findById(UUID.fromString(id))
                .orElseThrow(() -> new RuntimeException("Discount not found!"));
        discount.setStatus(status);
        Discount updated = discountRepository.save(discount);
        clearProductCacheByDiscount(updated);
        return DiscountResponseDTO.fromEntity(updated);
    }

    @Override
    @Transactional
    public void assignToUsers(String id, List<UUID> userIds) {
        Discount discount = discountRepository.findById(UUID.fromString(id))
                .orElseThrow(() -> new RuntimeException("Discount not found!"));

        for (UUID userId : userIds) {
            User user = userRepository.findById(userId).orElse(null);
            if (user == null) continue;
            if (userDiscountRepository.existsByUserAndDiscount(user, discount)) continue;

            UserDiscount ud = UserDiscount.builder()
                    .user(user)
                    .discount(discount)
                    .isUsed(false)
                    .savedAt(java.time.LocalDateTime.now())
                    .build();
            userDiscountRepository.save(ud);
        }
        clearProductCacheByDiscount(discount);
    }

    @Override
    @Transactional
    public int assignToTier(String id, UserTier tier) {
        Discount discount = discountRepository.findById(UUID.fromString(id))
                .orElseThrow(() -> new RuntimeException("Discount not found!"));

        int assigned = userDiscountRepository.bulkAssignToUsersByTiers(
                discount.getDiscountId(), tierNamesFrom(tier));
        clearProductCacheByDiscount(discount);
        return assigned;
    }

    @Override
    @Transactional
    public void grantWelcomeVouchers(UUID userId) {
        if (userId == null) return;
        try {
            userDiscountRepository.bulkAssignEligibleDiscountsToUser(
                    userId, List.of(UserTier.MEMBER.name()));
        } catch (Exception e) {
            // Không chặn flow đăng ký nếu gán voucher lỗi
            System.err.println("Lỗi gán welcome voucher cho user " + userId + ": " + e.getMessage());
        }
    }
}