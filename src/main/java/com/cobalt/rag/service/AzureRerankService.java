package com.cobalt.rag.service;

import com.cobalt.rag.model.ChunkResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.azure.openai.AzureOpenAiResponseFormat;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Azure OpenAI variant of {@link RerankService} — identical behavior and prompt,
 * the only difference is the chat-options type passed to {@code chatModel.call()}.
 *
 * <p>Why a separate class instead of a code change to {@link RerankService}: that
 * class builds its call with {@code org.springframework.ai.openai.OpenAiChatOptions}
 * (and {@code org.springframework.ai.openai.api.ResponseFormat}), which is the
 * OpenAI-specific options type from {@code spring-ai-openai}. When the injected
 * {@link ChatModel} bean is actually Spring AI's {@code AzureOpenAiChatModel} (an
 * Azure-backed deployment, as on the office laptop), that OpenAI-specific options
 * object isn't the type Azure's model implementation looks for, so JSON-mode
 * enforcement silently doesn't apply — the rerank call starts producing
 * non-JSON/malformed output more often, which this service's existing
 * fail-safe catch swallows by falling back to pre-rerank order. Net effect:
 * reranking quietly stops doing anything useful on Azure, without an obvious
 * error — this class fixes that by using Azure's own options type instead:
 * {@link AzureOpenAiChatOptions} + {@link AzureOpenAiResponseFormat#JSON}.
 *
 * <p>Not wired into {@code RagService} — this is a standalone class for the
 * Azure-backed environment to swap in for {@link RerankService} where needed
 * (e.g. change {@code RagService}'s constructor parameter type, or introduce a
 * shared interface, whichever fits how the two environments are meant to
 * switch between OpenAI and Azure OpenAI). {@link RerankService} itself is
 * untouched.
 */
@Service
public class AzureRerankService {

    private static final int SNIPPET_CHARS = 300;

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

    public AzureRerankService(ChatModel chatModel, RagMetrics metrics) {
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
                    AzureOpenAiChatOptions.builder().responseFormat(AzureOpenAiResponseFormat.JSON).build()));

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
