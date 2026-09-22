package com.cobalt.rag.service;

import com.cobalt.rag.model.ChatTurn;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Recent-turn conversation history for prompt injection, cache-aside over
 * {@link ConversationStore} (Postgres stays the source of truth; this class
 * never writes there). Redis holds a capped LIST of JSON-encoded
 * {@code {role, content}} entries per conversation at key
 * {@code chatmem:{userId}:{conversationId}}, so the hot "give me the recent
 * turns" read on every question doesn't have to re-walk
 * {@link ConversationStore}'s full parent-pointer tree
 * ({@code buildActivePath}) each time.
 *
 * <p>Every method is best-effort with respect to Redis: on any Redis failure,
 * reads fall back to Postgres and writes are silently dropped (the next read
 * for that conversation just falls back to Postgres again). A caller never
 * sees an exception from this class — worst case, a question is answered
 * with no history, identical to today's behavior before chat memory existed.
 *
 * <p>No summarization: history is bounded purely by keeping the last
 * {@code cobalt.rag.chat-memory.max-turns} turns (a "turn" = one user
 * message + one assistant message), trimmed on every write.
 */
@Service
public class ChatMemoryService {

    private static final String KEY_PREFIX = "chatmem:";

    private final StringRedisTemplate redisTemplate;
    private final ConversationStore conversationStore;
    private final RagMetrics metrics;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int maxMessages;
    private final Duration ttl;

    public ChatMemoryService(StringRedisTemplate redisTemplate,
                              ConversationStore conversationStore,
                              RagMetrics metrics,
                              @Value("${cobalt.rag.chat-memory.max-turns:8}") int maxTurns,
                              @Value("${cobalt.rag.chat-memory.ttl-seconds:86400}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.conversationStore = conversationStore;
        this.metrics = metrics;
        this.maxMessages = maxTurns * 2;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    /** Ordered oldest→newest, at most maxTurns*2 messages. Never throws. */
    public List<ChatTurn> recentTurns(String userId, String conversationId) {
        String key = key(userId, conversationId);
        try {
            List<String> raw = redisTemplate.opsForList().range(key, 0, -1);
            if (raw != null && !raw.isEmpty()) {
                metrics.recordChatMemoryCacheResult(true);
                return decode(raw);
            }
        } catch (Exception e) {
            // Redis down — fall straight through to Postgres, no repopulation attempt.
            metrics.recordChatMemoryCacheResult(false);
            return loadFromPostgres(userId, conversationId);
        }

        // Cache miss (TTL expired, evicted, or a genuinely new conversation) —
        // Postgres is authoritative; repopulate the cache best-effort so the
        // next question in this conversation is a hit.
        metrics.recordChatMemoryCacheResult(false);
        List<ChatTurn> fromPostgres = loadFromPostgres(userId, conversationId);
        if (!fromPostgres.isEmpty()) {
            try {
                repopulate(key, fromPostgres);
            } catch (Exception e) {
                // Best-effort — the next recentTurns() call just falls back to Postgres again.
            }
        }
        return fromPostgres;
    }

    /** Appends one message and trims to the configured cap. Call for role "user"/"assistant" only. */
    public void appendTurn(String userId, String conversationId, String role, String content) {
        if (!"user".equals(role) && !"assistant".equals(role)) {
            return;
        }
        String key = key(userId, conversationId);
        try {
            redisTemplate.opsForList().rightPush(key, encode(role, content));
            redisTemplate.opsForList().trim(key, -(long) maxMessages, -1);
            redisTemplate.expire(key, ttl);
        } catch (Exception e) {
            // Best-effort — a dropped append is invisible to the caller; the next
            // recentTurns() call falls back to Postgres, which already has this message.
        }
    }

    private List<ChatTurn> loadFromPostgres(String userId, String conversationId) {
        List<ChatTurn> all = conversationStore.getConversation(userId, conversationId)
                .map(detail -> detail.messages().stream()
                        .filter(m -> "user".equals(m.role()) || "assistant".equals(m.role()))
                        .map(m -> new ChatTurn(m.role(), m.content()))
                        .toList())
                .orElse(List.of());
        return all.size() <= maxMessages ? all : all.subList(all.size() - maxMessages, all.size());
    }

    private void repopulate(String key, List<ChatTurn> turns) {
        redisTemplate.delete(key);
        for (ChatTurn turn : turns) {
            redisTemplate.opsForList().rightPush(key, encode(turn.role(), turn.content()));
        }
        redisTemplate.expire(key, ttl);
    }

    private String encode(String role, String content) {
        try {
            return objectMapper.writeValueAsString(new ChatTurn(role, content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<ChatTurn> decode(List<String> raw) {
        return raw.stream()
                .map(json -> {
                    try {
                        return objectMapper.readValue(json, ChatTurn.class);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private String key(String userId, String conversationId) {
        return KEY_PREFIX + userId + ":" + conversationId;
    }
}
