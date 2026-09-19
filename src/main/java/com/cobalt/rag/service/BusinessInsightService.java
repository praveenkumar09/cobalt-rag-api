package com.cobalt.rag.service;

import com.cobalt.rag.model.BusinessFlow;
import com.cobalt.rag.model.BusinessFlowEdge;
import com.cobalt.rag.model.BusinessRule;
import com.cobalt.rag.model.ChunkResult;
import com.cobalt.rag.model.DataDictionaryEntry;
import com.cobalt.rag.model.DecisionTableRow;
import com.cobalt.rag.model.DomainTag;
import com.cobalt.rag.model.GraphRelationship;
import com.cobalt.rag.model.ScenarioStep;
import com.cobalt.rag.model.ScenarioTrace;
import com.cobalt.rag.model.TechnicalRule;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Business- and technical-audience insight generators layered on top of the
 * retrieved code (grounded, same trust tier as the answer text itself):
 * business rules, a decision table, a data dictionary, a technical (COBOL-term)
 * restatement of the same rule logic for developers, and a business-activity
 * relabeling of the technical call-graph. The graph's topology is always
 * derived deterministically from real Neo4j edges plus real domain/sub-domain
 * ingestion tags — an LLM may only polish the connecting wording, and only
 * after its output is validated against the original edges, so it can never
 * introduce a flow that doesn't actually exist in the system.
 */
@Service
public class BusinessInsightService {

    private static final String RELEVANCE_GATE = """
            First, judge whether the ORIGINAL QUESTION is actually asking about business \
            logic or decision behavior at all. Many questions are purely structural or \
            relational instead — e.g. "what programs use/call/reference X", "where is X \
            defined", "which files does X read or write", "list the fields in X" — and have \
            NO business rule or decision to extract, even though the retrieved code (e.g. a \
            copybook's field/status definitions) may itself describe things that sound \
            rule-like. For a structural/relational question like that, respond with an empty \
            array — do not extract unrelated rules just because the surrounding code happens \
            to define some. Only extract when the question or answer is actually about what \
            the system decides, permits, rejects, or requires.
            """;

    private static final String CHUNK_REFERENCE_INSTRUCTION = """
            Each retrieved chunk in the context below is preceded by a line "Chunk ID: <id>". \
            For every item you return, include the exact Chunk ID (copied verbatim, exactly as \
            written) of the ONE chunk that most directly grounds that specific item. If no \
            single chunk clearly grounds it, set chunkId to null — never invent an id or guess \
            one that doesn't appear in the context.
            """;

    private static final String BUSINESS_RULES_SYSTEM_PROMPT = ("""
            You explain COBOL business logic to non-technical business stakeholders.
            You are given the user's ORIGINAL QUESTION, the assistant's ANSWER, and the \
            retrieved code that grounded that answer.

            %s
            When there genuinely is a relevant rule, state it in plain business language only \
            — no code, no COBOL terms, no field names, no program names — like a policy \
            statement a business analyst would write, e.g. "A policyholder cannot request a \
            partial withdrawal within the first 12 months of the policy." Ground every rule in \
            what the question/answer is actually about, not just anything findable in the raw \
            retrieved code. Return at most 6 rules.

            %s
            Respond with ONLY a JSON array of objects with exactly these two keys, no markdown \
            fences, no commentary. Example:
            [{"rule":"A partial withdrawal cannot be made within the first 12 months of the policy.","chunkId":"POLWD01C.cbl#2100-CHECK-ELIGIBILITY"}]
            """).formatted(RELEVANCE_GATE, CHUNK_REFERENCE_INSTRUCTION);

    private static final String TECHNICAL_RULES_SYSTEM_PROMPT = ("""
            You restate COBOL decision logic for developers — the same kind of rule a \
            business rule would describe, but in technical terms: exact COBOL field names, \
            condition-names, paragraph names, and literal values as they appear in the code, \
            not a business paraphrase. You are given the user's ORIGINAL QUESTION, the \
            assistant's ANSWER, and the retrieved code that grounded that answer.

            %s
            When there genuinely is a relevant rule, reference the actual field/paragraph \
            names and literals involved, e.g. "When CLM-APPROVED-AMT is less than or equal to \
            WS-AUTO-APPROVE-LIMIT, CLM-CLAIM-STATUS is set to 'A' and the approved-claims \
            counter is incremented." Return at most 8 rules.

            %s
            Respond with ONLY a JSON array of objects with exactly these two keys, no markdown \
            fences, no commentary. Example:
            [{"rule":"When WS-POLICY-YEARS is less than 2, SURR-REQUEST-STATUS is set to 'REJECTED'.","chunkId":"SURRPGM.cbl#2200-VALIDATE-SURRENDER"}]
            """).formatted(RELEVANCE_GATE, CHUNK_REFERENCE_INSTRUCTION);

    private static final String DECISION_TABLE_SYSTEM_PROMPT = ("""
            You convert COBOL conditional logic (IF/EVALUATE/condition-name checks) into a \
            business decision table. You are given the user's ORIGINAL QUESTION, the \
            assistant's ANSWER, and the retrieved code that grounded that answer.

            %s
            When there genuinely are relevant decisions, extract each distinct decision point \
            as one row with:
             - "condition": the business condition in plain language (not COBOL syntax)
             - "outcome": what happens when the condition is met, in plain business language
             - "exception": any special/error case tied to this condition, in plain business \
               language, or null if there is none

            %s
            Return at most 8 rows. Respond with ONLY a JSON array of objects with exactly \
            these four keys, no markdown fences, no commentary. Example:
            [{"condition":"Policy is less than 2 years old","outcome":"Surrender request is rejected","exception":"Hardship waiver code on file allows early surrender","chunkId":"SURRPGM.cbl#2200-VALIDATE-SURRENDER"}]
            """).formatted(RELEVANCE_GATE, CHUNK_REFERENCE_INSTRUCTION);

    private static final String SCENARIO_TRACE_SYSTEM_PROMPT = ("""
            You trace a concrete "what if" scenario through COBOL decision logic for a business \
            audience. You are given the user's ORIGINAL QUESTION (which describes a specific \
            hypothetical situation with concrete values — e.g. a policy year, an amount, a \
            status), the assistant's ANSWER, and the retrieved code that grounded that answer.

            First, judge whether the question actually describes a concrete scenario (specific \
            values you can trace through real decision logic) AND whether the retrieved code \
            contains decision logic relevant to it. If either is false, respond with exactly \
            {"steps":[],"outcome":null} — do not fabricate a trace from logic that isn't there.

            When there genuinely is a traceable scenario, walk through the relevant decision \
            points from the retrieved code IN ORDER, evaluating each one against the scenario's \
            specific values. For each step, provide:
             - "condition": the business condition being checked, in plain language (not COBOL syntax)
             - "result": how that condition evaluates for THIS scenario's specific values (e.g. \
               "Yes — 3 years is less than the 5-year minimum")
             - "explanation": what that result means in plain business terms

            %s
            After the last step, give one final "outcome": a one- or two-sentence plain-language \
            summary of what ultimately happens to this scenario.

            Return at most 8 steps. Respond with ONLY a JSON object with exactly two keys, \
            "steps" (array of objects with condition/result/explanation/chunkId) and "outcome" \
            (string or null) — no markdown fences, no commentary. Example:
            {"steps":[{"condition":"Is the policy at least 5 years old?","result":"No — the policy is 3 years old","explanation":"The policy does not meet the minimum tenure for standard surrender.","chunkId":"SURRPGM.cbl#2200-VALIDATE-SURRENDER"}],"outcome":"The surrender request is rejected because the policy has not reached the 5-year minimum tenure."}
            """).formatted(CHUNK_REFERENCE_INSTRUCTION);

    private static final String DATA_DICTIONARY_SYSTEM_PROMPT = ("""
            You build a business data dictionary from COBOL data definitions (copybook \
            fields, record layouts, working-storage items). You are given the user's \
            ORIGINAL QUESTION, the assistant's ANSWER, and the retrieved code that grounded \
            that answer.

            Extract a data-dictionary entry for each distinct data element that is central to \
            what the question is actually about — e.g. the fields of a specific copybook or \
            record the question concerns. Unlike a business rule, a data dictionary is \
            relevant even for structural questions (e.g. "what programs use copybook X" or \
            "what fields does X have" both warrant one) — only return an empty array when the \
            question has NO specific data element/record/copybook in focus at all (e.g. it's \
            purely about which programs call which, with no record layout involved).

            For each relevant entry, provide:
             - "term": a short business-friendly name for the field (e.g. "Claim Amount")
             - "technicalName": the COBOL field name exactly as it appears in the code \
               (e.g. "CLM-AMOUNT"), or null if it doesn't correspond to one single named field
             - "description": what the field represents and how it's used, in plain business \
               language

            %s
            Return at most 10 entries. Respond with ONLY a JSON array of objects with exactly \
            these four keys, no markdown fences, no commentary. Example:
            [{"term":"Claim Amount","technicalName":"CLM-AMOUNT","description":"The total monetary amount being claimed for a specific incident.","chunkId":"CLMREC.cpy#01-CLAIM-RECORD"}]
            """).formatted(CHUNK_REFERENCE_INSTRUCTION);

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
    private final RagMetrics metrics;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BusinessInsightService(VectorSearchService vectorSearch, ChatModel chatModel, RagMetrics metrics) {
        this.vectorSearch = vectorSearch;
        this.chatModel = chatModel;
        this.metrics = metrics;
    }

    public List<BusinessRule> extractBusinessRules(String question, String answer, String contextBlock,
                                                     List<ChunkResult> chunks) {
        Timer.Sample sample = metrics.startLlmCall();
        try {
            Set<String> validChunkIds = chunks.stream().map(ChunkResult::chunkId).collect(Collectors.toSet());
            String userMessage = buildExtractionUserMessage(question, answer, contextBlock);
            var response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(BUSINESS_RULES_SYSTEM_PROMPT), new UserMessage(userMessage))));
            String json = extractJsonArray(response.getResult().getOutput().getText());
            List<BusinessRule> rules = objectMapper.readValue(json, new TypeReference<List<BusinessRule>>() {});
            return rules.stream()
                    .filter(r -> r != null && r.rule() != null && !r.rule().isBlank())
                    .map(r -> validChunkIds.contains(r.chunkId()) ? r : new BusinessRule(r.rule(), null))
                    .limit(6)
                    .toList();
        } catch (Exception e) {
            metrics.recordLlmCallError("business_rules");
            return List.of();
        } finally {
            metrics.stopLlmCall(sample, "business_rules");
        }
    }

    public List<TechnicalRule> extractTechnicalRules(String question, String answer, String contextBlock,
                                                       List<ChunkResult> chunks) {
        Timer.Sample sample = metrics.startLlmCall();
        try {
            Set<String> validChunkIds = chunks.stream().map(ChunkResult::chunkId).collect(Collectors.toSet());
            String userMessage = buildExtractionUserMessage(question, answer, contextBlock);
            var response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(TECHNICAL_RULES_SYSTEM_PROMPT), new UserMessage(userMessage))));
            String json = extractJsonArray(response.getResult().getOutput().getText());
            List<TechnicalRule> rules = objectMapper.readValue(json, new TypeReference<List<TechnicalRule>>() {});
            return rules.stream()
                    .filter(r -> r != null && r.rule() != null && !r.rule().isBlank())
                    .map(r -> validChunkIds.contains(r.chunkId()) ? r : new TechnicalRule(r.rule(), null))
                    .limit(8)
                    .toList();
        } catch (Exception e) {
            metrics.recordLlmCallError("technical_rules");
            return List.of();
        } finally {
            metrics.stopLlmCall(sample, "technical_rules");
        }
    }

    public List<DecisionTableRow> extractDecisionTable(String question, String answer, String contextBlock,
                                                         List<ChunkResult> chunks) {
        Timer.Sample sample = metrics.startLlmCall();
        try {
            Set<String> validChunkIds = chunks.stream().map(ChunkResult::chunkId).collect(Collectors.toSet());
            String userMessage = buildExtractionUserMessage(question, answer, contextBlock);
            var response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(DECISION_TABLE_SYSTEM_PROMPT), new UserMessage(userMessage))));
            String json = extractJsonArray(response.getResult().getOutput().getText());
            List<DecisionTableRow> rows = objectMapper.readValue(json, new TypeReference<List<DecisionTableRow>>() {});
            return rows.stream()
                    .filter(r -> r != null && r.condition() != null && !r.condition().isBlank())
                    .map(r -> validChunkIds.contains(r.chunkId()) ? r
                            : new DecisionTableRow(r.condition(), r.outcome(), r.exception(), null))
                    .limit(8)
                    .toList();
        } catch (Exception e) {
            metrics.recordLlmCallError("decision_table");
            return List.of();
        } finally {
            metrics.stopLlmCall(sample, "decision_table");
        }
    }

    /**
     * Traces a concrete "what if" scenario (from {@code question}) through the
     * retrieved decision logic. Only ever called when the question already looks
     * like a scenario (see RagService.looksLikeScenarioQuestion) — the relevance
     * gate in {@link #SCENARIO_TRACE_SYSTEM_PROMPT} is a safety net on top of that,
     * not the primary gate, so this never runs for an ordinary question.
     */
    public ScenarioTrace extractScenarioTrace(String question, String answer, String contextBlock,
                                               List<ChunkResult> chunks) {
        Timer.Sample sample = metrics.startLlmCall();
        try {
            Set<String> validChunkIds = chunks.stream().map(ChunkResult::chunkId).collect(Collectors.toSet());
            String userMessage = buildExtractionUserMessage(question, answer, contextBlock);
            var response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(SCENARIO_TRACE_SYSTEM_PROMPT), new UserMessage(userMessage))));
            String json = extractJsonObject(response.getResult().getOutput().getText());
            ScenarioTrace trace = objectMapper.readValue(json, ScenarioTrace.class);
            if (trace == null || trace.steps() == null || trace.steps().isEmpty()) {
                return new ScenarioTrace(List.of(), null);
            }
            List<ScenarioStep> steps = trace.steps().stream()
                    .filter(s -> s != null && s.condition() != null && !s.condition().isBlank())
                    .map(s -> validChunkIds.contains(s.chunkId()) ? s
                            : new ScenarioStep(s.condition(), s.result(), s.explanation(), null))
                    .limit(8)
                    .toList();
            return new ScenarioTrace(steps, trace.outcome());
        } catch (Exception e) {
            metrics.recordLlmCallError("scenario_trace");
            return new ScenarioTrace(List.of(), null);
        } finally {
            metrics.stopLlmCall(sample, "scenario_trace");
        }
    }

    public List<DataDictionaryEntry> extractDataDictionary(String question, String answer, String contextBlock,
                                                             List<ChunkResult> chunks) {
        Timer.Sample sample = metrics.startLlmCall();
        try {
            Set<String> validChunkIds = chunks.stream().map(ChunkResult::chunkId).collect(Collectors.toSet());
            String userMessage = buildExtractionUserMessage(question, answer, contextBlock);
            var response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(DATA_DICTIONARY_SYSTEM_PROMPT), new UserMessage(userMessage))));
            String json = extractJsonArray(response.getResult().getOutput().getText());
            List<DataDictionaryEntry> entries = objectMapper.readValue(json, new TypeReference<List<DataDictionaryEntry>>() {});
            return entries.stream()
                    .filter(e -> e != null && e.term() != null && !e.term().isBlank())
                    .map(e -> validChunkIds.contains(e.chunkId()) ? e
                            : new DataDictionaryEntry(e.term(), e.technicalName(), e.description(), null))
                    .limit(10)
                    .toList();
        } catch (Exception e) {
            metrics.recordLlmCallError("data_dictionary");
            return List.of();
        } finally {
            metrics.stopLlmCall(sample, "data_dictionary");
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
        Timer.Sample sample = metrics.startLlmCall();
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
            metrics.recordLlmCallError("business_flow_polish");
            return deterministic;
        } finally {
            metrics.stopLlmCall(sample, "business_flow_polish");
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

    private String extractJsonObject(String text) {
        if (text == null) return "{}";
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start == -1 || end == -1 || end < start) return "{}";
        return text.substring(start, end + 1);
    }
}
