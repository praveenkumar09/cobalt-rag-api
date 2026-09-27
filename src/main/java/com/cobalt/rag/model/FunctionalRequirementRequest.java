package com.cobalt.rag.model;

import java.util.List;

/**
 * The original chat Q&amp;A plus its already-extracted structured insights
 * (business rules, decision table, data dictionary — all already grounded in
 * real chunks from the original retrieval), used as grounding to generate a
 * detailed functional requirement document for that specific question. No
 * raw retrieved context is needed here (unlike {@link ProposeChangeRequest}'s
 * full-source grounding): these structured fields are what the original
 * answer was itself grounded in, so they're sufficient without re-retrieving.
 */
public record FunctionalRequirementRequest(
        String question,
        String answer,
        List<BusinessRule> businessRules,
        List<DecisionTableRow> decisionTable,
        List<DataDictionaryEntry> dataDictionary
) {
}
