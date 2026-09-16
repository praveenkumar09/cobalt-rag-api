package com.cobalt.rag.service;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A deliberately casual auth system for this internal tool — email + password
 * (5-character minimum), stored in the same Postgres instance cobalt-rag-api
 * already uses, self-provisioning its own tables at startup (same pattern as
 * {@link ConversationStore}). Passwords are always hashed (BCrypt), even
 * though the rest of the policy is intentionally lightweight: reset-password
 * verifies nothing beyond knowing the email address, by explicit design.
 */
@Service
public class AuthStore {

    private static final int SESSION_TTL_DAYS = 30;

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", passwordEncoder.encode(newPassword), userId);
        jdbc.update("DELETE FROM sessions WHERE user_id = ?", userId);
    }

    public Optional<String> resolveUserId(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        List<String> ids = jdbc.query(
                "SELECT user_id FROM sessions WHERE token = ? AND expires_at > now()",
                (rs, rowNum) -> rs.getString("user_id"),
                token
        );
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
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
    }

    private String createSession(String userId) {
        String token = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO sessions (token, user_id, expires_at) VALUES (?, ?, now() + make_interval(days => ?))",
                token, userId, SESSION_TTL_DAYS
        );
        return token;
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private record UserRow(String id, String passwordHash) {}
}
