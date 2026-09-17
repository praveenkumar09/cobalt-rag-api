package com.cobalt.rag.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Central place for the custom application metrics this service publishes to
 * Micrometer (scraped by Prometheus at /actuator/prometheus, visualized in
 * Grafana) — on top of Spring Boot's built-in HTTP/JVM metrics. Every LLM call
 * in {@link RagService} and {@link BusinessInsightService} reports its
 * duration and, on failure, an error count here, since those calls are the
 * dominant cost/latency driver of this app and were previously invisible
 * (several fail silently and fall back to an empty result).
 */
@Component
public class RagMetrics {

    private final MeterRegistry registry;

    public RagMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Start timing one LLM call; pair with {@link #stopLlmCall}. */
    public Timer.Sample startLlmCall() {
        return Timer.start(registry);
    }

    /** @param type e.g. "answer", "business_rules", "technical_rules", "decision_table",
     *              "data_dictionary", "business_flow_polish", "followups", "starter_suggestions" */
    public void stopLlmCall(Timer.Sample sample, String type) {
        sample.stop(Timer.builder("llm.calls")
                .description("Duration of each LLM call, by call type")
                .tag("type", type)
                .register(registry));
    }

    /** An LLM call that threw, or whose output couldn't be parsed into the expected shape. */
    public void recordLlmCallError(String type) {
        Counter.builder("llm.call.errors")
                .description("LLM calls that failed or returned unparsable output, by call type")
                .tag("type", type)
                .register(registry)
                .increment();
    }

    /** @param outcome "in_scope", "out_of_scope", or "security_violation" */
    public void recordAnswer(String outcome) {
        Counter.builder("rag.answers")
                .description("Answers returned, tagged by outcome")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    /** @param type "prompt_injection", "pii_requested", or "pii_provided" */
    public void recordSecurityViolation(String type) {
        Counter.builder("rag.security.violations")
                .description("Questions flagged as prompt injection, a PII request, or PII volunteered by the user")
                .tag("type", type)
                .register(registry)
                .increment();
    }

    public void recordRateLimitExceeded() {
        Counter.builder("rag.rate_limit.exceeded")
                .description("Requests to /api/ask or /api/ask/formal rejected for exceeding the per-caller rate limit")
                .register(registry)
                .increment();
    }

    public void recordChunksRetrieved(int count) {
        DistributionSummary.builder("rag.chunks.retrieved")
                .description("Number of chunks returned by vector search per question")
                .register(registry)
                .record(count);
    }

    public void recordGraphContext(boolean hasContext) {
        Counter.builder("rag.graph_context")
                .description("Questions tagged by whether graph search returned any relationships")
                .tag("has_context", String.valueOf(hasContext))
                .register(registry)
                .increment();
    }

    public void recordImpactAnalysisTriggered() {
        Counter.builder("rag.impact_analysis.triggered")
                .description("Questions that triggered an impact-analysis graph traversal")
                .register(registry)
                .increment();
    }
}
