package com.cobalt.rag.model;

/**
 * One prior message ("user" or "assistant") in a conversation's history, as
 * injected into the LLM prompt. Produced by {@link com.cobalt.rag.service.ChatMemoryService}
 * from cache or Postgres, and consumed by both RagService (non-streaming Prompt)
 * and OrderedOpenAiStreamClient (streaming request body) — kept a plain model
 * type so the streaming client doesn't need to depend on ChatMemoryService.
 */
public record ChatTurn(String role, String content) {
}
