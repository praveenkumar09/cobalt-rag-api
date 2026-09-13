package com.cobalt.rag.model;

/**
 * A business rule extracted from the retrieved code, with a pointer back to the
 * specific chunk that grounds it (validated against the real retrieval — never
 * a chunk id the model merely claims exists).
 */
public record BusinessRule(String rule, String chunkId) {
}
