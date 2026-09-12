package com.cobalt.rag.model;

public record ChunkResult(
        String chunkId,
        String sourceFile,
        String programId,
        String domain,
        String subDomain,
        String sectionName,
        String sectionPurpose,
        String content,
        String fileType,
        Integer lineStart,
        Integer lineEnd,
        double similarity
) {
}
