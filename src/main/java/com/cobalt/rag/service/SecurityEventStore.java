package com.cobalt.rag.service;

import com.cobalt.rag.model.SecurityEvent;
import com.cobalt.rag.model.SecurityStats;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Persists detected security-relevant questions (prompt injection attempts,
 * requests for PII, or PII the user volunteered in their own message) in the
 * same Postgres instance cobalt-rag-api already uses, self-provisioning its
 * own table at startup (same pattern as {@link FeedbackStore}).
 *
 * Unlike {@link FeedbackStore}, user_id is nullable with ON DELETE SET NULL
 * rather than CASCADE: this is an audit trail, so a record should survive the
 * user account being deleted rather than disappearing with it. It's also
 * nullable because /api/ask does not currently require authentication — an
 * unauthenticated caller's violation is still worth recording, just with no
 * attributable user.
 *
 * Note: rows here can contain the literal PII text a user typed in (that's
 * the point of the "pii_provided" category) — treat this table as sensitive,
 * same as you would the source system it's protecting.
 */
@Service
public class SecurityEventStore {

    private final JdbcTemplate jdbc;

    public SecurityEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS security_events (
                    id             TEXT PRIMARY KEY,
                    user_id        TEXT REFERENCES users(id) ON DELETE SET NULL,
                    question       TEXT NOT NULL,
                    violation_type TEXT NOT NULL,
                    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_security_events_created ON security_events (created_at DESC)");
    }

    public void record(String userId, String question, String violationType) {
        jdbc.update(
                "INSERT INTO security_events (id, user_id, question, violation_type) VALUES (?, ?, ?, ?)",
                UUID.randomUUID().toString(), userId, question, violationType
        );
    }

    public SecurityStats stats(int limit, int offset) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM security_events", Long.class);
        Long promptInjection = jdbc.queryForObject(
                "SELECT COUNT(*) FROM security_events WHERE violation_type = 'prompt_injection'", Long.class);
        Long piiRequested = jdbc.queryForObject(
                "SELECT COUNT(*) FROM security_events WHERE violation_type = 'pii_requested'", Long.class);
        Long piiProvided = jdbc.queryForObject(
                "SELECT COUNT(*) FROM security_events WHERE violation_type = 'pii_provided'", Long.class);

        List<SecurityEvent> recent = jdbc.query(
                "SELECT se.id, COALESCE(u.email, 'anonymous') AS email, se.question, se.violation_type, se.created_at " +
                        "FROM security_events se LEFT JOIN users u ON u.id = se.user_id " +
                        "ORDER BY se.created_at DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> new SecurityEvent(
                        rs.getString("id"),
                        rs.getString("email"),
                        rs.getString("question"),
                        rs.getString("violation_type"),
                        rs.getTimestamp("created_at").toInstant()
                ),
                limit, offset
        );

        return new SecurityStats(
                total == null ? 0 : total,
                promptInjection == null ? 0 : promptInjection,
                piiRequested == null ? 0 : piiRequested,
                piiProvided == null ? 0 : piiProvided,
                recent
        );
    }
}
