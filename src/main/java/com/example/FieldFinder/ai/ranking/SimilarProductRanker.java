package com.example.FieldFinder.ai.ranking;

import com.example.FieldFinder.entity.Product;
import com.example.FieldFinder.repository.ProductRepository;
import com.example.FieldFinder.service.CategoryService;
import com.example.FieldFinder.specification.ProductSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class SimilarProductRanker {

    private final CategoryService categoryService;
    private final ProductRepository productRepository;

    static final int TIER1_CAP = 2;
    static final int TIER2_CAP = 3;
    static final int TIER3_CAP = 5;
    static final int POOL_SIZE = 100;

    public record TierEntry(Long productId, int tier) {}

    public List<TierEntry> rank(Product anchor, int limit) {
        if (anchor == null || anchor.getCategory() == null || limit <= 0) {
            return List.of();
        }

        Long anchorId = anchor.getProductId();
        Long anchorLeaf = anchor.getCategory().getCategoryId();
        String anchorBrand = anchor.getBrand();
        String anchorSex = anchor.getSex();

        // Candidate generation: mọi category cùng productType với anchor (giày → mọi loại giày).
        String type = categoryService.detectProductTypeFromQuery(
                null,
                anchor.getTags() == null ? null : new ArrayList<>(anchor.getTags()),
                anchor.getCategory().getName());
        Set<Long> categoryIds = new HashSet<>();
        if (type != null) {
            categoryIds.addAll(categoryService.expandByProductType(type));
        }
        if (categoryIds.isEmpty() && anchorLeaf != null) {
            // Không suy được type → thu hẹp về đúng leaf category của anchor.
            categoryIds.add(anchorLeaf);
        }
        if (categoryIds.isEmpty()) {
            return List.of();
        }

        List<Product> pool = productRepository
                .findAll(ProductSpecification.hasCategoryIds(categoryIds), PageRequest.of(0, POOL_SIZE))
                .getContent()
                .stream()
                .filter(p -> p.getProductId() != null && !p.getProductId().equals(anchorId))
                .toList();

        if (pool.isEmpty()) {
            return List.of();
        }

        // Phân tầng theo predicate cứng.
        List<Product> t1 = new ArrayList<>(); // same leaf + brand + gender
        List<Product> t2 = new ArrayList<>(); // same leaf
        List<Product> t3 = new ArrayList<>(); // same type, other leaf
        for (Product p : pool) {
            boolean sameLeaf = anchorLeaf != null
                    && p.getCategory() != null
                    && anchorLeaf.equals(p.getCategory().getCategoryId());
            boolean sameBrand = anchorBrand != null && anchorBrand.equalsIgnoreCase(p.getBrand());
            if (sameLeaf && sameBrand && genderOK(p.getSex(), anchorSex)) {
                t1.add(p);
            } else if (sameLeaf) {
                t2.add(p);
            } else {
                t3.add(p);
            }
        }

        Comparator<Product> byPopularity = Comparator
                .comparingInt(Product::getTotalSold).reversed()
                .thenComparing(Product::getProductId); // tie-break ổn định
        t1.sort(byPopularity);
        t2.sort(byPopularity);
        t3.sort(byPopularity);

        List<TierEntry> out = new ArrayList<>();
        Set<Long> used = new HashSet<>();
        addCapped(out, used, t1, TIER1_CAP, 0, limit);
        addCapped(out, used, t2, TIER2_CAP, 1, limit);
        addCapped(out, used, t3, TIER3_CAP, 2, limit);

        // Fill: còn thiếu → lấy phần còn lại của pool theo độ phổ biến (gắn tier 2 = nhóm cuối).
        if (out.size() < limit) {
            pool.stream()
                    .filter(p -> !used.contains(p.getProductId()))
                    .sorted(byPopularity)
                    .forEach(p -> {
                        if (out.size() < limit && used.add(p.getProductId())) {
                            out.add(new TierEntry(p.getProductId(), 2));
                        }
                    });
        }

        log.info("[SIMILAR-RANK] anchor={} type={} pool={} t1={} t2={} t3={} returned={}",
                anchorId, type, pool.size(), t1.size(), t2.size(), t3.size(), out.size());
        return out;
    }

    private void addCapped(List<TierEntry> out, Set<Long> used, List<Product> tier,
                           int cap, int tierIdx, int limit) {
        int added = 0;
        for (Product p : tier) {
            if (added >= cap || out.size() >= limit) break;
            if (used.add(p.getProductId())) {
                out.add(new TierEntry(p.getProductId(), tierIdx));
                added++;
            }
        }
    }

    /**
     * Cùng giới tính hoặc Unisex. anchor không có giới tính → chấp nhận hết.
     * Ứng viên thiếu giới tính → KHÔNG đủ điều kiện tier 0 (cần chắc chắn).
     */
    private boolean genderOK(String sex, String anchorSex) {
        if (anchorSex == null || anchorSex.isBlank()) return true;
        if (sex == null || sex.isBlank()) return false;
        String a = anchorSex.trim().toUpperCase();
        String s = sex.trim().toUpperCase();
        if (a.startsWith("UNI") || s.startsWith("UNI")) return true;
        String a3 = a.substring(0, Math.min(3, a.length()));
        String s3 = s.substring(0, Math.min(3, s.length()));
        return a3.equals(s3);
    }
}
