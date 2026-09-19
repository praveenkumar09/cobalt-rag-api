package com.cobalt.rag.model;

public record AskRequest(String question, String viewMode) {

    /** "business" or "tech" (default) — which mode-specific extraction calls to run. */
    public String resolvedViewMode() {
        return "business".equals(viewMode) ? "business" : "tech";
    }
}
