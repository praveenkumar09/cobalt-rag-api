package com.cobalt.rag.model;

/**
 * One business data-dictionary entry for a data element central to the
 * question (e.g. a copybook/record field), with a pointer back to the
 * specific chunk that grounds it (validated against the real retrieval —
 * never a chunk id the model merely claims exists).
 */
public record DataDictionaryEntry(String term, String technicalName, String description, String chunkId) {
}
