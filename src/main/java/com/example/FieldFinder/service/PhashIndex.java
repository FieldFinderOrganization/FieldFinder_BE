package com.example.FieldFinder.service;

import com.example.FieldFinder.repository.ProductRepository;
import com.example.FieldFinder.util.PhashUtil;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PhashIndex {

    private final ProductRepository productRepository;

    private volatile Map<Long, Long> cache = new HashMap<>();

    @PostConstruct
    public void init() {
        refresh();
    }

    @Scheduled(fixedDelay = 60_000)
    public void refresh() {
        List<Object[]> rows = productRepository.findAllProductIdAndPhash();
        Map<Long, Long> next = new HashMap<>(rows.size() * 2);
        for (Object[] r : rows) {
            Long id = (Long) r[0];
            Long h = (Long) r[1];
            if (id != null && h != null) next.put(id, h);
        }
        cache = next;
    }

    public static class Hit {
        public final Long productId;
        public final int distance;
        public Hit(Long productId, int distance) {
            this.productId = productId;
            this.distance = distance;
        }
    }

    public List<Hit> findWithin(long target, int maxDistance, int limit) {
        List<Hit> hits = new ArrayList<>();
        for (Map.Entry<Long, Long> e : cache.entrySet()) {
            int d = PhashUtil.hammingDistance(target, e.getValue());
            if (d <= maxDistance) hits.add(new Hit(e.getKey(), d));
        }
        hits.sort(Comparator.comparingInt(h -> h.distance));
        if (hits.size() > limit) return hits.subList(0, limit);
        return hits;
    }

    public void put(Long productId, Long phash) {
        if (productId == null || phash == null) return;
        Map<Long, Long> next = new HashMap<>(cache);
        next.put(productId, phash);
        cache = next;
    }

    public int size() {
        return cache.size();
    }
}