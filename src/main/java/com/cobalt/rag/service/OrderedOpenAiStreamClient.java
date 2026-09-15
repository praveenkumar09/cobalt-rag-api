package com.cobalt.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * Streams chat completion text deltas directly from OpenAI's HTTP API,
 * deliberately bypassing Spring AI's {@code OpenAiChatModel}/{@code OpenAiApi}
 * streaming path ({@code chatModel.stream(...)}).
 *
 * <p><b>Why this exists.</b> Spring AI 1.0.0-M6's {@code OpenAiApi
 * .chatCompletionStream()} reassembles the raw per-token SSE chunks through
 * {@code windowUntil(...).concatMapIterable(...).flatMap(mono -> mono)} — and
 * {@code Flux.flatMap} does not guarantee emission order, only that each inner
 * publisher gets a fair chance to run concurrently (that's {@code concatMap}'s
 * job, which this pipeline does not use for the final flatten). Under bursty
 * delivery — most likely right at the very start of a stream, when several
 * small tokens can arrive in rapid succession as generation "warms up" — this
 * can emit tokens out of their original sequence: same words, none missing,
 * none duplicated, just displaced. That exactly matches an observed real
 * example (confirmed via {@code chatModel.call()} / non-streaming producing
 * correct output for the identical prompt+context, which rules out the model,
 * prompt, or retrieved context as the cause — the corruption is specific to
 * code that only runs in the streaming path).
 *
 * <p>This class makes the SAME OpenAI HTTP call directly via {@link WebClient},
 * whose {@code bodyToFlux(String.class)} decodes the SSE response body
 * strictly sequentially — no windowing, no flatMap, nothing that can reorder
 * emissions — so token order is always exactly what OpenAI sent. No tool/
 * function-calling support is implemented here since none of this
 * application's prompts use it; if that ever changes, this would need
 * extending (or the affected call should go back through Spring AI's
 * {@code ChatModel} instead).
 */
@Service
public class OrderedOpenAiStreamClient {

    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}")
    private String defaultModel;

    @Value("${spring.ai.openai.chat.options.temperature:0.3}")
    private double defaultTemperature;

    public OrderedOpenAiStreamClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    /** Streams the assistant's reply text, delta by delta, in guaranteed original order. */
    public Flux<String> streamText(String systemPrompt, String userMessage) {
        Map<String, Object> body = Map.of(
                "model", defaultModel,
                "stream", true,
                "temperature", defaultTemperature,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userMessage)
                )
        );

        return webClient.post()
                .uri(baseUrl + "/v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .filter(data -> !data.isBlank() && !data.equals("[DONE]"))
                .mapNotNull(this::extractDelta);
    }

    private String extractDelta(String sseData) {
        try {
            JsonNode root = mapper.readTree(sseData);
            JsonNode delta = root.path("choices").path(0).path("delta").path("content");
            if (delta.isMissingNode() || delta.isNull()) return null;
            String text = delta.asText();
            return (text == null || text.isEmpty()) ? null : text;
        } catch (Exception e) {
            // A malformed/partial SSE frame is not fatal to the stream — skip it
            // rather than breaking the whole response over one bad chunk.
            return null;
        }
    }
}
