package com.cobalt.rag.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple fixed-window rate limiter for /api/ask and /api/ask/formal — a
 * scripted flood of requests (e.g. repeated prompt-injection attempts) was
 * previously only ever recorded after each one completed, with nothing
 * throttling the requests themselves. In-memory and per-instance: adequate
 * for this internal tool's single-instance deployment, not meant to survive
 * a restart or scale across multiple app instances.
 *
 * Keyed by resolved user id when available, falling back to remote address
 * for unauthenticated callers (see RagController — these endpoints don't
 * require auth).
 */
@Component
public class AskRateLimiter {

    private static final long WINDOW_MILLIS = 60_000;

    private final int limitPerMinute;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public AskRateLimiter(@Value("${cobalt.rag.rate-limit.requests-per-minute:20}") int limitPerMinute) {
        this.limitPerMinute = limitPerMinute;
    }

    /** @return true if the request is allowed, false if the caller has exceeded the per-minute limit. */
    public boolean tryAcquire(String key) {
        long now = System.currentTimeMillis();
        Window window = windows.computeIfAbsent(key, k -> new Window(now));

        synchronized (window) {
            if (now - window.windowStart >= WINDOW_MILLIS) {
                window.windowStart = now;
                window.count.set(0);
            }
            return window.count.incrementAndGet() <= limitPerMinute;
        }
    }

    private static final class Window {
        volatile long windowStart;
        final AtomicInteger count = new AtomicInteger(0);

        Window(long windowStart) {
            this.windowStart = windowStart;
        }
    }
}
