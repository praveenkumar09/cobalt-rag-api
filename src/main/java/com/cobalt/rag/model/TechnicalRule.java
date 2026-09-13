package com.cobalt.rag.model;

/**
 * A technical (developer-facing) restatement of a code rule — same underlying
 * decision logic as a {@link BusinessRule}, but in COBOL terms (field names,
 * paragraph names, literals) instead of business language — with a pointer
 * back to the specific chunk that grounds it (validated against the real
 * retrieval — never a chunk id the model merely claims exists).
 */
public record TechnicalRule(String rule, String chunkId) {
}
