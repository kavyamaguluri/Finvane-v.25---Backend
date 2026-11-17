package com.finvane.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Parity Rate Limiter:
 * - Login attempts: max 5 per 15 minutes per IP.
 * - General endpoints: max 120 per minute per IP.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RateLimitFilter extends OncePerRequestFilter {

    private static class RequestCounter {
        final AtomicInteger count = new AtomicInteger(0);
        volatile long resetTimeMs;

        RequestCounter(long windowMs) {
            this.resetTimeMs = System.currentTimeMillis() + windowMs;
        }

        boolean allow(int maxLimit, long windowMs) {
            long now = System.currentTimeMillis();
            if (now > resetTimeMs) {
                count.set(0);
                resetTimeMs = now + windowMs;
            }
            return count.incrementAndGet() <= maxLimit;
        }

        long secondsUntilReset() {
            long remaining = resetTimeMs - System.currentTimeMillis();
            return Math.max(1, remaining / 1000);
        }
    }

    private final Map<String, RequestCounter> loginCounters = new ConcurrentHashMap<>();
    private final Map<String, RequestCounter> generalCounters = new ConcurrentHashMap<>();

    private static final int LOGIN_LIMIT = 5;
    private static final long LOGIN_WINDOW_MS = 15 * 60 * 1000L; // 15 mins

    private static final int GENERAL_LIMIT = 120;
    private static final long GENERAL_WINDOW_MS = 60 * 1000L; // 1 min

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String ip = getClientIp(request);
        String path = request.getRequestURI();

        if (path.contains("/auth/login") && "POST".equalsIgnoreCase(request.getMethod())) {
            // Do not block localhost in local testing
            if (!("127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "localhost".equals(ip))) {
                RequestCounter counter = loginCounters.computeIfAbsent(ip, k -> new RequestCounter(LOGIN_WINDOW_MS));
                if (!counter.allow(LOGIN_LIMIT, LOGIN_WINDOW_MS)) {
                    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                    response.setHeader("Retry-After", String.valueOf(counter.secondsUntilReset()));
                    response.setContentType("application/json");
                    response.getWriter().write("{\"ok\":false,\"error\":\"Too many failed login attempts. Please try again after 15 minutes.\"}");
                    return;
                }
            }
        }

        RequestCounter generalCounter = generalCounters.computeIfAbsent(ip, k -> new RequestCounter(GENERAL_WINDOW_MS));
        if (!generalCounter.allow(GENERAL_LIMIT, GENERAL_WINDOW_MS)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(generalCounter.secondsUntilReset()));
            response.setContentType("application/json");
            response.getWriter().write("{\"ok\":false,\"error\":\"Rate limit exceeded. Please slow down.\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String getClientIp(HttpServletRequest request) {
        String cfIp = request.getHeader("CF-Connecting-IP");
        if (cfIp != null && !cfIp.isBlank()) return cfIp.trim();
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

