package com.cobalt.rag.controller;

import com.cobalt.rag.model.ProgramSource;
import com.cobalt.rag.model.ProposeChangeRequest;
import com.cobalt.rag.model.ProposeChangeResponse;
import com.cobalt.rag.service.CodeChangeService;
import org.springframework.http.HttpStatus;
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
     * the original question/answer that recommended the change — agentically,
     * via locate/generate/splice, for a file too large to send whole (see
     * {@link CodeChangeService}'s Javadoc). 404 if the program has no ingested
     * source to ground the proposal against; 422 (with a specific message in
     * the body, plus the {@code steps} the agent took before giving up) if the
     * agent could not confidently produce a change at all.
     */
    @PostMapping(value = "/{programId}/propose-change",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProposeChangeResponse> proposeChange(
            @PathVariable String programId,
            @RequestBody ProposeChangeRequest request) {
        var outcome = codeChangeService.proposeChange(programId, request.question(), request.answer());
        if (outcome.notFound()) {
            return ResponseEntity.notFound().build();
        }
        if (outcome.errorMessage() != null) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new ProposeChangeResponse(programId, null, outcome.errorMessage(), outcome.steps(), null));
        }
        return ResponseEntity.ok(new ProposeChangeResponse(
                programId, outcome.proposedSource(), outcome.steps(), outcome.businessSummary()));
    }

    /**
     * POST /api/programs/{programId}/propose-change/stream  — SSE variant, used
     * when the user's response-mode setting is "Live". Event frames:
     * {@code {"type":"thinking","message":"..."}} (zero or more, as the agent's
     * locate/generate/splice steps happen), then exactly one of
     * {@code {"type":"result","proposedSource":"..."}} or
     * {@code {"type":"error","message":"..."}}, followed by {@code [DONE]} (an
     * SSE stream can't change its HTTP status once it has started, so a
     * not-found program is also just an error frame here).
     */
    @PostMapping(value = "/{programId}/propose-change/stream",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> proposeChangeStream(
            @PathVariable String programId,
            @RequestBody ProposeChangeRequest request) {
        return codeChangeService.proposeChangeStream(programId, request.question(), request.answer());
    }
}
