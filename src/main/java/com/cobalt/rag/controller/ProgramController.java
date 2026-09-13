package com.cobalt.rag.controller;

import com.cobalt.rag.model.ProgramSource;
import com.cobalt.rag.model.ProposeChangeRequest;
import com.cobalt.rag.model.ProposeChangeResponse;
import com.cobalt.rag.service.CodeChangeService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * Backs the "current vs. proposed" code-compare view opened from an Impact
 * Analysis entry (see {@link CodeChangeService}).
 */
@RestController
@RequestMapping("/api/programs")
public class ProgramController {

    private final CodeChangeService codeChangeService;

    public ProgramController(CodeChangeService codeChangeService) {
        this.codeChangeService = codeChangeService;
    }

    /** GET /api/programs/{programId}/source — the program's real, ingested source. */
    @GetMapping(value = "/{programId}/source", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProgramSource> source(@PathVariable String programId) {
        return codeChangeService.getSource(programId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * POST /api/programs/{programId}/propose-change
     *
     * Generates a modified version of the program's real source, grounded in
     * the original question/answer that recommended the change. 404 if the
     * program has no ingested source to ground the proposal against.
     */
    @PostMapping(value = "/{programId}/propose-change",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProposeChangeResponse> proposeChange(
            @PathVariable String programId,
            @RequestBody ProposeChangeRequest request) {
        return codeChangeService.proposeChange(programId, request.question(), request.answer())
                .map(proposed -> ResponseEntity.ok(new ProposeChangeResponse(programId, proposed)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * POST /api/programs/{programId}/propose-change/stream  — SSE variant, used
     * when the user's response-mode setting is "Live". Same event shape as
     * {@code /api/ask}: {@code data: {"type":"token","content":"..."}} frames
     * followed by {@code data: [DONE]}; a program with no ingested source emits
     * a single {@code {"type":"error", ...}} frame instead (an SSE stream can't
     * change its HTTP status once it has started).
     */
    @PostMapping(value = "/{programId}/propose-change/stream",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> proposeChangeStream(
            @PathVariable String programId,
            @RequestBody ProposeChangeRequest request) {
        return codeChangeService.proposeChangeStream(programId, request.question(), request.answer());
    }
}
