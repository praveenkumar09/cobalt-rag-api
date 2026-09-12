package com.cobalt.rag.model;

/**
 * A single retrieved code chunk cited as evidence for an answer, with enough
 * structural metadata (file/program/section/line range) and the raw snippet
 * text for the UI to render an expandable citation.
 */
public record SourceCitation(
        String chunkId,
        String sourceFile,
        String programId,
        String sectionName,
        String sectionPurpose,
        Integer lineStart,
        Integer lineEnd,
        String fileType,
        double similarity,
        String snippet
) {
}
