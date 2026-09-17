package com.cobalt.rag.model;

import java.time.Instant;

/**
 * One detected security-relevant question, for display on the /admin page.
 * @param violationType "prompt_injection", "pii_requested", or "pii_provided"
 */
public record SecurityEvent(String id, String userEmail, String question, String violationType,
                             Instant createdAt) {
}
