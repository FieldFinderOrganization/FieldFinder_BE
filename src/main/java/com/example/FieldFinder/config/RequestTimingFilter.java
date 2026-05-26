package com.example.FieldFinder.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Log thời gian xử lý mọi HTTP request.
 * Bật để đo TTFB / total time khi test từ mobile / FE.
 * Output: [REQ] POST /api/ai/chat status=200 time=4321ms
 */
@Component
public class RequestTimingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestTimingFilter.class);
    private static final long SLOW_THRESHOLD_MS = 1000;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        long t0 = System.currentTimeMillis();
        try {
            chain.doFilter(req, res);
        } finally {
            long dt = System.currentTimeMillis() - t0;
            String marker = dt >= SLOW_THRESHOLD_MS ? " SLOW" : "";
            String line = String.format("[REQ] %s %s status=%d time=%dms%s",
                    req.getMethod(), req.getRequestURI(), res.getStatus(), dt, marker);
            if (dt >= SLOW_THRESHOLD_MS) {
                log.warn(line);
            } else {
                log.info(line);
            }
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // Bỏ qua actuator + static để giảm noise
        return uri.startsWith("/actuator") || uri.startsWith("/static") || uri.equals("/favicon.ico");
    }
}
