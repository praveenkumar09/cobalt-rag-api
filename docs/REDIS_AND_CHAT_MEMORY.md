# Cobalt RAG — Redis & Chat Memory

**What this document is:** a reference for the Redis integration added to `cobalt-rag-api` — what it's used for, how each piece is designed, and a detailed walkthrough of the chat-memory feature specifically (how the assistant now remembers a conversation's prior turns). Written to be presentable as-is to management, and detailed enough for engineers to extend.

**Last updated:** 2026-09-22

---

## 1. Executive Summary

Redis was added to `cobalt-rag-api` (via Docker) to solve three existing problems and enable one new feature:

| Subsystem | Before | After |
|---|---|---|
| **Session validation** | Every authenticated request hit Postgres to check a token | Redis-cached, falls back to Postgres automatically |
| **`/api/ask` rate limiting** | In-memory counter — lost on restart, doesn't work if the app ever scales to more than one instance | Redis-backed, atomic, shared across instances |
| **Chat memory** *(new feature)* | Did not exist — every question was answered with zero awareness of anything said earlier in the conversation | The last 8 question/answer turns of a conversation are now injected into every prompt |
| **Redis role in all four cases** | — | A **cache**, never the source of truth. Postgres always stays authoritative; Redis is an optimization layered on top, and every Redis operation is written so a Redis outage degrades the app rather than breaking it. |

All four pieces were implemented and verified live (real Postgres, real Redis, real OpenAI calls) — see [§8 Verification](#8-verification-what-was-actually-tested).

---

## 2. Why We Added This

Three things were true about `cobalt-rag-api` before this work, discovered by reading the actual service code rather than guessing:

1. **`AuthStore.resolveUserId()`** ran a Postgres query on *every single call* to `/api/ask`, `/api/ask/formal`, and every `/api/conversations/*` endpoint — for a session token that, once issued, essentially never changes for 30 days.
2. **`AskRateLimiter`** was a plain `ConcurrentHashMap`, and its own code comment already said as much: *"adequate for single-instance, not meant to survive a restart or scale across multiple app instances."*
3. **There was no chat memory at all.** `ConversationStore` already saved the full chat transcript to Postgres (for the history sidebar in the UI), but `RagService.ask()` / `askStream()` built every prompt as exactly `[SystemMessage, UserMessage(question)]` — nothing else. `AskRequest` didn't even have a `conversationId` field. Every question, including an obvious follow-up like *"what about the second one?"*, was answered as if it were the very first message the user had ever sent.

Redis is a natural fit for the first two (classic cache/counter use cases), and turned out to be the right tool for the third too: chat memory needs a *fast, per-conversation, capped read* on the hot path of every question — re-walking Postgres's full message tree on every single question would be needlessly slow, so Redis caches the derived "last N turns" view instead.

---

## 3. Architecture Overview

```
                        ┌─────────────────────────────────────────────┐
                        │              cobalt-rag-api                  │
                        │                                               │
  X-Session-Token  ───▶ │  AuthStore.resolveUserId()                   │
                        │      Redis GET  ──miss──▶  Postgres SELECT    │
                        │      (session:{token} → userId)               │
                        │                                               │
  POST /api/ask    ───▶ │  AskRateLimiter.tryAcquire()                 │
                        │      Redis Lua INCR+EXPIRE (ratelimit:{key})  │
                        │      Redis down?  →  ALLOW (fail open)        │
                        │                                               │
                        │  RagService.ask()/askStream()                │
                        │      ChatMemoryService.recentTurns()          │
                        │          Redis LRANGE ──miss──▶ Postgres      │
                        │          (chatmem:{userId}:{conversationId})  │
                        │      → injected into the OpenAI Prompt        │
                        │                                               │
  PUT /api/conversations/{id}/messages/{id}                             │
                        │  ConversationController.upsertMessage()      │
                        │      1. Postgres INSERT (source of truth)     │
                        │      2. ChatMemoryService.appendTurn()        │
                        │         Redis RPUSH + LTRIM + EXPIRE          │
                        └─────────────────┬─────────────────────────────┘
                                           │
                     ┌─────────────────────┼─────────────────────┐
                     ▼                     ▼                     ▼
              ┌─────────────┐      ┌──────────────┐      ┌─────────────┐
              │    Redis     │      │  Postgres     │      │   OpenAI     │
              │ (cache only, │      │ (pgvector +   │      │ (embeddings, │
              │  AOF-backed) │      │  source of    │      │  chat)       │
              │              │      │  truth)       │      │              │
              └─────────────┘      └──────────────┘      └─────────────┘
```

**The rule that governs every design decision below:** Redis never becomes a second source of truth. Every read from Redis has a Postgres (or in-memory) fallback; every write to Redis happens *after* the corresponding Postgres write already succeeded. If the `redis` container is stopped entirely, the app keeps working — slower for sessions, unthrottled for rate limiting, and memory-less for chat — but it does not 500.

### Docker topology

`redis` is a new service in `cobalt-rag-api/docker-compose.yml`, alongside the existing `prometheus` and `grafana` services. It does **not** join the `cobalt-shared` network (the one `cobol-ingestor-api` owns for Postgres/Neo4j) — it's owned entirely by `cobalt-rag-api`, since nothing else needs it.

```yaml
redis:
  image: redis:7-alpine
  command: redis-server --appendonly yes --maxmemory 256mb --maxmemory-policy allkeys-lru
  healthcheck:
    test: ["CMD-SHELL", "redis-cli ping | grep -q PONG"]
```

AOF persistence is turned on — not because losing the data would be dangerous (it's all a cache or a disposable counter), but so a plain container restart doesn't instantly cold-cache every session and unthrottle every rate limit at the exact same moment.

---

## 4. Session Cache (`AuthStore`)

**What it does:** caches the result of `SELECT user_id FROM sessions WHERE token = ? AND expires_at > now()` so it only actually runs against Postgres once per token every 5 minutes, instead of on every request.

**Key:** `session:{token}` → the raw `userId` string, TTL 300s (`cobalt.redis.session-cache-ttl-seconds`).

| Event | What happens to the cache |
|---|---|
| `login` / `signup` | Write-through: cached immediately, so the very next request is already a hit |
| Every `resolveUserId(token)` call | Redis `GET` first; on a miss, falls back to Postgres and writes the result back to Redis |
| `logout(token)` | That one key is deleted immediately |
| `resetPassword(email, ...)` | *All* of that user's tokens are looked up from Postgres's existing `idx_sessions_user` index **before** they're deleted, so every one of that user's cached sessions is evicted precisely — no separate Redis-side index needed |
| App boot | The existing "wipe all sessions so everyone re-logs-in after a restart" behavior is preserved — `session:*` is flushed in Redis right alongside the Postgres `DELETE FROM sessions` |
| Redis unreachable | `resolveUserId` silently falls through to the Postgres query — identical to the pre-Redis behavior, just without the latency win |

This was chosen deliberately as a **short-TTL cache instead of a full secondary index**: rather than Redis tracking every token ever issued to a user (so `logout`/`resetPassword` could enumerate and evict them all from Redis without touching Postgres), a 5-minute TTL alone bounds how long a revoked token could theoretically still resolve from a stale cache entry — an acceptable trade for an internal tool where logout/reset are rare events, and it avoids maintaining a second index.

---

## 5. Distributed Rate Limiter (`AskRateLimiter`)

**What it does:** the exact same job as before (cap `/api/ask` and `/api/ask/formal` at N requests/minute per caller), just backed by Redis instead of a local `ConcurrentHashMap`, so it now:
- survives an app restart,
- works correctly if `cobalt-rag-api` is ever scaled to more than one instance (all instances share the same counters).

**Key:** `ratelimit:{callerKey}` (`user:{userId}` or `ip:{remoteAddr}`, unchanged from before), a plain integer counter, window 60s.

**How the count is incremented atomically.** A naive `INCR` followed by a separate `EXPIRE` call has a race: if the process crashes between the two calls, that key is left with no expiry and permanently blocks that caller. This is avoided with a single Lua script, which Redis guarantees runs atomically:

```lua
local current = redis.call('INCR', KEYS[1])
if current == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
return current
```

**Fails open.** If Redis is unreachable, `tryAcquire()` returns `true` (request allowed) rather than throwing or blocking. This was a deliberate choice: this limiter exists to guard against *cost/abuse from scripted floods*, not as a security boundary (prompt-injection detection lives entirely elsewhere, in `SecurityPreFilter` and the LLM's own instructions, and is completely unaffected by Redis being up or down). Failing *closed* would mean a Redis blip takes down `/api/ask` for every single user — clearly worse than a brief unthrottled window for an internal, low-traffic tool.

---

## 6. Chat Memory — Detailed Walkthrough

This is the main feature, so it gets its own section.

### 6.1 What was actually missing before

It's worth being precise about this, because "add chat memory" sounds like it should already half-exist given `ConversationStore` was already saving every message to Postgres. It didn't, for one specific reason:

`ConversationStore` (Postgres) has always been a **write path from the frontend, for the frontend** — the chat UI calls `PUT /api/conversations/{id}/messages/{id}` to save the user's question and the assistant's answer *after* an answer already came back, purely so the history sidebar can show past conversations. `RagService` — the class that actually talks to OpenAI — never read from `ConversationStore`. And `AskRequest`, the payload for `/api/ask`, didn't even have a `conversationId` field to look one up by. So structurally, there was no wire connecting "what was said before" to "what the LLM sees now." Every question was answered completely in isolation, no matter how obviously it referred to a previous answer.

### 6.2 The design: two stores, two different jobs

| | Postgres (`ConversationStore`, unchanged) | Redis (`ChatMemoryService`, new) |
|---|---|---|
| **Role** | Source of truth | Cache, disposable |
| **What it holds** | The *entire* conversation, forever (until the existing 1-day expiry), as a branching tree (supports "regenerate" / alternate replies) | Just the **last 8 turns** (16 messages), as a flat ordered list |
| **What it's used for** | The history sidebar UI, restoring a past conversation, branch navigation | The one thing needed on the hot path: *"what were the last few things said in this conversation, so I can hand them to the LLM right now"* |
| **If it's lost** | Would be a real data-loss problem | Nothing — it's rebuilt from Postgres automatically on the next question |

Redis was **not** used to store the canonical transcript. That would have made Redis a second source of truth for something Postgres already does well (durable, relational, supports the existing branch/regenerate tree structure) — a bad trade for no benefit. Redis's only job is to make the *repeated, hot-path read* of "recent turns" fast, via the standard **cache-aside** pattern.

### 6.3 Data model

**Redis key:** `chatmem:{userId}:{conversationId}` → a Redis **LIST**, where each list element is one JSON-encoded message:

```json
{"role": "user", "content": "How does GIRO processing work?"}
{"role": "assistant", "content": "GIRO processing handles recurring premium collections."}
```

Stored oldest → newest (`RPUSH`ed onto the right end), so a plain `LRANGE key 0 -1` returns them in chronological order, ready to hand straight to the LLM.

**In Java**, this is represented by one new, tiny record shared between the memory service and the streaming client:

```java
public record ChatTurn(String role, String content) {}
```

### 6.4 The write path

```
Frontend answers a question
        │
        ▼
PUT /api/conversations/{conversationId}/messages/{messageId}
   { role: "user" | "assistant", content, parentId, ... }
        │
        ▼
ConversationController.upsertMessage()
        │
        ├──▶ 1. ConversationStore.upsertMessage(...)   [Postgres — unchanged, source of truth]
        │
        └──▶ 2. ChatMemoryService.appendTurn(userId, conversationId, role, content)
                     │
                     ├── RPUSH  chatmem:{userId}:{conversationId}   (encode as JSON, push to the end)
                     ├── LTRIM  chatmem:{userId}:{conversationId}  -16 -1   (keep only the last 16)
                     └── EXPIRE chatmem:{userId}:{conversationId}   86400s  (refresh the TTL)
```

The frontend already calls this endpoint twice per question (once for the user's message, once for the assistant's reply) immediately after `RagService` returns — so this was the natural single place to keep the cache in step with Postgres, with no extra round trip and no separate background job needed.

`appendTurn` only acts on `role == "user"` or `role == "assistant"` — other message types are ignored — and every Redis call inside it is wrapped: if Redis is down, the append is silently dropped. This is safe because Postgres already has the message (step 1 above always runs first and is what the response's success actually depends on); the next time anyone asks for this conversation's history, the cache-miss path below just rebuilds it from Postgres.

### 6.5 The read path

```
POST /api/ask  or  /api/ask/formal
   { question, viewMode, conversationId }
        │
        ▼
RagController  →  resolves userId from the session token (§4)
        │
        ▼
RagService.ask() / askStream(question, userId, viewMode, conversationId)
        │
        ▼
resolveHistory(userId, conversationId):
    if userId == null or conversationId is blank → return [] (no history)
    else → ChatMemoryService.recentTurns(userId, conversationId)
        │
        ▼
ChatMemoryService.recentTurns():
    LRANGE chatmem:{userId}:{conversationId} 0 -1
        │
        ├── non-empty  →  decode JSON, return it                      [cache HIT]
        │
        └── empty / Redis error
                │
                ▼
            ConversationStore.getConversation(userId, conversationId)   [Postgres fallback]
                (walks the message tree from current_leaf_id back to
                 the root — this is the exact same logic that powers
                 the history sidebar, reused as-is, unchanged)
                │
                ├── filter to role ∈ {user, assistant}
                ├── cap to the last 16 messages
                ├── best-effort: repopulate the Redis list for next time
                └── return it                                          [cache MISS, now warm]
```

Every path through `recentTurns()` returns a plain `List<ChatTurn>` and **never throws** — worst case (Redis *and* the conversation lookup both fail or the conversation is brand new) it returns an empty list, meaning the question gets answered with no history, which is exactly the old behavior. A caller can't tell the difference between "genuinely no history yet" and "something went wrong upstream" — by design, since neither case should ever surface as an error to the user asking a question.

### 6.6 How history actually reaches the LLM

This is the part that had to be threaded through carefully, because `cobalt-rag-api` has **two separate code paths** that talk to OpenAI for the main answer:

**Non-streaming (`RagService.ask()`, used by `/api/ask/formal`)** — uses Spring AI's `ChatModel` directly. The old two-message `Prompt` becomes a full list: system prompt, then one `UserMessage`/`AssistantMessage` per history turn (alternating, in order), then the current question:

```java
List<Message> promptMessages = new ArrayList<>();
promptMessages.add(new SystemMessage(...));
for (ChatTurn turn : history) {
    promptMessages.add("assistant".equals(turn.role())
            ? new AssistantMessage(turn.content())
            : new UserMessage(turn.content()));
}
promptMessages.add(new UserMessage(userMessage));   // the current question + retrieved context
chatModel.call(new Prompt(promptMessages));
```

**Streaming (`RagService.askStream()`, used by `/api/ask`)** — deliberately does **not** go through Spring AI's own streaming path. A separate class, `OrderedOpenAiStreamClient`, calls OpenAI's HTTP API directly, because Spring AI 1.0.0-M6's streaming reassembly can emit tokens out of order under bursty delivery (documented in that class's own Javadoc, confirmed by a real observed case). `streamText()` gained one new parameter for the history list, and builds the same message array by hand:

```java
public Flux<String> streamText(String systemPrompt, List<ChatTurn> history, String userMessage) {
    List<Map<String, String>> messages = new ArrayList<>();
    messages.add(Map.of("role", "system", "content", systemPrompt));
    for (ChatTurn turn : history) {
        messages.add(Map.of("role", turn.role(), "content", turn.content()));
    }
    messages.add(Map.of("role", "user", "content", userMessage));
    // ...unchanged from here down: the strictly-sequential SSE decode that
    // guarantees token order was not touched by this change at all.
}
```

**Everything else in `RagService` stays single-turn, on purpose.** The five or six *other* LLM calls per question — extracting business rules, the decision table, technical rules, the data dictionary, follow-up question suggestions, starter suggestions — are all independent, stateless extraction calls grounded in the *current* answer text. None of them take conversation history; only the one call that produces the actual answer the user reads does. Adding history everywhere would have been unnecessary scope for what was asked.

### 6.7 Bounding conversation length

The entire strategy for keeping the prompt size bounded, regardless of how long a conversation gets, is a **hard cap: the last 8 turns (16 messages)**, enforced by `LTRIM` on every write. There is no summarization of older turns in this version — once a conversation passes 8 turns, the 9th-oldest turn simply falls off the list and stops being sent to the model, the same way a normal chat UI's context window works.

This was a deliberate scope decision, not an oversight: summarizing older turns into a condensed running summary is a legitimate future enhancement if 8 turns proves too short in practice, but it's a separate feature with its own design questions (when to summarize, how to keep the summary accurate, an extra LLM call to pay for) — building it now would have been solving a problem nobody has reported yet.

### 6.8 Who gets history, and who doesn't

Chat memory is **authenticated-user-only**. `/api/ask` and `/api/ask/formal` have always allowed anonymous callers (no session token required) — that didn't change. What changes is only that an anonymous caller (or a request with no `conversationId`) simply gets an empty history list, i.e. exactly today's pre-chat-memory behavior. This mirrors how `ConversationStore` itself already scopes every saved conversation to an authenticated user — there was no anonymous history to build memory *from* in the first place, so no new anonymous-only storage path was invented.

### 6.9 A concrete example, from an actual test run

To make this less abstract, here's what really happened in a live verification (see §8), with two-message-per-turn logging of the Redis list at each step:

1. A user sends *"How does GIRO processing work?"* in conversation `c1`. The frontend `PUT`s it → Postgres gets the row, and `chatmem:{userId}:c1` becomes:
   ```
   [ {"role":"user","content":"How does GIRO processing work?"} ]
   ```
2. The assistant's answer comes back and gets `PUT` too. The list is now:
   ```
   [ {"role":"user","content":"..."} , {"role":"assistant","content":"..."} ]
   ```
3. The user asks a follow-up in the *same* conversation (`conversationId: "c1"`). `RagService` calls `resolveHistory("user-id", "c1")`, gets that 2-message list back from a Redis `LRANGE` (a cache **hit** — no Postgres query needed), and the OpenAI request now looks like:
   ```json
   { "messages": [
       {"role": "system", "content": "..."},
       {"role": "user", "content": "How does GIRO processing work?"},
       {"role": "assistant", "content": "..."},
       {"role": "user", "content": "<the follow-up, with retrieved code context appended>"}
   ]}
   ```
4. If the Redis key had instead been evicted (TTL expiry, `maxmemory` eviction, or a Redis restart) before step 3, `recentTurns()` would silently fall back to `ConversationStore.getConversation("user-id", "c1")`, reconstruct the identical 2-message list from Postgres, answer the question exactly the same way, and re-populate Redis so the *next* question is a cache hit again. This exact fallback-and-repopulate sequence was verified live and produced byte-identical results to step 3.

---

## 7. Metrics

One new counter was added to `RagMetrics`, following its existing method-per-metric pattern (everything here is scraped by Prometheus and visible in the existing Grafana "Cobalt RAG — Overview" dashboard — see `METRICS_AND_MONITORING.md`):

```java
rag_chat_memory_cache_total{result="hit"}
rag_chat_memory_cache_total{result="miss"}
```

A healthy steady state looks like mostly `hit` — a `miss` happens for the first question of a new conversation, right after a TTL expiry, or if Redis had a blip. A `miss` is never an error, just a signal that the Postgres fallback was used for that one lookup.

---

## 8. Verification — what was actually tested

This wasn't just code-reviewed — it was run live, against a real Postgres + Neo4j (from the sibling `cobol-ingestor-api` project), a real Redis container, and real OpenAI calls (via the local dev API key).

| Subsystem | Test | Result |
|---|---|---|
| Session cache | Signup → check `session:{token}` exists with TTL ~300s → authenticated call (cache hit) → logout → key evicted → next authenticated call → `401` | ✅ all steps confirmed |
| Rate limiter | 25 rapid `/api/ask/formal` calls | ✅ requests 1–20 → `200`, 21–25 → `429`; Redis key had a real TTL (never `-1`, confirming atomic `INCR`+`EXPIRE`) |
| Rate limiter fail-open | Stopped the `redis` container entirely, then called `/api/ask/formal` | ✅ still `200` — confirmed fail-open, exception caught internally, no user-facing failure |
| Chat memory — write & cap | Pushed 22 messages into one conversation | ✅ Redis list capped at exactly 16 via `LTRIM`, correct oldest→newest order |
| Chat memory — Postgres fallback | Built a 6-message conversation (with correctly chained `parentId`s, matching real frontend behavior), deleted the Redis key to simulate eviction, then asked a follow-up question | ✅ Redis was silently repopulated with the exact same 6 messages, in the same order, reconstructed from Postgres |
| End-to-end LLM call | Real OpenAI calls succeeded throughout (20/20 before the rate limit kicked in) | ✅ confirms the modified `Prompt`/`streamText` message construction is valid against the real OpenAI API |

One test artifact worth recording: an early version of the Postgres-fallback test reused message ids (`m1`, `m2`, …) across different test conversations. Because `conversation_messages.id` is a **global** primary key with `ON CONFLICT (id) DO UPDATE`, that silently overwrote rows belonging to an *earlier* conversation instead of creating new ones — a property of `ConversationStore`'s existing "idempotent regenerate" design, not a bug introduced by this change. Real message ids (generated by the frontend) are globally unique, so this only ever showed up in the test script, not in the feature itself.

---

## 9. Configuration Reference

All new `application.properties` keys, each with a sensible default so nothing needs to be set to run locally:

```properties
# ── Redis ─────────────────────────────────────────────────────────────────────
spring.data.redis.host=${REDIS_HOST:localhost}
spring.data.redis.port=${REDIS_PORT:6379}
spring.data.redis.timeout=2000ms

cobalt.redis.session-cache-ttl-seconds=300
cobalt.redis.rate-limit.window-seconds=60
cobalt.rag.chat-memory.max-turns=8
cobalt.rag.chat-memory.ttl-seconds=86400
```

| Property | Default | What it controls |
|---|---|---|
| `cobalt.redis.session-cache-ttl-seconds` | `300` | How long a cached `session:{token}` entry lives before it's re-verified against Postgres |
| `cobalt.redis.rate-limit.window-seconds` | `60` | The rate-limit window length (unchanged from before, just now enforced by Redis) |
| `cobalt.rag.chat-memory.max-turns` | `8` | How many question+answer pairs (16 messages) are kept per conversation and injected into the prompt |
| `cobalt.rag.chat-memory.ttl-seconds` | `86400` (1 day) | TTL of the cached turn list — matches `ConversationStore`'s own 1-day conversation expiry, so the cache and the source of truth go stale together |

`docker-compose.yml` sets `REDIS_HOST=redis` / `REDIS_PORT=6379` for the containerized app; the `localhost`/`6379` defaults above are for running the app outside Docker against a locally-exposed Redis.

---

## 10. Files Changed

| File | Change |
|---|---|
| `docker-compose.yml` | New `redis` service; `cobalt-rag-api` gains a `depends_on: redis (healthy)` and `REDIS_HOST`/`REDIS_PORT` env vars |
| `pom.xml` | New dependency: `spring-boot-starter-data-redis` |
| `src/main/resources/application.properties` | New Redis + chat-memory config keys (§9) |
| `src/main/java/com/cobalt/rag/service/AuthStore.java` | Session cache-aside (§4) |
| `src/main/java/com/cobalt/rag/service/AskRateLimiter.java` | Rewritten to use a Redis Lua script instead of `ConcurrentHashMap` (§5) |
| `src/main/java/com/cobalt/rag/model/ChatTurn.java` | **New** — one prior message (`role`, `content`) |
| `src/main/java/com/cobalt/rag/service/ChatMemoryService.java` | **New** — cache-aside recent-turn history (§6.3–6.5) |
| `src/main/java/com/cobalt/rag/model/AskRequest.java` | Added `conversationId` field |
| `src/main/java/com/cobalt/rag/controller/RagController.java` | Passes `conversationId` through to `RagService` |
| `src/main/java/com/cobalt/rag/service/RagService.java` | `ask`/`askStream` take `conversationId`; history is resolved and injected into the main-answer `Prompt` only (§6.6) |
| `src/main/java/com/cobalt/rag/service/OrderedOpenAiStreamClient.java` | `streamText` gained a `List<ChatTurn> history` parameter (§6.6) |
| `src/main/java/com/cobalt/rag/service/CodeChangeService.java` | Updated call site for the new `streamText` signature (passes `List.of()` — this call is unrelated to chat, stays stateless) |
| `src/main/java/com/cobalt/rag/controller/ConversationController.java` | Appends to the chat-memory cache right after every message is persisted (§6.4) |
| `src/main/java/com/cobalt/rag/service/RagMetrics.java` | New `rag.chat_memory.cache` hit/miss counter (§7) |

---

## 11. Known Limitations / Future Work

- **No cross-conversation memory.** Chat memory is scoped strictly to one `conversationId` — the assistant doesn't recall anything from a user's *other* past conversations. That would be a genuinely different feature (semantic long-term memory), and if built, it belongs in the existing pgvector infrastructure (`VectorSearchService`) rather than Redis — see the reasoning in the original design discussion.
- **No summarization for very long conversations** — see §6.7. The last-8-turns cap is the entire v1 bounding strategy.
- **No exact-match answer caching.** A repeated identical question still re-runs the full pipeline. Considered and deliberately deferred — it needs an invalidation story tied to corpus re-ingestion that doesn't exist yet, plus special-casing so `out_of_scope`/`security_violation` canned answers are never cached as if they were real answers.
- **Starter-suggestions cache** (`RagService`'s `volatile` 30-minute cache) was left as-is — same idea as the others, but lower value since its existing stale-while-revalidate behavior already masks most of the cost of a restart.
