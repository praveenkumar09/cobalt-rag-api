package com.cobalt.rag.model;

import java.util.List;

public record AskResponse(
        String answer,
        List<SourceCitation> sources,
        List<GraphRelationship> graphContext,
        int chunksRetrieved,
        List<String> followUpQuestions,
        ImpactAnalysis impactAnalysis,
        List<String> businessRules,
        List<DecisionTableRow> decisionTable,
        BusinessFlow businessFlow
) {
}
