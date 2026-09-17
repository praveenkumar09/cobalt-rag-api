package com.cobalt.rag.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown by {@link RagController#ask} when {@link com.cobalt.rag.service.AskRateLimiter}
 * rejects a request — the @ResponseStatus lets Spring's default exception
 * resolver map this to 429 even though it's thrown into a reactive Flux
 * return type (askFormal, the non-streaming sibling, returns the status
 * directly instead since it isn't reactive).
 */
@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException() {
        super("Rate limit exceeded — too many requests");
    }
}
