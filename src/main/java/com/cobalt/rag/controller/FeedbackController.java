package com.cobalt.rag.controller;

import com.cobalt.rag.model.FeedbackRequest;
import com.cobalt.rag.model.FeedbackStats;
import com.cobalt.rag.service.AuthStore;
import com.cobalt.rag.service.FeedbackStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * "Suggest improvement" feedback, scoped by the authenticated user resolved
 * from the X-Session-Token header (see {@link AuthStore}). The stats endpoint
 * under /api/admin is deliberately open to any signed-in user for now — there
 * is no admin role concept yet in this internal tool.
 */
@RestController
public class FeedbackController {

    private final FeedbackStore feedbackStore;
    private final AuthStore authStore;

    public FeedbackController(FeedbackStore feedbackStore, AuthStore authStore) {
        this.feedbackStore = feedbackStore;
        this.authStore = authStore;
    }

    @PostMapping(value = "/api/feedback", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> submit(
            @RequestHeader(value = "X-Session-Token", required = false) String token,
            @RequestBody FeedbackRequest request) {
        if (request.message() == null || request.message().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Feedback message is required"));
        }
        try {
            String userId = authStore.requireUserId(token);
            feedbackStore.submit(userId, request.question(), request.answer(), request.message());
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (AuthStore.InvalidCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    @GetMapping(value = "/api/admin/feedback/stats", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FeedbackStats> stats(
            @RequestHeader(value = "X-Session-Token", required = false) String token,
            @RequestParam(defaultValue = "50") int limit) {
        try {
            authStore.requireUserId(token);
            return ResponseEntity.ok(feedbackStore.stats(limit));
        } catch (AuthStore.InvalidCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }
}
