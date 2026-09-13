package com.cobalt.rag.service;

import com.cobalt.rag.model.BusinessFlow;
import com.cobalt.rag.model.BusinessFlowEdge;
import com.cobalt.rag.model.DecisionTableRow;
import com.cobalt.rag.model.DomainTag;
import com.cobalt.rag.model.GraphRelationship;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Business-audience counterparts to the technical view: business rules and a
 * decision table extracted from the retrieved code (grounded, same trust tier
 * as the answer text itself), and a business-activity relabeling of the
 * technical call-graph. The graph's topology is always derived deterministically
 * from real Neo4j edges plus real domain/sub-domain ingestion tags — an LLM may
 * only polish the connecting wording, and only after its output is validated
 * against the original edges, so it can never introduce a flow that doesn't
 * actually exist in the system.
 */
@Service
public class BusinessInsightService {

    private static final String BUSINESS_RULES_SYSTEM_PROMPT = """
            You explain COBOL business logic to non-technical business stakeholders.
            Given the retrieved code context, extract every distinct business rule \
            actually implemented in this code — plain business language only, no code, \
            no COBOL terms, no field names, no program names. Each rule should read like \
            a policy statement a business analyst would write, e.g. "A policyholder cannot \
            request a partial withdrawal within the first 12 months of the policy."

            Only state rules clearly grounded in the given code — if the code has no \
            discernible business rule (e.g. it is purely technical plumbing), respond with \
            an empty array. Return at most 6 rules.

            Respond with ONLY a JSON array of strings, no markdown fences, no commentary.
            """;

    private static final String DECISION_TABLE_SYSTEM_PROMPT = """
            You convert COBOL conditional logic (IF/EVALUATE/condition-name checks) into a \
            business decision table. Given the retrieved code context, extract each distinct \
            decision point as one row with:
             - "condition": the business condition in plain language (not COBOL syntax)
             - "outcome": what happens when the condition is met, in plain business language
             - "exception": any special/error case tied to this condition, in plain business \
               language, or null if there is none

            Only include decisions clearly grounded in the given code. If there is no real \
            conditional/decision logic in the context, respond with an empty array. Return at \
            most 8 rows.

            Respond with ONLY a JSON array of objects with exactly these three keys, no \
            markdown fences, no commentary. Example:
            [{"condition":"Policy is less than 2 years old","outcome":"Surrender request is rejected","exception":"Hardship waiver code on file allows early surrender"}]
            """;

    private static final String FLOW_POLISH_SYSTEM_PROMPT = """
            You are given the EXACT, real business-activity flow of a system, as a JSON array \
            of {"from","relation","to"} steps already derived from the real system — you must \
            NOT add, remove, reorder, merge, or split any step, and you must NOT change "from" \
            or "to" for any step. Your only job is to optionally rephrase "relation" into more \
            natural business language (e.g. "leads to" -> "then triggers"), keeping it short.

            Respond with ONLY a JSON array of the same length, in the same order, with the same \
            "from"/"to" values and possibly-rephrased "relation" values — no markdown fences, no \
            commentary.
            """;

    private static final Map<String, String> RELATION_WORD = Map.of(
            "CALLS", "leads to",
            "EXECUTES", "leads to",
            "READS", "uses data from",
            "WRITES", "updates",
            "UPDATES", "updates",
            "DELETES_FROM", "removes data from",
            "COPIES", "shares data with",
            "USES_DATASET", "uses"
    );

    private static final Map<String, String> TYPE_FALLBACK_LABEL = Map.of(
            "COPYBOOK", "Shared Data Layout",
            "DATABASE_FILE", "Data File",
            "JCL_JOB", "Batch Job",
            "ENTRY_POINT", "Entry Point"
    );

    private final VectorSearchService vectorSearch;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BusinessInsightService(VectorSearchService vectorSearch, ChatModel chatModel) {
        this.vectorSearch = vectorSearch;
        this.chatModel = chatModel;
    }

    public List<String> extractBusinessRules(String question, String answer, String contextBlock) {
        try {
            String userMessage = buildExtractionUserMessage(question, answer, contextBlock);
            var response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(BUSINESS_RULES_SYSTEM_PROMPT), new UserMessage(userMessage))));
            String json = extractJsonArray(response.getResult().getOutput().getText());
            List<String> rules = objectMapper.readValue(json, new TypeReference<List<String>>() {});
            return rules.stream().filter(r -> r != null && !r.isBlank()).limit(6).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<DecisionTableRow> extractDecisionTable(String question, String answer, String contextBlock) {
        try {
            String userMessage = buildExtractionUserMessage(question, answer, contextBlock);
            var response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(DECISION_TABLE_SYSTEM_PROMPT), new UserMessage(userMessage))));
            String json = extractJsonArray(response.getResult().getOutput().getText());
            List<DecisionTableRow> rows = objectMapper.readValue(json, new TypeReference<List<DecisionTableRow>>() {});
            return rows.stream()
                    .filter(r -> r != null && r.condition() != null && !r.condition().isBlank())
                    .limit(8)
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    public BusinessFlow buildBusinessFlow(List<GraphRelationship> graphContext) {
        if (graphContext.isEmpty()) {
            return null;
        }

        try {
            List<String> nodeIds = graphContext.stream()
                    .flatMap(r -> List.of(r.fromId(), r.toId()).stream())
                    .filter(id -> id != null && !id.isBlank())
                    .distinct()
                    .toList();
            Map<String, DomainTag> domainTags = vectorSearch.fetchDomainTags(nodeIds);

            List<BusinessFlowEdge> deterministic = new ArrayList<>();
            java.util.Set<String> seen = new LinkedHashSet<>();
            for (GraphRelationship rel : graphContext) {
                String fromLabel = businessLabel(rel.fromId(), rel.fromType(), domainTags);
                String toLabel = businessLabel(rel.toId(), rel.toType(), domainTags);
                if (fromLabel.equals(toLabel)) continue; // collapsed self-loop — not a meaningful flow step

                String relation = RELATION_WORD.getOrDefault(rel.relType(), rel.relType().toLowerCase().replace('_', ' '));
                String key = fromLabel + "|" + relation + "|" + toLabel;
                if (!seen.add(key)) continue; // de-dupe identical triples

                deterministic.add(new BusinessFlowEdge(fromLabel, relation, toLabel));
            }

            if (deterministic.isEmpty()) {
                return null;
            }

            return new BusinessFlow(polishRelations(deterministic));
        } catch (Exception e) {
            // Best-effort, same as the extraction methods — a DB hiccup here shouldn't
            // ever be able to fail (or delay) the surrounding SSE stream.
            return null;
        }
    }

    /**
     * Best-effort LLM rephrasing of the connecting words — validated strictly
     * against the original edges. Any mismatch in count or in an edge's
     * from/to falls back to the deterministic relation for that edge, so the
     * model can influence wording only, never topology.
     */
    private List<BusinessFlowEdge> polishRelations(List<BusinessFlowEdge> deterministic) {
        try {
            String inputJson = objectMapper.writeValueAsString(deterministic.stream()
                    .map(e -> Map.of("from", e.fromActivity(), "relation", e.relation(), "to", e.toActivity()))
                    .toList());

            var response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(FLOW_POLISH_SYSTEM_PROMPT), new UserMessage(inputJson))));
            String json = extractJsonArray(response.getResult().getOutput().getText());
            List<Map<String, String>> polished = objectMapper.readValue(json, new TypeReference<List<Map<String, String>>>() {});

            if (polished.size() != deterministic.size()) {
                return deterministic;
            }

            List<BusinessFlowEdge> result = new ArrayList<>();
            for (int i = 0; i < deterministic.size(); i++) {
                BusinessFlowEdge original = deterministic.get(i);
                Map<String, String> candidate = polished.get(i);
                boolean sameEndpoints = original.fromActivity().equals(candidate.get("from"))
                        && original.toActivity().equals(candidate.get("to"));
                String relation = candidate.get("relation");
                if (sameEndpoints && relation != null && !relation.isBlank()) {
                    result.add(new BusinessFlowEdge(original.fromActivity(), relation, original.toActivity()));
                } else {
                    result.add(original);
                }
            }
            return result;
        } catch (Exception e) {
            return deterministic;
        }
    }

    private String businessLabel(String id, String type, Map<String, DomainTag> domainTags) {
        DomainTag tag = domainTags.get(id);
        if (tag != null) {
            String label = humanize(tag.subDomain());
            if (label == null || label.isBlank()) {
                label = humanize(tag.domain());
            }
            if (label != null && !label.isBlank()) {
                return label;
            }
        }
        return TYPE_FALLBACK_LABEL.getOrDefault(type, id);
    }

    private String humanize(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String[] words = raw.toLowerCase().split("[_\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    private String buildExtractionUserMessage(String question, String answer, String contextBlock) {
        return """
                Original question: %s

                Assistant's answer:
                %s

                Retrieved context:
                %s
                """.formatted(question, answer, contextBlock);
    }

    private String extractJsonArray(String text) {
        if (text == null) return "[]";
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start == -1 || end == -1 || end < start) return "[]";
        return text.substring(start, end + 1);
    }
}
