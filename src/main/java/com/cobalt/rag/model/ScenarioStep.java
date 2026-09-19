package com.cobalt.rag.model;

/**
 * One step in a {@link ScenarioTrace}: a decision point evaluated against the
 * scenario's specific values, with a pointer back to the chunk that grounds it
 * (validated against the real retrieval — never a chunk id the model merely claims exists).
 */
public record ScenarioStep(String condition, String result, String explanation, String chunkId) {
}
