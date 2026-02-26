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

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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

        clearProductCacheByDiscount(saved);

        return DiscountResponseDTO.fromEntity(saved);
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

        handleScopeMapping(discount, dto);

        Discount updated = discountRepository.save(discount);

        clearProductCacheByDiscount(updated);

        return DiscountResponseDTO.fromEntity(updated);
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
                List<Category> categories = categoryRepository.findAllById(dto.getApplicableCategoryIds());
                discount.setApplicableCategories(new HashSet<>(categories));
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
    public List<DiscountResponseDTO> getAllDiscounts() {
        return discountRepository.findAll().stream()
                .map(DiscountResponseDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
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

        discount.setQuantity(discount.getQuantity() - 1);
        discountRepository.save(discount);

        clearProductCacheByDiscount(discount);
    }

    @Override
    public List<UserDiscountResponseDTO> getMyWallet(UUID userId) {
        User user = userRepository.findById(UUID.fromString(String.valueOf(userId)))
                .orElseThrow(() -> new RuntimeException("User not found"));

        return userDiscountRepository.findByUser_UserId(userId).stream()
                .map(UserDiscountResponseDTO::fromEntity)
                .collect(Collectors.toList());
    }
}