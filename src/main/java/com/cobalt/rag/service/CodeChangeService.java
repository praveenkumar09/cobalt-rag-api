package com.cobalt.rag.service;

import com.cobalt.rag.model.ProgramSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Backs the "current vs. proposed" code-compare view opened from an Impact
 * Analysis entry. The current side is always the real ingested source (never
 * fabricated — see {@link VectorSearchService#fetchFullSource}); the proposed
 * side is a real LLM generation grounded in that exact source plus the actual
 * chat answer that recommended the change, not a canned diff.
 */
@Service
public class CodeChangeService {

    private static final String SYSTEM_PROMPT = """
            You are a COBOL code-modification assistant. You are given the ORIGINAL \
            source of one program exactly as it exists, and a description of a \
            recommended change taken from a prior question-and-answer exchange.

            Output the FULL modified source implementing that change:
            - Preserve every unrelated line exactly as-is — same columns, spacing, and formatting.
            - Change only what the described recommendation actually requires.
            - Output raw COBOL source only: no markdown code fences, no commentary, \
              no explanation before or after.
            """;

    private final VectorSearchService vectorSearch;
    private final ChatModel chatModel;
    // Used ONLY for proposeChangeStream's token flux — see its Javadoc (same
    // class used by RagService) for why chatModel.stream() can emit tokens
    // out of order. chatModel.call() (proposeChange, non-streaming) is
    // unaffected and stays exactly as-is — but reordering matters even more
    // here, since the output is COBOL SOURCE CODE, not prose: a misplaced
    // token could silently produce syntactically broken code.
    private final OrderedOpenAiStreamClient orderedStreamClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CodeChangeService(VectorSearchService vectorSearch, ChatModel chatModel,
                              OrderedOpenAiStreamClient orderedStreamClient) {
        this.vectorSearch = vectorSearch;
        this.chatModel = chatModel;
        this.orderedStreamClient = orderedStreamClient;
    }

    public Optional<ProgramSource> getSource(String programId) {
        return vectorSearch.fetchFullSource(programId);
    }

    public Optional<String> proposeChange(String programId, String question, String answer) {
        Optional<ProgramSource> source = vectorSearch.fetchFullSource(programId);
        if (source.isEmpty()) {
            return Optional.empty();
        }

        var response = chatModel.call(
                new Prompt(List.of(
                        new SystemMessage(SYSTEM_PROMPT),
                        new UserMessage(buildUserMessage(programId, question, answer, source.get().content()))
                ))
        );

        String text = response.getResult().getOutput().getText();
        return Optional.of(stripCodeFences(text));
    }

    /**
     * Streaming variant used when the user's response-mode setting is "Live" —
     * same SSE token/[DONE] shape RagService.askStream already uses. Emits a
     * single "error" event (rather than an HTTP error status, since the SSE
     * response has already started) when the program has no source to ground
     * the proposal against.
     */
    public Flux<String> proposeChangeStream(String programId, String question, String answer) {
        Optional<ProgramSource> source = vectorSearch.fetchFullSource(programId);
        if (source.isEmpty()) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("type", "error");
            error.put("message", "Source not available for " + programId);
            return Flux.just(toJson(error), "[DONE]");
        }

        String userMessage = buildUserMessage(programId, question, answer, source.get().content());

        Flux<String> tokenFlux = orderedStreamClient.streamText(SYSTEM_PROMPT, userMessage)
        .mapNotNull(text -> {
            if (text == null || text.isEmpty()) return null;
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("type", "token");
            payload.put("content", text);
            return toJson(payload);
        });

        return Flux.concat(tokenFlux, Flux.just("[DONE]"));
    }

    private String buildUserMessage(String programId, String question, String answer, String currentSource) {
        return """
                Original question: %s

                Recommended change (from the assistant's answer):
                %s

                ORIGINAL SOURCE of %s:
                %s
                """.formatted(question, answer, programId, currentSource);
    }

    private String stripCodeFences(String text) {
        if (text == null) return "";
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline != -1) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            int lastFence = trimmed.lastIndexOf("```");
            if (lastFence != -1) {
                trimmed = trimmed.substring(0, lastFence);
            }
        }
        return trimmed.trim();
    }

    private String toJson(Map<String, ?> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"error\":\"serialization failed\"}";
        }
    }
}
