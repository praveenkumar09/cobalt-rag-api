package com.cobalt.rag.controller;

import com.cobalt.rag.model.AskRequest;
import com.cobalt.rag.model.AskResponse;
import com.cobalt.rag.model.FunctionalRequirementRequest;
import com.cobalt.rag.model.FunctionalRequirementResponse;
import com.cobalt.rag.model.SuggestionsResponse;
import com.cobalt.rag.model.TestScenariosRequest;
import com.cobalt.rag.model.TestScenariosResponse;
import com.cobalt.rag.service.AskRateLimiter;
import com.cobalt.rag.service.AuthStore;
import com.cobalt.rag.service.RagMetrics;
import com.cobalt.rag.service.RagService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class RagController {

    private final RagService ragService;
    private final AuthStore authStore;
    private final AskRateLimiter rateLimiter;
    private final RagMetrics metrics;

    public RagController(RagService ragService, AuthStore authStore, AskRateLimiter rateLimiter, RagMetrics metrics) {
        this.ragService = ragService;
        this.authStore = authStore;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
    }

    /**
     * /api/ask and /api/ask/formal don't require authentication (no 401 on a
     * missing/invalid token) — but when a valid session token IS present, we
     * resolve it so security-relevant questions (see RagService's Security
     * Guidelines) can be attributed to a user in the /admin audit log instead
     * of recorded as anonymous.
     */
    private String resolveUserIdOrNull(String token) {
        return authStore.resolveUserId(token).orElse(null);
    }

    /**
     * Rate-limit key: the resolved user id when available, otherwise the
     * caller's remote address — so unauthenticated callers (these endpoints
     * don't require a token) are still individually throttled rather than
     * sharing one global bucket.
     */
    private String rateLimitKey(String userId, HttpServletRequest request) {
        return userId != null ? "user:" + userId : "ip:" + request.getRemoteAddr();
    }

    /**
     * POST /api/ask  — streaming SSE endpoint
     *
     * Postman setup:
     *   Method  : POST
     *   URL     : http://localhost:8083/api/ask
     *   Headers : Content-Type: application/json
     *   Body    : { "question": "How does GIRO processing handle rejected collections?" }
     *
     * SSE event stream format:
     *
     *   data: {"type":"metadata","sources":[{"chunkId":"...","sourceFile":"GIROPGM.cbl","programId":"GIROPGM","sectionName":"2100-VALIDATE-GIRO","sectionPurpose":"...","lineStart":210,"lineEnd":268,"fileType":"cbl","similarity":0.62,"snippet":"..."}],"graphContext":[{"fromId":"GIROPGM","fromLabel":"GIROPGM","fromType":"COBOL_PROGRAM","relType":"CALLS","toId":"PREMCOL","toLabel":"PREMCOL","toType":"COBOL_PROGRAM"}],"chunksRetrieved":5}
     *
     *   data: {"type":"token","content":"The GIRO "}
     *   data: {"type":"token","content":"processing program handles..."}
     *
     *   data: {"type":"correction","sources":[]}   (only sent if the answer turned out to be out-of-scope)
     *
     *   data: {"type":"followups","questions":["How does PREMCOL validate the policy number?","..."]}
     *
     *   data: [DONE]
     */
    @PostMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> ask(
            @RequestHeader(value = "X-Session-Token", required = false) String token,
            @RequestBody AskRequest request,
            HttpServletRequest httpRequest) {
        if (request.question() == null || request.question().isBlank()) {
            return Flux.error(new IllegalArgumentException("Question must not be blank"));
        }
        String userId = resolveUserIdOrNull(token);
        if (!rateLimiter.tryAcquire(rateLimitKey(userId, httpRequest))) {
            metrics.recordRateLimitExceeded();
            return Flux.error(new RateLimitExceededException());
        }
        return ragService.askStream(request.question().trim(), userId, request.resolvedViewMode(), request.conversationId());
    }

    /**
     * POST /api/ask/formal  — non-streaming endpoint
     *
     * Same RAG functionality as {@link #ask}, but returns a single JSON response
     * instead of an SSE token stream.
     *
     * Postman setup:
     *   Method  : POST
     *   URL     : http://localhost:8083/api/ask/formal
     *   Headers : Content-Type: application/json
     *   Body    : { "question": "How does GIRO processing handle rejected collections?" }
     *
     * Response:
     *   {
     *     "answer": "The GIRO processing program...",
     *     "sources": [
     *       {
     *         "chunkId": "GIROPGM.cbl#2100-VALIDATE-GIRO",
     *         "sourceFile": "GIROPGM.cbl",
     *         "programId": "GIROPGM",
     *         "sectionName": "2100-VALIDATE-GIRO",
     *         "sectionPurpose": "Validates GIRO collection rejection codes before re-presentment.",
     *         "lineStart": 210,
     *         "lineEnd": 268,
     *         "fileType": "cbl",
     *         "similarity": 0.62,
     *         "snippet": "2100-VALIDATE-GIRO.\n    IF WS-REJECT-CODE = 'R01' ..."
     *       }
     *     ],
     *     "graphContext": [
     *       {
     *         "fromId": "GIROPGM", "fromLabel": "GIROPGM", "fromType": "COBOL_PROGRAM",
     *         "relType": "CALLS",
     *         "toId": "PREMCOL", "toLabel": "PREMCOL", "toType": "COBOL_PROGRAM"
     *       }
     *     ],
     *     "chunksRetrieved": 5,
     *     "followUpQuestions": [
     *       "How does PREMCOL validate the policy number?",
     *       "What happens if GIRO collection fails twice?",
     *       "Which programs call SURRPGM?"
     *     ]
     *   }
     *
     * Chunks scoring below cobalt.rag.similarity-threshold are dropped entirely, so an
     * off-topic question naturally yields an empty "sources" array and no follow-up
     * suggestions.
     */
    @PostMapping(value = "/ask/formal", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AskResponse> askFormal(
            @RequestHeader(value = "X-Session-Token", required = false) String token,
            @RequestBody AskRequest request,
            HttpServletRequest httpRequest) {
        if (request.question() == null || request.question().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String userId = resolveUserIdOrNull(token);
        if (!rateLimiter.tryAcquire(rateLimitKey(userId, httpRequest))) {
            metrics.recordRateLimitExceeded();
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        return ResponseEntity.ok(ragService.ask(request.question().trim(), userId, request.resolvedViewMode(), request.conversationId()));
    }

    /**
     * GET /api/suggestions  — home-screen starter question chips
     *
     * Generated from a random sample of whatever codebase is actually ingested
     * right now (see {@link RagService#getStarterSuggestions()}), never hardcoded,
     * so these can't drift out of sync with the loaded corpus. Cached server-side
     * for 30 minutes.
     *
     * Response:
     *   { "suggestions": ["What does CBTRN02C do when a transaction fails validation?", "...", "..."] }
     */
    @GetMapping(value = "/suggestions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SuggestionsResponse> suggestions() {
        return ResponseEntity.ok(new SuggestionsResponse(ragService.getStarterSuggestions()));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "cobalt-rag-api"));
    }

    /**
     * POST /api/functional-requirement  — "Export Functional Requirement Report"
     *
     * Generates a formal functional requirement document for one already-answered
     * chat question, grounded in the question, its answer, and whatever business
     * rules / decision table / data dictionary entries were already extracted for
     * it — no fresh retrieval, unlike {@code /ask}. Available for every business-mode
     * answer (see MessageBubble.tsx), unlike the scenario-only Change Impact Report.
     *
     * Postman setup:
     *   Method  : POST
     *   URL     : http://localhost:8083/api/functional-requirement
     *   Headers : Content-Type: application/json
     *   Body    : {
     *     "question": "What is the functionality of claims processing?",
     *     "answer": "<the answer text already shown in the chat>",
     *     "businessRules": [{"rule": "...", "chunkId": "..."}],
     *     "decisionTable": [{"condition": "...", "outcome": "...", "exception": null, "chunkId": "..."}],
     *     "dataDictionary": [{"term": "...", "technicalName": "...", "description": "...", "chunkId": "..."}]
     *   }
     *
     * Response: { "requirement": "## Overview\n..." }  (Markdown)
     */
    @PostMapping(value = "/functional-requirement",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FunctionalRequirementResponse> functionalRequirement(
            @RequestBody FunctionalRequirementRequest request) {
        if (request.question() == null || request.question().isBlank()
                || request.answer() == null || request.answer().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            String requirement = ragService.generateFunctionalRequirement(
                    request.question(), request.answer(),
                    request.businessRules(), request.decisionTable(), request.dataDictionary());
            return ResponseEntity.ok(new FunctionalRequirementResponse(requirement));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * POST /api/test-scenarios  — "Export Test Scenarios"
     *
     * Generates a QA test-scenario document for one already-answered chat
     * question, grounded in the question, its answer, and whatever business
     * rules / decision table entries were already extracted for it — no fresh
     * retrieval, same pattern as /api/functional-requirement. Available for a
     * business-mode answer that actually has business rules or a decision
     * table to derive test cases from (see MessageBubble.tsx's
     * showTestScenarios).
     *
     * Postman setup:
     *   Method  : POST
     *   URL     : http://localhost:8083/api/test-scenarios
     *   Headers : Content-Type: application/json
     *   Body    : {
     *     "question": "What if a claim is submitted on a lapsed policy?",
     *     "answer": "<the answer text already shown in the chat>",
     *     "businessRules": [{"rule": "...", "chunkId": "..."}],
     *     "decisionTable": [{"condition": "...", "outcome": "...", "exception": null, "chunkId": "..."}]
     *   }
     *
     * Response: { "scenarios": "## Test Scenarios: ...\n..." }  (Markdown)
     */
    @PostMapping(value = "/test-scenarios",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TestScenariosResponse> testScenarios(@RequestBody TestScenariosRequest request) {
        if (request.question() == null || request.question().isBlank()
                || request.answer() == null || request.answer().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            String scenarios = ragService.generateTestScenarios(
                    request.question(), request.answer(), request.businessRules(), request.decisionTable());
            return ResponseEntity.ok(new TestScenariosResponse(scenarios));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}