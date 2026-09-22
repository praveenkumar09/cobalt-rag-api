package com.cobalt.rag.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis-backed fixed-window rate limiter for /api/ask and /api/ask/formal —
 * a scripted flood of requests (e.g. repeated prompt-injection attempts) was
 * previously only ever recorded after each one completed, with nothing
 * throttling the requests themselves. Counting happens in a single Lua
 * script (atomic INCR + conditional EXPIRE) so a crash between the two
 * calls can never leave a key without a TTL, which would otherwise block
 * that caller forever.
 *
 * <p>Fails OPEN: if Redis is unavailable, requests are allowed through
 * rather than rejected. This limiter guards against cost/abuse from
 * scripted floods, not a hard security boundary (prompt-injection detection
 * itself lives in {@link SecurityPreFilter} and the LLM's own judgment,
 * both unaffected by this class). Failing closed would mean a Redis blip
 * takes down /api/ask entirely for every caller, which is strictly worse
 * for this internal, low-traffic tool than a brief unthrottled window.
 *
 * <p>Keyed by resolved user id when available, falling back to remote
 * address for unauthenticated callers (see RagController — these endpoints
 * don't require auth).
 */
@Component
public class AskRateLimiter {

    private static final RedisScript<Long> INCR_AND_EXPIRE = RedisScript.of("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final int limitPerMinute;
    private final int windowSeconds;

    public AskRateLimiter(StringRedisTemplate redisTemplate,
                           @Value("${cobalt.rag.rate-limit.requests-per-minute:20}") int limitPerMinute,
                           @Value("${cobalt.redis.rate-limit.window-seconds:60}") int windowSeconds) {
        this.redisTemplate = redisTemplate;
        this.limitPerMinute = limitPerMinute;
        this.windowSeconds = windowSeconds;
    }

    /** @return true if the request is allowed, false if the caller has exceeded the per-window limit. */
    public boolean tryAcquire(String key) {
        try {
            Long count = redisTemplate.execute(
                    INCR_AND_EXPIRE, List.of("ratelimit:" + key), String.valueOf(windowSeconds));
            return count == null || count <= limitPerMinute;
        } catch (Exception e) {
            return true; // fail open — see class Javadoc
        }
    }
}
