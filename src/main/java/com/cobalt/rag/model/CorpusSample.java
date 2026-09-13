package com.cobalt.rag.model;

/**
 * A lightweight, randomly-sampled row from the ingested chunks table, used to
 * ground the home-screen starter question suggestions in whatever codebase
 * is actually loaded — never a hardcoded example that could drift out of sync.
 */
public record CorpusSample(
        String programId,
        String domain,
        String subDomain,
        String sectionName,
        String sectionPurpose
) {
}
