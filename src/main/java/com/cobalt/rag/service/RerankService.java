package com.cobalt.rag.service;

import com.cobalt.rag.model.ChunkResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Narrows a wider hybrid-retrieval candidate pool (see VectorSearchService) down
 * to the chunks that actually answer the question, before they reach the answer
 * prompt — replacing "take the top-K by raw similarity" with "widen the net,
 * then have the model pick the best K from a bigger, hybrid-retrieved pool."
 *
 * <p>One fast, structured-output LLM call: given the question and each
 * candidate's (chunkId, sectionPurpose, a short snippet), returns which
 * candidate chunkIds are genuinely relevant, ordered best-first. Kept
 * deliberately cheap (short JSON output, no free text) since this adds a real
 * LLM call to the critical path before the answer starts.
 */
@Service
public class RerankService {

    private static final int SNIPPET_CHARS = 300;

    private static final ResponseFormat JSON_OBJECT_FORMAT =
            ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build();

    private static final String RERANK_SYSTEM_PROMPT = """
            You are given a user's question and a numbered list of candidate code
            chunks (each with its id, a business-purpose summary, and a short code
            snippet), retrieved by a hybrid vector+keyword search that deliberately
            over-retrieves — some candidates are genuinely relevant, others are
            near-miss noise (similar wording but the wrong program, a shared
            copybook that happens to match a keyword, etc.).

            Select ONLY the candidates that a well-grounded answer to the question
            would actually need to cite, ordered best-first (most directly relevant
            first). Exclude a candidate entirely rather than including it "just in
            case" — a shorter, precise list is better than a padded one. It is valid
            to return an empty list if truly none of the candidates address the
            question.

            Respond with ONLY a JSON object with exactly one key, "relevantChunkIds":
            an ordered array of the chunk id strings you selected, no markdown
            fences, no commentary. Example:
            {"relevantChunkIds":["SURRPGM.cbl#2200-VALIDATE-SURRENDER","POLRNWL.cbl#1000-MAIN"]}
            """;

    private final ChatModel chatModel;
    private final RagMetrics metrics;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RerankService(ChatModel chatModel, RagMetrics metrics) {
        this.chatModel = chatModel;
        this.metrics = metrics;
    }

    /**
     * Reorders and filters {@code candidates} down to the best {@code finalCount}
     * (or fewer). Never throws — on any failure (LLM error, malformed output,
     * the model returning nothing usable), falls back to the first
     * {@code finalCount} candidates in their original (RRF-ranked) order, so a
     * rerank hiccup degrades to pre-rerank behavior rather than breaking the
     * question.
     */
    public List<ChunkResult> rerank(String question, List<ChunkResult> candidates, int finalCount) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        Timer.Sample sample = metrics.startLlmCall();
        try {
            Map<String, ChunkResult> byId = new LinkedHashMap<>();
            StringBuilder candidateList = new StringBuilder();
            int i = 1;
            for (ChunkResult c : candidates) {
                byId.put(c.chunkId(), c);
                candidateList.append(i++).append(". chunkId: ").append(c.chunkId())
                        .append("\n   purpose: ").append(nullToEmpty(c.sectionPurpose()))
                        .append("\n   snippet: ").append(snippet(c.content()))
                        .append("\n");
            }

            String userMessage = "Question: " + question + "\n\nCandidates:\n" + candidateList;
            var response = chatModel.call(new Prompt(
                    List.of(new SystemMessage(RERANK_SYSTEM_PROMPT), new UserMessage(userMessage)),
                    OpenAiChatOptions.builder().responseFormat(JSON_OBJECT_FORMAT).build()));

            JsonNode root = objectMapper.readTree(response.getResult().getOutput().getText());
            List<ChunkResult> ordered = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonNode idNode : root.path("relevantChunkIds")) {
                String id = idNode.asText(null);
                ChunkResult match = (id == null) ? null : byId.get(id);
                if (match != null && seen.add(id)) {
                    ordered.add(match);
                }
            }
            if (ordered.isEmpty()) {
                return candidates.subList(0, Math.min(finalCount, candidates.size()));
            }
            return ordered.subList(0, Math.min(finalCount, ordered.size()));
        } catch (Exception e) {
            metrics.recordLlmCallError("rerank");
            return candidates.subList(0, Math.min(finalCount, candidates.size()));
        } finally {
            metrics.stopLlmCall(sample, "rerank");
        }
    }

    private String snippet(String content) {
        if (content == null) return "";
        String trimmed = content.strip();
        return trimmed.length() <= SNIPPET_CHARS ? trimmed : trimmed.substring(0, SNIPPET_CHARS);
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
