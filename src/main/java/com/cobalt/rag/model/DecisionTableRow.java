package com.cobalt.rag.model;

/**
 * One row of a business decision table extracted from real conditional code
 * logic, with a pointer back to the specific chunk that grounds it (validated
 * against the real retrieval — never a chunk id the model merely claims exists).
 */
public record DecisionTableRow(String condition, String outcome, String exception, String chunkId) {
}
