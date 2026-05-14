package com.example.FieldFinder.config;

import com.example.FieldFinder.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * Pre-fills Redis list + AI catalog caches shortly after startup.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
@Slf4j
public class CatalogCacheWarmup implements ApplicationRunner {

    private static final int WARMUP_PAGE_SIZE = 20;
    private static final int WARMUP_MAX_PAGES = 10;

    private final ProductService productService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            for (int p = 0; p < WARMUP_MAX_PAGES; p++) {
                productService.getAllProducts(PageRequest.of(p, WARMUP_PAGE_SIZE), null, null, null, null);
            }
            productService.getProductsForAiAssistant(null);
            log.info("Catalog cache warmup finished (pages 0..{} size {}).", WARMUP_MAX_PAGES - 1, WARMUP_PAGE_SIZE);
        } catch (Exception e) {
            log.warn("Catalog cache warmup skipped: {}", e.getMessage());
        }
    }
}
