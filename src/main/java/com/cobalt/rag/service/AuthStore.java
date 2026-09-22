package com.cobalt.rag.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A deliberately casual auth system for this internal tool — email + password
 * (5-character minimum), stored in the same Postgres instance cobalt-rag-api
 * already uses, self-provisioning its own tables at startup (same pattern as
 * {@link ConversationStore}). Passwords are always hashed (BCrypt), even
 * though the rest of the policy is intentionally lightweight: reset-password
 * verifies nothing beyond knowing the email address, by explicit design.
 *
 * <p>{@code resolveUserId} is cached cache-aside in Redis ({@code session:*})
 * since it previously ran a Postgres query on every single authenticated
 * request. Postgres stays the source of truth — Redis is a pure latency
 * optimization: every Redis operation here is wrapped and best-effort, so a
 * Redis outage silently degrades back to the pre-cache, Postgres-only
 * behavior rather than failing the request. The cache TTL is intentionally
 * short (see {@code cobalt.redis.session-cache-ttl-seconds}) rather than
 * maintaining a full per-user secondary index for {@link #logout} — the only
 * multi-key case, {@link #resetPassword}, enumerates the user's tokens from
 * Postgres's own {@code idx_sessions_user} index before evicting them, so no
 * extra Redis-side bookkeeping is needed there either.
 */
@Service
public class AuthStore {

    private static final int SESSION_TTL_DAYS = 30;
    private static final String SESSION_CACHE_PREFIX = "session:";

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redisTemplate;
    private final Duration cacheTtl;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthStore(JdbcTemplate jdbc, StringRedisTemplate redisTemplate,
                      @Value("${cobalt.redis.session-cache-ttl-seconds:300}") long cacheTtlSeconds) {
        this.jdbc = jdbc;
        this.redisTemplate = redisTemplate;
        this.cacheTtl = Duration.ofSeconds(cacheTtlSeconds);
    }

    public static class EmailAlreadyExistsException extends RuntimeException {}
    public static class InvalidCredentialsException extends RuntimeException {}
    public static class UserNotFoundException extends RuntimeException {}

    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id            TEXT PRIMARY KEY,
                    email         TEXT NOT NULL UNIQUE,
                    password_hash TEXT NOT NULL,
                    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                    token      TEXT PRIMARY KEY,
                    user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    expires_at TIMESTAMPTZ NOT NULL
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions (user_id)");

        // Sessions are persisted in Postgres so they survive within a running
        // app instance, but every fresh boot should force everyone to sign in
        // again rather than silently resuming whatever was valid before the
        // restart — so wipe the table once, here, on startup.
        jdbc.update("DELETE FROM sessions");

        // The Redis cache must be wiped alongside the Postgres table, or a
        // still-TTL'd cached token would keep resolving after the boot-wipe
        // above. A one-time KEYS scan at startup (not per-request) is fine at
        // this scale. Best-effort: a Redis outage at boot must never block
        // the app from starting — Postgres is already correctly empty either way.
        try {
            Set<String> staleKeys = redisTemplate.keys(SESSION_CACHE_PREFIX + "*");
            if (staleKeys != null && !staleKeys.isEmpty()) {
                redisTemplate.delete(staleKeys);
            }
        } catch (Exception e) {
            // Redis unavailable at boot — Postgres wipe above is still correct.
        }
    }

    public String signup(String email, String password) {
        String normalized = normalizeEmail(email);
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, normalized);
        if (exists != null && exists > 0) {
            throw new EmailAlreadyExistsException();
        }

        String userId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, ?)",
                userId, normalized, passwordEncoder.encode(password));

        return createSession(userId);
    }

    public String login(String email, String password) {
        String normalized = normalizeEmail(email);
        List<UserRow> rows = jdbc.query(
                "SELECT id, password_hash FROM users WHERE email = ?",
                (rs, rowNum) -> new UserRow(rs.getString("id"), rs.getString("password_hash")),
                normalized
        );
        if (rows.isEmpty() || !passwordEncoder.matches(password, rows.get(0).passwordHash())) {
            throw new InvalidCredentialsException();
        }
        return createSession(rows.get(0).id());
    }

    /** Resets by email alone (no ownership verification) — see class Javadoc. */
    public void resetPassword(String email, String newPassword) {
        String normalized = normalizeEmail(email);
        List<String> ids = jdbc.query(
                "SELECT id FROM users WHERE email = ?", (rs, rowNum) -> rs.getString("id"), normalized);
        if (ids.isEmpty()) {
            throw new UserNotFoundException();
        }
        String userId = ids.get(0);
        // Enumerated BEFORE the delete below, via the existing idx_sessions_user
        // index, so every cached token for this user can be evicted precisely —
        // no separate Redis-side index of user->tokens is needed for this one
        // multi-key case.
        List<String> tokens = jdbc.query(
                "SELECT token FROM sessions WHERE user_id = ?", (rs, rowNum) -> rs.getString("token"), userId);
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", passwordEncoder.encode(newPassword), userId);
        jdbc.update("DELETE FROM sessions WHERE user_id = ?", userId);
        evictCachedSessions(tokens);
    }

    public Optional<String> resolveUserId(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            String cached = redisTemplate.opsForValue().get(SESSION_CACHE_PREFIX + token);
            if (cached != null) {
                return Optional.of(cached);
            }
        } catch (Exception e) {
            // Redis down — fall through to Postgres below, same as before caching existed.
        }

        List<String> ids = jdbc.query(
                "SELECT user_id FROM sessions WHERE token = ? AND expires_at > now()",
                (rs, rowNum) -> rs.getString("user_id"),
                token
        );
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        String userId = ids.get(0);
        cacheSession(token, userId);
        return Optional.of(userId);
    }

    public String requireUserId(String token) {
        return resolveUserId(token).orElseThrow(InvalidCredentialsException::new);
    }

    public Optional<String> emailForUser(String userId) {
        List<String> emails = jdbc.query(
                "SELECT email FROM users WHERE id = ?", (rs, rowNum) -> rs.getString("email"), userId);
        return emails.isEmpty() ? Optional.empty() : Optional.of(emails.get(0));
    }

    public void logout(String token) {
        jdbc.update("DELETE FROM sessions WHERE token = ?", token);
        evictCachedSessions(List.of(token));
    }

    private String createSession(String userId) {
        String token = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO sessions (token, user_id, expires_at) VALUES (?, ?, now() + make_interval(days => ?))",
                token, userId, SESSION_TTL_DAYS
        );
        // Write-through so the very next request for this token is already a cache hit.
        cacheSession(token, userId);
        return token;
    }

    private void cacheSession(String token, String userId) {
        try {
            redisTemplate.opsForValue().set(SESSION_CACHE_PREFIX + token, userId, cacheTtl);
        } catch (Exception e) {
            // Best-effort — a miss here just costs the next call a Postgres round-trip.
        }
    }

    private void evictCachedSessions(List<String> tokens) {
        try {
            redisTemplate.delete(tokens.stream().map(t -> SESSION_CACHE_PREFIX + t).toList());
        } catch (Exception e) {
            // Best-effort — the cache TTL is the backstop if this fails.
        }
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private record UserRow(String id, String passwordHash) {}
}
