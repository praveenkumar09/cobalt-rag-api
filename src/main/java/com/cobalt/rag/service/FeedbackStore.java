package com.cobalt.rag.service;

import com.cobalt.rag.model.FeedbackEntry;
import com.cobalt.rag.model.FeedbackStats;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Persists "Suggest improvement" notes in the same Postgres instance
 * cobalt-rag-api already uses, self-provisioning its own table at startup
 * (same pattern as {@link AuthStore} / {@link ConversationStore}).
 */
@Service
public class FeedbackStore {

    private static final int ANSWER_SNIPPET_MAX_LENGTH = 500;

    private final JdbcTemplate jdbc;

    // Unused beyond ordering: feedback.user_id has a FK to users(id), so this
    // constructor dependency forces Spring to fully initialize AuthStore
    // (including its own @PostConstruct, which creates the users table) before
    // this bean is constructed — otherwise, on a fresh database, bean creation
    // order between two @Service classes that only depend on JdbcTemplate is
    // unspecified, and this table's own @PostConstruct can run first and fail
    // with "relation users does not exist".
    public FeedbackStore(JdbcTemplate jdbc, AuthStore authStore) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS feedback (
                    id             TEXT PRIMARY KEY,
                    user_id        TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    question       TEXT,
                    answer_snippet TEXT,
                    message        TEXT NOT NULL,
                    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_feedback_created ON feedback (created_at DESC)");
    }

    public void submit(String userId, String question, String answer, String message) {
        String snippet = answer == null ? null
                : answer.length() > ANSWER_SNIPPET_MAX_LENGTH ? answer.substring(0, ANSWER_SNIPPET_MAX_LENGTH) + "…" : answer;
        jdbc.update(
                "INSERT INTO feedback (id, user_id, question, answer_snippet, message) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), userId, question, snippet, message
        );
    }

    public FeedbackStats stats(int limit, int offset) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM feedback", Long.class);
        Long last7Days = jdbc.queryForObject(
                "SELECT COUNT(*) FROM feedback WHERE created_at > now() - interval '7 days'", Long.class);

        List<FeedbackEntry> recent = jdbc.query(
                "SELECT f.id, u.email, f.question, f.answer_snippet, f.message, f.created_at " +
                        "FROM feedback f JOIN users u ON u.id = f.user_id " +
                        "ORDER BY f.created_at DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> new FeedbackEntry(
                        rs.getString("id"),
                        rs.getString("email"),
                        rs.getString("question"),
                        rs.getString("answer_snippet"),
                        rs.getString("message"),
                        rs.getTimestamp("created_at").toInstant()
                ),
                limit, offset
        );

        return new FeedbackStats(total == null ? 0 : total, last7Days == null ? 0 : last7Days, recent);
    }
}
