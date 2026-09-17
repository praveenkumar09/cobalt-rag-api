package com.cobalt.rag.controller;

import com.cobalt.rag.model.SecurityStats;
import com.cobalt.rag.service.AuthStore;
import com.cobalt.rag.service.SecurityEventStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of detected security events (prompt injection / PII), for
 * the /admin page. Detection itself happens in {@link com.cobalt.rag.service.RagService}
 * as part of answering each question — see its Security Guidelines system
 * prompt section. Deliberately open to any signed-in user for now, same as
 * {@link FeedbackController} — there is no admin role concept yet.
 */
@RestController
public class SecurityController {

    private final SecurityEventStore securityEventStore;
    private final AuthStore authStore;

    public SecurityController(SecurityEventStore securityEventStore, AuthStore authStore) {
        this.securityEventStore = securityEventStore;
        this.authStore = authStore;
    }

    @GetMapping(value = "/api/admin/security/stats", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SecurityStats> stats(
            @RequestHeader(value = "X-Session-Token", required = false) String token,
            @RequestParam(defaultValue = "50") int limit) {
        try {
            authStore.requireUserId(token);
            return ResponseEntity.ok(securityEventStore.stats(limit));
        } catch (AuthStore.InvalidCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }
}
