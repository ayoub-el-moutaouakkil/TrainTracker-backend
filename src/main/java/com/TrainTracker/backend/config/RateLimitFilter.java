package com.TrainTracker.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_PER_MINUTE     = 60;
    private static final int MAX_POST_PER_MINUTE = 10;

    private final ConcurrentHashMap<String, Deque<Long>> log = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws IOException, ServletException {
        String ip = clientIp(req);
        long now = System.currentTimeMillis();
        long window = now - 60_000;

        boolean isStartTracking = "POST".equals(req.getMethod())
                && "/api/journeys".equals(req.getRequestURI());
        int limit = isStartTracking ? MAX_POST_PER_MINUTE : MAX_PER_MINUTE;

        Deque<Long> timestamps = log.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            timestamps.removeIf(t -> t < window);
            if (timestamps.size() >= limit) {
                res.setStatus(429);
                res.setContentType("application/json");
                res.getWriter().write("{\"error\":\"Too Many Requests\"}");
                res.flushBuffer();
                return;
            }
            timestamps.addLast(now);
        }
        chain.doFilter(req, res);
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanup() {
        long cutoff = System.currentTimeMillis() - 60_000;
        log.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                e.getValue().removeIf(t -> t < cutoff);
                return e.getValue().isEmpty();
            }
        });
    }

    private String clientIp(HttpServletRequest req) {
        String cf = req.getHeader("CF-Connecting-IP");
        return cf != null ? cf : req.getRemoteAddr();
    }
}
