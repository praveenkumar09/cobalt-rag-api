package com.cobalt.rag.model;

/**
 * @param conversationId When present (and the caller is authenticated), prior
 *                        turns from this conversation are injected into the
 *                        prompt for multi-turn context — see ChatMemoryService.
 *                        Optional: anonymous callers or a first question in a
 *                        new conversation simply omit it.
 */
public record AskRequest(String question, String viewMode, String conversationId) {

    /** "business" or "tech" (default) — which mode-specific extraction calls to run. */
    public String resolvedViewMode() {
        return "business".equals(viewMode) ? "business" : "tech";
    }
}
