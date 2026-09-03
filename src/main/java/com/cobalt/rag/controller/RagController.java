package com.cobalt.rag.controller;

import com.cobalt.rag.model.AskRequest;
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
     *   data: {"type":"metadata","sources":["GIROPGM.cbl"],"graphContext":["GIROPGM -[CALLS]-> PREMCOL"],"chunksRetrieved":5}
     *
     *   data: {"type":"token","content":"The GIRO "}
     *   data: {"type":"token","content":"processing program handles..."}
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

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "cobalt-rag-api"));
    }
}