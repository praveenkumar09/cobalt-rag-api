package com.cobalt.rag.service;

import com.cobalt.rag.model.ConversationDetail;
import com.cobalt.rag.model.ConversationMessageDto;
import com.cobalt.rag.model.ConversationSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persists chat conversations in the same Postgres instance cobalt-rag-api
 * already uses for pgvector, self-provisioning its own tables at startup —
 * kept separate from tpk-cobalt-ingestor's init.sql, which only runs once
 * against a fresh volume and wouldn't retroactively apply here.
 *
 * History is scoped by the authenticated user id and expires a day after
 * last activity: every read filters out expired rows immediately, and a
 * scheduled job actually purges them so storage doesn't grow unbounded.
 *
 * Messages form a tree via parent_id rather than a flat chronological list,
 * so a response can be "branched from" to explore an alternate follow-up
 * without disturbing the original path. conversations.current_leaf_id marks
 * the tip of whichever path is currently active; reads walk parent_id
 * pointers back from that leaf to the root to reconstruct just that path,
 * attaching sibling metadata at each step so the UI can offer prev/next
 * navigation wherever a message has more than one child.
 */
@Service
public class ConversationStore {

    private static final String EXPIRY_CLAUSE = "last_active_at > now() - interval '1 day'";
    private static final int TITLE_MAX_LENGTH = 60;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ConversationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS conversations (
                    id             TEXT PRIMARY KEY,
                    client_id      TEXT NOT NULL,
                    title          TEXT,
                    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                    last_active_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_conversations_client_active
                ON conversations (client_id, last_active_at DESC)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS conversation_messages (
                    id              TEXT PRIMARY KEY,
                    conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                    role            TEXT NOT NULL,
                    content         TEXT NOT NULL,
                    payload         JSONB,
                    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_conversation_messages_conv
                ON conversation_messages (conversation_id, created_at)
                """);

        jdbc.execute("""
                ALTER TABLE conversation_messages
                ADD COLUMN IF NOT EXISTS parent_id TEXT REFERENCES conversation_messages(id) ON DELETE CASCADE
                """);
        jdbc.execute("""
                ALTER TABLE conversations
                ADD COLUMN IF NOT EXISTS current_leaf_id TEXT
                """);
        jdbc.execute("""
                ALTER TABLE conversations
                ADD COLUMN IF NOT EXISTS view_mode TEXT NOT NULL DEFAULT 'tech'
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_conversation_messages_parent
                ON conversation_messages (parent_id)
                """);
    }

    public void upsertMessage(String clientId, String conversationId, String messageId,
                               String role, String content, JsonNode payload, String parentId, String viewMode) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversations WHERE id = ? AND client_id = ?",
                Integer.class, conversationId, clientId);

        if (exists == null || exists == 0) {
            String resolvedViewMode = "business".equals(viewMode) ? "business" : "tech";
            jdbc.update(
                    "INSERT INTO conversations (id, client_id, title, view_mode) VALUES (?, ?, ?, ?) " +
                            "ON CONFLICT (id) DO NOTHING",
                    conversationId, clientId, deriveTitle(role, content), resolvedViewMode);
        }

        String payloadJson = (payload == null || payload.isNull()) ? null : payload.toString();

        jdbc.update(
                "INSERT INTO conversation_messages (id, conversation_id, role, content, payload, parent_id) " +
                        "VALUES (?, ?, ?, ?, ?::jsonb, ?) " +
                        "ON CONFLICT (id) DO UPDATE SET content = EXCLUDED.content, payload = EXCLUDED.payload",
                messageId, conversationId, role, content, payloadJson, parentId);

        jdbc.update(
                "UPDATE conversations SET last_active_at = now(), current_leaf_id = ? WHERE id = ? AND client_id = ?",
                messageId, conversationId, clientId);
    }

    public List<ConversationSummary> listConversations(String clientId, int limit, int offset) {
        return jdbc.query(
                "SELECT c.id, c.title, c.last_active_at, " +
                        "(SELECT COUNT(*) FROM conversation_messages m WHERE m.conversation_id = c.id) AS message_count " +
                        "FROM conversations c " +
                        "WHERE c.client_id = ? AND " + EXPIRY_CLAUSE + " " +
                        "ORDER BY c.last_active_at DESC " +
                        "LIMIT ? OFFSET ?",
                (rs, rowNum) -> new ConversationSummary(
                        rs.getString("id"),
                        rs.getString("title"),
                        rs.getTimestamp("last_active_at").toInstant(),
                        rs.getInt("message_count")
                ),
                clientId, limit, offset
        );
    }

    public boolean hasMore(String clientId, int limit, int offset) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversations WHERE client_id = ? AND " + EXPIRY_CLAUSE,
                Integer.class, clientId);
        return count != null && count > offset + limit;
    }

    public Optional<ConversationDetail> getConversation(String clientId, String conversationId) {
        List<TitleAndMode> rows = jdbc.query(
                "SELECT title, view_mode FROM conversations WHERE id = ? AND client_id = ? AND " + EXPIRY_CLAUSE,
                (rs, rowNum) -> new TitleAndMode(rs.getString("title"), rs.getString("view_mode")),
                conversationId, clientId
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        String currentLeafId = queryCurrentLeafId(clientId, conversationId);
        List<ConversationMessageDto> messages = buildActivePath(conversationId, currentLeafId);

        return Optional.of(new ConversationDetail(conversationId, rows.get(0).title(), rows.get(0).viewMode(), messages));
    }

    private record TitleAndMode(String title, String viewMode) {
    }

    /**
     * Switches the conversation's active path to run through messageId, descending
     * from there via each node's most recently created child (stable because
     * regenerate never changes created_at) until a childless message is reached.
     */
    public Optional<ConversationDetail> selectBranch(String clientId, String conversationId, String messageId) {
        Integer owns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversations WHERE id = ? AND client_id = ? AND " + EXPIRY_CLAUSE,
                Integer.class, conversationId, clientId);
        if (owns == null || owns == 0) {
            return Optional.empty();
        }

        List<Row> rows = loadRows(conversationId);
        Map<String, Row> byId = new HashMap<>();
        for (Row row : rows) {
            byId.put(row.id, row);
        }
        if (!byId.containsKey(messageId)) {
            return Optional.empty();
        }

        Map<String, List<Row>> childrenByParent = groupByParent(rows);

        String newLeaf = messageId;
        List<Row> children = childrenByParent.get(newLeaf);
        while (children != null && !children.isEmpty()) {
            newLeaf = children.get(children.size() - 1).id;
            children = childrenByParent.get(newLeaf);
        }

        jdbc.update(
                "UPDATE conversations SET current_leaf_id = ? WHERE id = ? AND client_id = ?",
                newLeaf, conversationId, clientId);

        return getConversation(clientId, conversationId);
    }

    public boolean deleteConversation(String clientId, String conversationId) {
        return jdbc.update(
                "DELETE FROM conversations WHERE id = ? AND client_id = ?",
                conversationId, clientId) > 0;
    }

    @Scheduled(fixedRate = 60 * 60 * 1000, initialDelay = 60 * 1000)
    public void purgeExpiredScheduled() {
        purgeExpired();
    }

    public int purgeExpired() {
        return jdbc.update("DELETE FROM conversations WHERE last_active_at <= now() - interval '1 day'");
    }

    private String queryCurrentLeafId(String clientId, String conversationId) {
        List<String> leafIds = jdbc.query(
                "SELECT current_leaf_id FROM conversations WHERE id = ? AND client_id = ?",
                (rs, rowNum) -> rs.getString("current_leaf_id"),
                conversationId, clientId
        );
        return leafIds.isEmpty() ? null : leafIds.get(0);
    }

    private List<ConversationMessageDto> buildActivePath(String conversationId, String currentLeafId) {
        List<Row> rows = loadRows(conversationId);
        if (rows.isEmpty()) {
            return List.of();
        }

        Map<String, Row> byId = new HashMap<>();
        for (Row row : rows) {
            byId.put(row.id, row);
        }
        Map<String, List<Row>> childrenByParent = groupByParent(rows);

        List<Row> path;
        if (currentLeafId == null || !byId.containsKey(currentLeafId)) {
            // Legacy conversation with no tree metadata yet — fall back to plain
            // chronological order, reproducing pre-branching behavior exactly.
            path = rows;
        } else {
            List<Row> reversed = new ArrayList<>();
            String cursor = currentLeafId;
            while (cursor != null && byId.containsKey(cursor)) {
                Row row = byId.get(cursor);
                reversed.add(row);
                cursor = row.parentId;
            }
            Collections.reverse(reversed);
            path = reversed;
        }

        List<ConversationMessageDto> result = new ArrayList<>();
        for (Row row : path) {
            List<Row> siblings = childrenByParent.getOrDefault(row.parentId, List.of(row));
            List<String> siblingIds = siblings.stream().map(r -> r.id).toList();
            int siblingIndex = siblingIds.indexOf(row.id);
            result.add(new ConversationMessageDto(
                    row.id, row.role, row.content, parsePayload(row.payload), row.createdAt,
                    row.parentId, siblingIds, siblingIndex
            ));
        }
        return result;
    }

    private List<Row> loadRows(String conversationId) {
        return jdbc.query(
                "SELECT id, parent_id, role, content, payload, created_at FROM conversation_messages " +
                        "WHERE conversation_id = ? ORDER BY created_at ASC",
                (rs, rowNum) -> new Row(
                        rs.getString("id"),
                        rs.getString("parent_id"),
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getString("payload"),
                        rs.getTimestamp("created_at").toInstant()
                ),
                conversationId
        );
    }

    private Map<String, List<Row>> groupByParent(List<Row> rows) {
        Map<String, List<Row>> childrenByParent = new HashMap<>();
        for (Row row : rows) {
            childrenByParent.computeIfAbsent(row.parentId, k -> new ArrayList<>()).add(row);
        }
        for (List<Row> siblings : childrenByParent.values()) {
            siblings.sort(Comparator.comparing(r -> r.createdAt));
        }
        return childrenByParent;
    }

    private String deriveTitle(String role, String content) {
        if (!"user".equals(role) || content == null) {
            return null;
        }
        String trimmed = content.trim().replaceAll("\\s+", " ");
        return trimmed.length() > TITLE_MAX_LENGTH ? trimmed.substring(0, TITLE_MAX_LENGTH) + "…" : trimmed;
    }

    private JsonNode parsePayload(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private record Row(String id, String parentId, String role, String content, String payload, Instant createdAt) {
    }
}
