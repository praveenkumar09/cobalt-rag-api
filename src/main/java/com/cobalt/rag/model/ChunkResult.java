package com.cobalt.rag.model;

import java.util.List;

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
        double similarity,
        List<String> keyDataFields
) {
}
