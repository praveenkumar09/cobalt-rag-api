# Cobalt RAG — Metrics & Monitoring

**What this document is:** a reference for what we measure across the Cobalt RAG chatbot (the AS400/COBOL knowledge assistant), how each measurement is captured, and where to view it. Written to be presentable as-is to management, and detailed enough for engineers to extend.

**Last updated:** 2026-09-17

---

## 1. Executive Summary

The Cobalt RAG chatbot now has two independent measurement systems, covering two different questions:

| System | Question it answers | Where it lives |
|---|---|---|
| **Operational / AI-performance metrics** (Prometheus + Grafana) | *Is the system fast, reliable, and giving good answers?* | `/stats` page, dashboard **"Cobalt RAG — Overview"** |
| **Product feedback** (Postgres-backed) | *What are users telling us we should fix?* | `/admin` page |

Before this work, neither existed — the app had no visibility into how often the LLM calls failed, how long they took, how often the bot said "I don't know," or what users were asking to have improved. Both systems are now live and collecting real data with every question asked.

---

## 2. Why We Built This

This is a Retrieval-Augmented Generation (RAG) chatbot: for every question, it runs a vector search, a graph search, and up to **six separate LLM calls** (the main answer, business rules, technical rules, decision table, data dictionary, follow-up suggestions) — several of which previously **failed silently** and just returned an empty result with no record that anything went wrong. There was no way to answer basic operational questions like:

- How often does the bot say a question is out of scope?
- Which of the six LLM calls is slowest, or fails most often?
- Are people actually finding the retrieved code context relevant?
- What specific improvements are users asking for?

This effort closes that gap.

---

## 3. System 1 — Operational & AI-Performance Metrics

### 3.1 How it's measured (architecture)

```
┌─────────────────────┐      every question asked       ┌──────────────────┐
│  cobalt-rag-api      │ ───────────────────────────────▶│  In-memory        │
│  (Spring Boot app)   │   records timers & counters      │  Micrometer       │
│                      │   at each LLM call, each         │  registry         │
│                      │   retrieval step, each HTTP req  │                    │
└──────────────────────┘                                  └─────────┬────────┘
                                                                     │ exposed as text
                                                                     │ at /actuator/prometheus
                                                                     ▼
                                                          ┌──────────────────┐
                                                          │   Prometheus     │
                                                          │  (scrapes every  │
                                                          │   15 seconds)    │
                                                          └─────────┬────────┘
                                                                     │ stores as time-series
                                                                     ▼
                                                          ┌──────────────────┐
                                                          │     Grafana      │
                                                          │  (dashboard:     │
                                                          │  "Cobalt RAG —   │
                                                          │   Overview")     │
                                                          └─────────┬────────┘
                                                                     │ embedded (iframe)
                                                                     ▼
                                                          ┌──────────────────┐
                                                          │  /stats page in  │
                                                          │  the chat app    │
                                                          └──────────────────┘
```

**In plain terms:** every time the app does something worth measuring (calls the LLM, retrieves chunks, answers a question), it records that event in memory using a library called **Micrometer**. Every 15 seconds, **Prometheus** pulls the latest numbers from the app and stores them as a time-series (a running history, not just the current value). **Grafana** reads that history and draws it as charts and numbers. Our own `/stats` page shows that Grafana dashboard embedded directly in the app, so nobody needs a separate login or tool.

This is the same industry-standard pattern (Prometheus + Grafana) used for monitoring at most modern software companies.

### 3.2 What we measure — grouped by business question

#### A. Answer Quality — "Is the bot giving good, in-scope answers?"

| Metric (plain English) | How it's captured |
|---|---|
| **Out-of-scope rate** — % of questions the bot couldn't answer from the ingested COBOL codebase | Every answer is checked against the canned "I'm COBOL AI…" fallback message; tagged as `in_scope` or `out_of_scope` |
| **Average chunks retrieved per question** | Counted every time the vector search runs, before the LLM ever sees the question |
| **% of questions with graph context** | Recorded whenever the Neo4j graph search returns at least one program relationship |
| **Impact-analysis triggers** | Counted whenever a question is detected as a change-request (e.g. "what if I add a field to X") and the extra Neo4j traversal runs |

**Why this matters to the business:** the out-of-scope rate is the single clearest signal of whether the ingested codebase actually covers what people are asking about. A rising out-of-scope rate means either the ingestion needs to cover more programs, or users are asking questions the tool was never meant to answer.

#### B. LLM Call Performance & Reliability — "Is the AI layer fast and stable?"

Every question triggers **up to 8 distinct LLM calls**. Each one is now individually timed and tracked for failure, tagged by which call it was:

| Call type | What it does |
|---|---|
| `answer` / `answer_stream` | The main response to the user's question (non-streaming and streaming variants) |
| `business_rules` | Extracts plain-English business rules from the retrieved code |
| `technical_rules` | Extracts the same rules restated in COBOL/technical terms |
| `decision_table` | Builds a condition → outcome table from COBOL conditional logic |
| `data_dictionary` | Extracts field-level definitions (business + technical names) |
| `business_flow_polish` | Rephrases the call-graph into business-friendly wording |
| `followups` | Generates the 3 suggested follow-up questions |
| `starter_suggestions` | Generates the home-screen example questions (runs periodically, not per-question) |

For **each** of these, we track:
- **Call rate** — how many are happening per minute
- **Latency (p95)** — the 95th-percentile response time, i.e. "19 out of 20 calls finish faster than this" — a far more honest number than an average, which hides slow outliers
- **Error rate** — how often the call throws an exception or returns something the app can't parse

**Why this matters to the business:** several of these calls previously failed *silently* — the code caught the error and just returned an empty list, so a user might see "no business rules found" and have no way to know whether that's because there genuinely weren't any, or because the LLM call quietly broke. Now every failure is counted and visible.

#### C. System / Infrastructure Health — "Is the underlying service healthy?"

Standard application health metrics, via Spring Boot's built-in instrumentation (no custom code needed):

- **HTTP request rate and latency (p95)** — for the `/api/ask` (streaming) and `/api/ask/formal` (non-streaming) endpoints specifically, plus all endpoints in aggregate
- **JVM heap memory usage** — early warning for memory pressure
- **Database connection pool utilization (Postgres)** — early warning for connection exhaustion under load

### 3.3 The Dashboard: "Cobalt RAG — Overview"

Accessible at **`/stats`** in the chat app (requires login, same as the rest of the app). Organized into three rows:

1. **Answer quality** — 4 headline numbers (out-of-scope rate, avg chunks retrieved, % with graph context, impact-analysis triggers) + a trend chart of in-scope vs out-of-scope answers over time
2. **LLM call health** — call rate by type, p95 latency by type, error rate by type
3. **System health** — HTTP request rate/latency, JVM heap, database connection pool

The dashboard auto-refreshes every 30 seconds and defaults to the last 6 hours (adjustable, like any Grafana dashboard).

---

## 4. System 2 — Product Feedback ("Suggest Improvement")

### 4.1 How it's measured (architecture)

```
User clicks "Suggest improvement"          ┌──────────────────┐
on any answer, types feedback   ─────────▶ │  POST /api/feedback │
                                            └─────────┬────────┘
                                                       │ stored with:
                                                       │  - the question asked
                                                       │  - the answer given
                                                       │  - the user's note
                                                       │  - who submitted it, when
                                                       ▼
                                            ┌──────────────────┐
                                            │  Postgres         │
                                            │  "feedback" table │
                                            └─────────┬────────┘
                                                       │ read by
                                                       ▼
                                            ┌──────────────────┐
                                            │ GET /api/admin/   │
                                            │ feedback/stats     │
                                            └─────────┬────────┘
                                                       ▼
                                            ┌──────────────────┐
                                            │   /admin page      │
                                            └──────────────────┘
```

This is a simpler, direct path: the feedback is a first-class database record (not a metric/time-series), because we need to read the actual text of what people are asking for — a graph can't show that, a table can.

### 4.2 What we measure

| What | Where shown |
|---|---|
| **Total suggestions submitted** (all time) | `/admin` — headline tile |
| **Suggestions in the last 7 days** | `/admin` — headline tile |
| **Full detail per suggestion**: when, who submitted it, the original question, the answer it was about, and their suggested improvement | `/admin` — table, most recent first |

**Why this matters to the business:** this turns "suggest improvement" from a UI element that silently discarded feedback into an actual, queryable record of what's not working — grounded in the exact question and answer the user was reacting to.

---

## 5. Where To View This

| What | URL | Access |
|---|---|---|
| Operational/AI metrics dashboard | `/stats` | Requires login (any user — no separate admin role exists yet) |
| Feedback stats | `/admin` | Requires login (any user — no separate admin role exists yet) |
| Raw metrics (for engineers) | `http://<api-host>:8083/actuator/prometheus` | Internal only |
| Prometheus (raw time-series query UI) | `http://<host>:9090` | Internal only |
| Grafana (full UI, not just embedded dashboard) | `http://<host>:3001` | Internal only, anonymous viewer access enabled |

> **Note on access control:** this is presently an internal tool with no admin-role concept — `/admin` and `/stats` are visible to anyone who can log in, not restricted to a subset of users. That's a deliberate, acknowledged simplification for now, not an oversight.

---

## 6. Appendix — Metric Reference (for engineers)

All metrics are exposed in Prometheus format at `/actuator/prometheus`. Naming convention: dots become underscores, and Micrometer appends a unit suffix (`_seconds`, `_total`) automatically.

| Prometheus metric name | Type | Key label(s) | Meaning |
|---|---|---|---|
| `llm_calls_seconds_count` / `_sum` / `_bucket` | Timer (histogram) | `type` | Count/total time/latency distribution of each LLM call, by call type |
| `llm_call_errors_total` | Counter | `type` | LLM calls that threw or returned unparsable output, by call type |
| `rag_answers_total` | Counter | `outcome` (`in_scope` / `out_of_scope`) | Every answer returned, by outcome |
| `rag_chunks_retrieved_count` / `_sum` | Summary | — | Distribution of chunks returned by vector search per question |
| `rag_graph_context_total` | Counter | `has_context` (`true` / `false`) | Questions by whether graph search found relationships |
| `rag_impact_analysis_triggered_total` | Counter | — | Questions that triggered an impact-analysis graph traversal |
| `http_server_requests_seconds_*` | Timer (histogram) | `uri`, `status` | Standard Spring Boot HTTP metrics, all endpoints |
| `jvm_memory_used_bytes` | Gauge | `area` (`heap`/`nonheap`) | JVM memory usage |
| `hikaricp_connections_active` | Gauge | `pool` | Active Postgres connections |

**Source files** (for anyone extending this):
- Metric definitions: `cobalt-rag-api/src/main/java/com/cobalt/rag/service/RagMetrics.java`
- Instrumentation call sites: `RagService.java`, `BusinessInsightService.java`
- Dashboard definition: `cobalt-rag-api/docker/grafana/dashboards/cobalt-rag-overview.json`
- Feedback storage: `FeedbackStore.java`, `FeedbackController.java`
- Feedback UI: `cobalt-chat-ui/src/components/AdminPage.tsx`, `StatsPage.tsx`

---

## 7. Suggested Next Steps (not yet built)

- **Role-based access** for `/admin` and `/stats`, so they're not open to every logged-in user
- **Alerting** (e.g. page someone if the out-of-scope rate spikes, or an LLM call's error rate crosses a threshold)
- **Token usage / cost tracking per day**, if exposed by the underlying Spring AI client
- **Longer-term feedback trends** (week-over-week suggestion volume, not just a 7-day count)
