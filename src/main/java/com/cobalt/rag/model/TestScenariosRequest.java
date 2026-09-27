package com.cobalt.rag.model;

import java.util.List;

/**
 * Same grounding shape as {@link FunctionalRequirementRequest} — the original
 * chat Q&amp;A plus its already-extracted business rules and decision table —
 * used to derive QA test scenarios for that specific question without a fresh
 * retrieval.
 */
public record TestScenariosRequest(
        String question,
        String answer,
        List<BusinessRule> businessRules,
        List<DecisionTableRow> decisionTable
) {
}
