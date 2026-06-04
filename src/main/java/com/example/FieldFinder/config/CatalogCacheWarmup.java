package com.example.FieldFinder.config;

import com.example.FieldFinder.service.PitchService;
import com.example.FieldFinder.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Pre-fills Redis catalog caches at startup and every day at 3 AM.
 * Warms multiple page sizes (10, 20) — mobile uses size=10, web uses size=20.
 * Optionally warms per-user caches (cache key includes userId for wallet/discount overlay)
 * via WARMUP_USER_IDS env var (comma-separated UUIDs).
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
@Slf4j
public class CatalogCacheWarmup implements ApplicationRunner {

    // Sizes mobile + web đều dùng → warm cả 2 để mọi request hit cache.
    private static final int[] WARMUP_PAGE_SIZES = {10, 20};
    private static final int WARMUP_MAX_PAGES = 10;

    private final ProductService productService;
    private final PitchService pitchService;

    @Override
    public void run(ApplicationArguments args) {
        rewarmAllAsync();
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void scheduledRewarm() {
        log.info("Scheduled catalog rewarm starting (3 AM cron).");
        rewarmAllAsync();
    }

    @Async
    public void rewarmAllAsync() {
        rewarmAll();
    }

    private void rewarmAll() {
        try {
            productService.evictAllListProductCaches();
            pitchService.evictAllListPitchCaches();

            long t0 = System.currentTimeMillis();
            int productCalls = 0, pitchCalls = 0;
            // Base cache: 1 set (no userId). Warm cả 2 size mobile+web dùng.
            for (int size : WARMUP_PAGE_SIZES) {
                for (int p = 0; p < WARMUP_MAX_PAGES; p++) {
                    productService.getAllProducts(PageRequest.of(p, size), null, null, null, null);
                    productCalls++;
                }
            }
            productService.getProductsForAiAssistant(null);

            for (int size : WARMUP_PAGE_SIZES) {
                for (int p = 0; p < WARMUP_MAX_PAGES; p++) {
                    pitchService.getAllPitches(PageRequest.of(p, size), null, null, null);
                    pitchCalls++;
                }
            }
            long elapsed = System.currentTimeMillis() - t0;
            log.info("Catalog cache warmup finished in {}ms. sizes={}, productCalls={}, pitchCalls={}",
                    elapsed, Arrays.toString(WARMUP_PAGE_SIZES), productCalls, pitchCalls);
        } catch (Exception e) {
            log.warn("Catalog cache warmup skipped: {}", e.getMessage());
        }
    }
}
