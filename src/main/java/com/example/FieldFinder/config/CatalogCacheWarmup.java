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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pre-fills Redis catalog caches at startup and every day at 3 AM.
 * Evict-then-reload pattern so TTL resets fresh on each warm-up.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
@Slf4j
public class CatalogCacheWarmup implements ApplicationRunner {

    private static final int WARMUP_PAGE_SIZE = 20;
    private static final int WARMUP_MAX_PAGES = 10;

    private final ProductService productService;
    private final PitchService pitchService;

    @Override
    public void run(ApplicationArguments args) {
        rewarmAll();
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void scheduledRewarm() {
        log.info("Scheduled catalog rewarm starting (3 AM cron).");
        rewarmAll();
    }

    private void rewarmAll() {
        try {
            productService.evictAllListProductCaches();
            pitchService.evictAllListPitchCaches();

            for (int p = 0; p < WARMUP_MAX_PAGES; p++) {
                productService.getAllProducts(PageRequest.of(p, WARMUP_PAGE_SIZE), null, null, null, null);
            }
            productService.getProductsForAiAssistant(null);

            for (int p = 0; p < WARMUP_MAX_PAGES; p++) {
                pitchService.getAllPitches(PageRequest.of(p, WARMUP_PAGE_SIZE), null, null, null);
            }

            log.info("Catalog cache warmup finished (products + pitches, pages 0..{} size {}).",
                    WARMUP_MAX_PAGES - 1, WARMUP_PAGE_SIZE);
        } catch (Exception e) {
            log.warn("Catalog cache warmup skipped: {}", e.getMessage());
        }
    }
}
