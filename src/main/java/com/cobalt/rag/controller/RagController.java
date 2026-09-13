package com.cobalt.rag.controller;

import com.cobalt.rag.model.AskRequest;
import com.cobalt.rag.model.AskResponse;
import com.cobalt.rag.model.SuggestionsResponse;
import com.cobalt.rag.service.RagService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
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
    public Flux<String> ask(@RequestBody AskRequest request) {
        if (request.question() == null || request.question().isBlank()) {
            return Flux.error(new IllegalArgumentException("Question must not be blank"));
        }
        return ragService.askStream(request.question().trim());
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
    public ResponseEntity<AskResponse> askFormal(@RequestBody AskRequest request) {
        if (request.question() == null || request.question().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(ragService.ask(request.question().trim()));
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
}